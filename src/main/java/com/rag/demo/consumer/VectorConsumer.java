package com.rag.demo.consumer;

import com.rag.demo.udf.WeaviateCallUDF;
import com.rag.vector.VectorEmbedding;
import io.confluent.flink.plugin.ConfluentSettings;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class VectorConsumer {

    EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");

    TableEnvironment env = TableEnvironment.create(settings);

    private final WeaviateCallUDF weaviateCallUDF;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public VectorConsumer(WeaviateCallUDF weaviateCallUDF) {
        this.weaviateCallUDF = weaviateCallUDF;
    }

    @KafkaListener(topics = "user_questions_embedding", groupId = "vector-consumer-group-v2")
    public void consume(VectorEmbedding message) throws JsonProcessingException {

        env.useCatalog("default");
        env.useDatabase("poc-cluster");

        System.out.println("===================================================================");
        System.out.println("Consumed message: " + message.getVector());
        System.out.println("===================================================================");

        double[] vector = message.getVector()
                .stream()
                .mapToDouble(Float::doubleValue)
                .toArray();

        // Call Weaviate
        String eval = weaviateCallUDF.eval(vector);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(eval);

        String incidentFix =
                root.path("data")
                        .path("Get")
                        .path("Incident_knowledge_base_embeddings")
                        .get(0)
                        .path("incident_fix")
                        .asText();

        System.out.println("Retrieved Incident Title: " + incidentFix);

        incidentFix = incidentFix.replace("'", "''");

        String prompt =
                "Rewrite the following sentence in a clearer and professional way: " + incidentFix;

        String sql =
                "SELECT response " +
                        "FROM (VALUES ('" + prompt + "')) AS t(prompt), " +
                        "LATERAL TABLE(AI_COMPLETE('text_generation_llm_model', prompt))";

        TableResult tableResult = env.executeSql(sql);

        tableResult.collect().forEachRemaining(row -> {

            String response = row.getField("response").toString();

            System.out.println("LLM Response:");
            System.out.println(response);

            // PUSH RESPONSE TO FRONTEND VIA WEBSOCKET
            messagingTemplate.convertAndSend("/topic/messages", response);

            System.out.println("Sending to WebSocket: " + response);

        });
    }
}