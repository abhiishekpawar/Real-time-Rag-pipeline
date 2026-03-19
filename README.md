# 🚀 Real-Time RAG Pipeline with Confluent Flink, Kafka & Weaviate

> **Turn a Kafka stream into an AI-powered Q&A system — no separate orchestration layer needed.**
>
> Built using Confluent Cloud Flink SQL, managed LLM models, a sink connector, and a Weaviate vector database.

📖 [Read the full article on Medium](https://medium.com/@pdaengg94/building-a-real-time-rag-pipeline-with-confluent-flink-kafka-and-weaviate-09f2c3e0de15) &nbsp;|&nbsp; 🎥 [Watch the Demo](https://drive.google.com/file/d/1xwbbFpetOa4NiHvglvuDZMHlXjJw6au2/view?usp=drivesdk)

---

## 📌 Table of Contents

- [Why Flink for RAG?](#why-flink-for-rag)
- [Architecture Overview](#architecture-overview)
- [Tech Stack](#tech-stack)
- [Pipeline 1 — Ingestion: Embedding Your Knowledge Base](#pipeline-1--ingestion-embedding-your-knowledge-base)
    - [Step 1: Create the Managed Embedding Model](#step-1-create-the-managed-embedding-model)
    - [Step 2: Stream Embeddings into the Topic](#step-2-stream-embeddings-into-the-topic)
    - [Step 3: Sink Vectors to Weaviate via Connector](#step-3-sink-vectors-to-weaviate-via-connector)
- [Pipeline 2 — Query: Real-Time User Q&A](#pipeline-2--query-real-time-user-qa)
    - [Step 4: Embed the User's Question](#step-4-embed-the-users-question)
    - [Step 5: The Java Consumer — Search, Generate & Push](#step-5-the-java-consumer--search-generate--push)
    - [Step 6: Text Generation with Phi-3.5-mini](#step-6-text-generation-with-phi-35-mini)
- [Why the Weaviate UDF Was Moved Out of Flink SQL](#why-the-weaviate-udf-was-moved-out-of-flink-sql)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Real-World Constraints & Notes](#real-world-constraints--notes)

---

## 🤔 Why Flink for RAG?

Most RAG tutorials assume a **batch world**:
1. Crawl your docs
2. Embed them offline
3. Load a vector DB
4. Stand up a REST API

**The problem?** A new incident fix added at 9 AM won't be searchable until the next pipeline run. That gap creates latency.

**Confluent Cloud Flink eliminates that gap.**

With the `AI_EMBEDDING()` function, you can call a managed embedding model directly inside a Flink SQL statement — meaning every new event on a Kafka topic gets embedded **within seconds of arrival**. A sink connector then continuously drains the embeddings topic into Weaviate, keeping the vector store **perpetually up to date** without any custom writer code.

---

## 🏗️ Architecture Overview

The system is composed of **two streaming pipelines**:

```
┌─────────────────────────────────────────────────────────────────────┐
│                     INGESTION PIPELINE                              │
│                                                                     │
│  incident_knowledge_base (Kafka Topic)                              │
│            │                                                        │
│            ▼                                                        │
│  Flink SQL: AI_EMBEDDING() via BAAI/bge-large-en-v1.5               │
│            │                                                        │
│            ▼                                                        │
│  incident_knowledge_base_embeddings (Kafka Topic)                   │
│            │                                                        │
│            ▼                                                        │
│  Weaviate Sink Connector ──────────────► Weaviate Vector DB         │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        QUERY PIPELINE                               │
│                                                                     │
│  user_questions (Kafka Topic)                                       │
│            │                                                        │
│            ▼                                                        │
│  Flink SQL: AI_EMBEDDING() via BAAI/bge-large-en-v1.5               │
│            │                                                        │
│            ▼                                                        │
│  user_questions_embedding (Kafka Topic)                             │
│            │                                                        │
│            ▼                                                        │
│  Java Spring Boot Consumer                                          │
│       ├── nearVector GraphQL → Weaviate (semantic search)           │
│       ├── Flink SQL: AI_COMPLETE() via Phi-3.5-mini-instruct        │
│       └── WebSocket push → React Frontend                           │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Tech Stack

| Component | Technology |
|---|---|
| **Stream Processing** | Confluent Cloud Flink SQL |
| **Message Broker** | Apache Kafka (Confluent Cloud) |
| **Embedding Model** | BAAI/bge-large-en-v1.5 (Confluent Managed) |
| **Text Generation Model** | microsoft/Phi-3.5-mini-instruct (Confluent Managed) |
| **Vector Database** | Weaviate |
| **Sink Connector** | Confluent Weaviate Sink Connector |
| **Backend Consumer** | Java Spring Boot |
| **Frontend** | React (WebSocket) |

---

## Pipeline 1 — Ingestion: Embedding Your Knowledge Base

### Step 1: Create the Managed Embedding Model

Confluent Cloud Flink supports managed models via `CREATE MODEL`. We used BAAI's `bge-large-en-v1.5` — a strong general-purpose English embedding model — hosted directly on the Confluent platform. **No API keys to manage, no GPU instances to provision.**

```sql
CREATE MODEL `managed_model_embedding`
INPUT  (text      STRING)
OUTPUT (embedding ARRAY<FLOAT>)
WITH (
  'provider'         = 'confluent',
  'task'             = 'embedding',
  'confluent.model'  = 'BAAI/bge-large-en-v1.5'
);
```

---

### Step 2: Stream Embeddings into the Topic

The Flink job reads from the `incident_knowledge_base` Kafka topic, which contains incident titles and their known fixes. It concatenates them into a single text string and calls `AI_EMBEDDING` to generate an embedding vector.

The result — `key`, `class`, `properties`, and `vector` — is written to the `incident_knowledge_base_embeddings` topic.

```sql
INSERT INTO `incident_knowledge_base_embeddings`
SELECT
  CAST(NULL AS BYTES)                        AS key,
  'Incident_knowledge_base_embeddings'       AS class,
  ROW(incident_fix, incident_id, incident_title)
                                             AS properties,
  embedding                                  AS vector
FROM `default`.`poc-cluster`.`incident_knowledge_base`,
LATERAL TABLE(
  ML_PREDICT(
    '`default`.`poc-cluster`.`managed_model_embedding`',
    CONCAT(
      'incident: ', incident_title,
      ' fix: ',      incident_fix
    )
  )
) AS T(embedding);
```

---

### Step 3: Sink Vectors to Weaviate via Connector

Once the embedding vectors are on the `incident_knowledge_base_embeddings` topic, we configure a **Weaviate Sink Connector** directly on that topic — no custom Java consumer required.

The connector continuously polls for new messages and writes each record — including the `vector` field and its associated properties (`incident_id`, `incident_title`, `incident_fix`) — into the Weaviate collection.

> **This keeps the vector database in lock-step with the Kafka topic without any application code in the write path.**

---

## Pipeline 2 — Query: Real-Time User Q&A

### Step 4: Embed the User's Question

User questions are published to the `user_questions` Kafka topic. A second Flink job picks them up, runs the **same managed embedding model**, and writes the resulting vector to `user_questions_embedding`.

> ⚠️ **Critical:** Using the same model for both document embeddings and query embeddings is essential — the vector space must be consistent for cosine similarity to be meaningful.

```sql
INSERT INTO user_questions_embedding
SELECT
  CAST(NULL AS BYTES) AS key,
  embedding           AS vector
FROM `default`.`poc-cluster`.`user_questions`,
LATERAL TABLE(
  ML_PREDICT(
    '`default`.`poc-cluster`.`managed_model_embedding`',
    question
  )
) AS T(embedding);
```

---

### Step 5: The Java Consumer — Search, Generate & Push

The Spring Boot consumer listens on `user_questions_embedding`, performs a `nearVector` GraphQL query against Weaviate, retrieves the top-1 nearest incident fix, and then calls the Flink text generation model to polish the response before pushing it to the frontend via WebSocket.

**Weaviate UDF — nearVector Search:**

```java
public String eval(double[] vector) {
  String vectorJson = Arrays.toString(vector);

  String graphqlQuery =
    "{ \"query\": \"{ Get { Incident_knowledge_base_embeddings("
    + "nearVector:{vector:" + vectorJson + "},limit:1) {"
    + "incident_id incident_title incident_fix "
    + "_additional { distance } }}}\" }";

  HttpURLConnection conn = (HttpURLConnection)
      new URL(WEAVIATE_URL).openConnection();
  conn.setRequestMethod("POST");
  conn.setRequestProperty("Content-Type", "application/json");
  conn.setConnectTimeout(30000);
  conn.setReadTimeout(30000);
  // write body, read response ...
  return response.toString();
}
```

**Kafka Consumer — End-to-End Orchestration:**

```java
@KafkaListener(topics = "user_questions_embedding")
public void consume(VectorEmbedding message) {
  // 1. Convert List<Float> → double[]
  double[] vector = message.getVector()
      .stream().mapToDouble(Float::doubleValue).toArray();

  // 2. Semantic search in Weaviate
  String hit = weaviateCallUDF.eval(vector);
  String incidentFix = /* parse JSON → incident_fix */;

  // 3. Call text generation model via Flink SQL
  String prompt =
      "Rewrite in a clearer and professional way: " + incidentFix;
  String sql =
      "SELECT response FROM (VALUES ('" + prompt +
      "')) AS t(prompt), LATERAL TABLE(AI_COMPLETE(" +
      "'text_generation_llm_model', prompt))";

  env.executeSql(sql).collect()
      .forEachRemaining(row -> {
        String response = row.getField("response").toString();
        // 4. Push to frontend via WebSocket
        messagingTemplate.convertAndSend("/topic/messages", response);
      });
}
```

---

### Step 6: Text Generation with Phi-3.5-mini

Once the nearest incident fix is retrieved from Weaviate, we don't return it verbatim. The raw fix is passed through a **text generation model** to rewrite it as a clean, professional resolution note.

We used Confluent's managed `microsoft/Phi-3.5-mini-instruct` — a compact instruction-tuned model well-suited for short reformatting tasks.

```sql
CREATE MODEL text_generation_llm_model
INPUT  (prompt   STRING)
OUTPUT (response STRING)
WITH (
  'provider'         = 'confluent',
  'task'             = 'text_generation',
  'confluent.model'  = 'microsoft/Phi-3.5-mini-instruct'
);
```

---

## ⚠️ Why the Weaviate UDF Was Moved Out of Flink SQL

The natural design would call Weaviate's `nearVector` API directly inside a Flink SQL UDF — registering `WeaviateCallUDF` as a function and invoking it inline in the query pipeline SQL.

**However**, Confluent Cloud Flink jobs run in a **managed, sandboxed network environment**. During our POC we hit outbound network connectivity restrictions — the Flink execution environment could not reach our external Weaviate instance.

Rather than blocking on a network/firewall resolution, we moved the UDF call into a **Java Kafka consumer running in our own infrastructure**, which has direct access to Weaviate.

> 💡 **Note:** If you are running Flink in an environment where outbound connectivity to external services is unrestricted, you could register `WeaviateCallUDF` directly as a Flink function and call it from SQL — removing the need for the Java consumer entirely. The consumer is a pragmatic workaround for the managed cloud network boundary.

---

## 📁 Project Structure

```
real-time-rag-pipeline/
│
├── flink-sql/
│   ├── 01_create_embedding_model.sql        # Managed embedding model definition
│   ├── 02_stream_embeddings.sql             # Ingestion pipeline Flink SQL
│   ├── 03_embed_user_questions.sql          # Query pipeline Flink SQL
│   └── 04_create_text_generation_model.sql  # Text generation model definition
│
├── java-consumer/
│   ├── src/
│   │   └── main/java/
│   │       ├── consumer/
│   │       │   └── VectorEmbeddingConsumer.java   # Kafka listener
│   │       └── udf/
│   │           └── WeaviateCallUDF.java            # nearVector GraphQL call
│   └── pom.xml
│
├── weaviate/
│   └── schema.json                          # Weaviate collection schema
│
├── frontend/
│   └── src/
│       └── App.jsx                          # React WebSocket frontend
│
└── README.md
```

---

## 🚀 Getting Started

### Prerequisites

- [Confluent Cloud](https://confluent.io) account with Flink enabled
- [Weaviate](https://weaviate.io) instance (cloud or self-hosted)
- Java 17+
- Node.js 18+ (for the React frontend)

### 1. Set up Kafka Topics

Create the following topics in your Confluent Cloud cluster:

```
incident_knowledge_base
incident_knowledge_base_embeddings
user_questions
user_questions_embedding
```

### 2. Deploy Flink SQL Statements

Run the SQL files in order via the Confluent Cloud Console or Flink SQL CLI:

```bash
# 1. Create the embedding model
flink sql -f flink-sql/01_create_embedding_model.sql

# 2. Start the ingestion pipeline
flink sql -f flink-sql/02_stream_embeddings.sql

# 3. Create the text generation model
flink sql -f flink-sql/04_create_text_generation_model.sql

# 4. Start the query embedding pipeline
flink sql -f flink-sql/03_embed_user_questions.sql
```

### 3. Configure the Weaviate Sink Connector

In Confluent Cloud, configure a sink connector on the `incident_knowledge_base_embeddings` topic pointing to your Weaviate instance. Use the schema defined in `weaviate/schema.json`.

### 4. Run the Java Consumer

```bash
cd java-consumer
./mvnw spring-boot:run
```

### 5. Start the React Frontend

```bash
cd frontend
npm install
npm start
```

---

## ⚙️ Configuration

| Config Key | Description | Example |
|---|---|---|
| `WEAVIATE_URL` | Weaviate GraphQL endpoint | `http://localhost:8080/v1/graphql` |
| `KAFKA_BOOTSTRAP_SERVERS` | Confluent Cloud bootstrap URL | `pkc-xxx.us-east-1.aws.confluent.cloud:9092` |
| `KAFKA_API_KEY` | Confluent Cloud API key | `ABC123...` |
| `KAFKA_API_SECRET` | Confluent Cloud API secret | `xyz...` |
| `FLINK_GATEWAY_URL` | Flink SQL Gateway endpoint | `https://flink.region.confluent.cloud` |
| `WEBSOCKET_TOPIC` | WebSocket broadcast destination | `/topic/messages` |

---

## 📝 Real-World Constraints & Notes

- **Same model for ingestion and query**: Always use the identical embedding model for both document embedding and question embedding. Mixing models breaks cosine similarity.
- **Network boundary in Confluent Cloud Flink**: Outbound calls to external services (e.g., Weaviate) from Flink UDFs may be blocked in the managed environment. Use a Java consumer in your own infrastructure as a workaround.
- **Connector vs. custom writer**: Using the Weaviate Sink Connector for the write path eliminates custom consumer code and keeps the vector store in sync automatically.
- **Model choice**: `BAAI/bge-large-en-v1.5` is a strong general-purpose English embedder. `Phi-3.5-mini-instruct` is compact and fast — well-suited for short reformatting/rewriting tasks.

---

## 📚 References

- [Confluent Cloud Flink SQL Docs](https://docs.confluent.io/cloud/current/flink/index.html)
- [Weaviate Documentation](https://weaviate.io/developers/weaviate)
- [BAAI/bge-large-en-v1.5 on HuggingFace](https://huggingface.co/BAAI/bge-large-en-v1.5)
- [Microsoft Phi-3.5-mini-instruct](https://huggingface.co/microsoft/Phi-3.5-mini-instruct)
- [Medium Article — Full Writeup](https://medium.com/@pdaengg94/building-a-real-time-rag-pipeline-with-confluent-flink-kafka-and-weaviate-09f2c3e0de15)
- [Demo Video](https://drive.google.com/file/d/1xwbbFpetOa4NiHvglvuDZMHlXjJw6au2/view?usp=drivesdk)

---

## 👤 Author

**Abhishek** — [Medium](https://medium.com/@pdaengg94) | Problem Solver

---

## Demo vide0

**RealTime RAG in Action** - [Demo video](https://drive.google.com/file/d/1xwbbFpetOa4NiHvglvuDZMHlXjJw6au2/view?usp=drive_link)

> *If you're building something similar on Confluent Cloud, feel free to reach out — happy to share learnings on connector configuration, Weaviate schema design, and the network setup that would allow the UDF to run directly inside Flink SQL.*
