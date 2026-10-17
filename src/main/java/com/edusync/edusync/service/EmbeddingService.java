package com.edusync.edusync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String EMBED_MODEL = "nomic-embed-text";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public double[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    public List<double[]> embedAll(List<String> texts) {
        List<double[]> out = new ArrayList<>();
        try {
            for (int i = 0; i < texts.size(); i += 16) {          // batches of 16
                List<String> batch = texts.subList(i, Math.min(i + 16, texts.size()));
                ObjectNode body = mapper.createObjectNode();
                body.put("model", EMBED_MODEL);
                body.putArray("input").addAll(
                        batch.stream().map(mapper.getNodeFactory()::textNode).toList());

                HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_URL + "/api/embed"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    throw new RuntimeException("Embed error " + res.statusCode() + ": " + res.body());
                }
                for (JsonNode vec : mapper.readTree(res.body()).path("embeddings")) {
                    double[] v = new double[vec.size()];
                    for (int j = 0; j < v.length; j++) v[j] = vec.get(j).asDouble();
                    out.add(v);
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Embedding failed", e);
        }
    }

    public static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}