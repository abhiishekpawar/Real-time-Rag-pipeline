package com.rag.demo.udf;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

@Component
public class WeaviateCallUDF {

    private static final String WEAVIATE_URL =
            "https://rw2h0fhdq7ymy8izti3oa.c0.asia-southeast1.gcp.weaviate.cloud/v1/graphql";

    public String eval(double[] vector) {

        System.out.println("Making API Call");
        System.out.println("==============================================================");


        try {

            // Convert vector to JSON array
            String vectorJson = Arrays.toString(vector);

            String graphqlQuery =
                    "{ \"query\": \"{ Get { Incident_knowledge_base_embeddings("
                            + "nearVector:{vector:"
                            + vectorJson
                            + "}, limit:1) {"
                            + "incident_id incident_title incident_fix "
                            + "_additional { distance } }}}\" }";

            URL url = new URL(WEAVIATE_URL);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty(
                    "Authorization",
                    "Bearer OWxpUFU4RFYvUTIrNDlPal9JL1dMdkRTNjMwdUlvbytTYW1Ud3JEOUFPZXRZWEdIeitIak1taEk5d2JFPV92MjAw");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", "flink-udf-client");

            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);

            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(graphqlQuery.getBytes());
                os.flush();
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

            StringBuilder response = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            br.close();

            return response.toString();

        } catch (Exception e) {
            return "ERROR:" + e.getMessage();
        }
    }
}
