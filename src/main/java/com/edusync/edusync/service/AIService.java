package com.edusync.edusync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.HashMap;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AIService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
        private static final String MODEL = "qwen2.5:3b";
    private static final int NUM_CTX = 4096;

    public AIService() {

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        // Stop waiting indefinitely for Ollama.
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofMinutes(10));

        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:11434")
                .requestFactory(requestFactory)
                .build();

        this.objectMapper = new ObjectMapper();
    }

    // Used by chatbot, quiz, and other existing features.
    public String askAI(String prompt) {

        return callOllama(prompt, Map.of());
    }

    // Use this method specifically for lecture batches.
    public String askAIForLectureBatch(String prompt) {

                 Map<String, Object> options = Map.of(
                "num_predict", 700,
                "temperature", 0.2,
                "num_ctx", NUM_CTX
        );

        return callOllama(prompt, options);
    }

    
// Used only when a feature requires a valid JSON response.
public String askAIForJson(String prompt) {

    if (prompt == null || prompt.isBlank()) {
        throw new IllegalArgumentException(
                "AI prompt cannot be empty.");
    }

    try {
                Map<String, Object> options = Map.of(
                "temperature", 0.1,
                "num_predict", 1000,
                "num_ctx", NUM_CTX
        );

        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "model", MODEL,
                        "prompt", prompt,
                        "stream", false,
                        "format", "json",
                        "options", options,
                        "keep_alive", "10m"
                )
        );

        byte[] responseBytes = restClient.post()
                .uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(byte[].class);

        if (responseBytes == null || responseBytes.length == 0) {
            throw new IllegalStateException(
                    "Ollama returned an empty response.");
        }

        JsonNode responseJson = objectMapper.readTree(responseBytes);
        String generatedText = responseJson.path("response").asText("");

        if (generatedText.isBlank()) {
            throw new IllegalStateException(
                    "Ollama returned no JSON response.");
        }

        // Verify the generated text is valid JSON before returning it.
        objectMapper.readTree(generatedText);

        return generatedText;

    } catch (Exception e) {
        throw new RuntimeException(
                "Ollama JSON generation failed: " + e.getMessage(), e);
    }
}
    // Short JSON answer for yes/no style judgements. Much faster than askAIForJson.
    public String askAIForJudge(String prompt) {
        try {
            Map<String, Object> options = Map.of(
                    "temperature", 0.0,
                    "num_predict", 80,
                    "num_ctx", 2048
            );

            String requestBody = objectMapper.writeValueAsString(
                    Map.of(
                            "model", MODEL,
                            "prompt", prompt,
                            "stream", false,
                            "format", "json",
                            "options", options,
                            "keep_alive", "30m"
                    )
            );

            byte[] bytes = restClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);

            return objectMapper.readTree(bytes).path("response").asText("");
        } catch (Exception e) {
            throw new RuntimeException("Judge call failed: " + e.getMessage(), e);
        }
    }


    private String callOllama(
            String prompt,
            Map<String, Object> options) {

        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException(
                    "AI prompt cannot be empty."
            );
        }
        Map<String, Object> merged = new HashMap<>(options);
        merged.putIfAbsent("num_ctx", NUM_CTX);
        merged.putIfAbsent("temperature", 0.3);
        try {

            String requestBody = objectMapper.writeValueAsString(
                    new OllamaRequest(
                            MODEL,
                            prompt,
                            false,
                            merged,
                            "10m"
                    )
            );

            System.out.println("AI REQUEST STARTED");

            byte[] responseBytes = restClient.post()
        .uri("/api/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(requestBody)
        .retrieve()
        .body(byte[].class);

if (responseBytes == null || responseBytes.length == 0) {
    throw new IllegalStateException(
            "Ollama returned an empty response.");
}

String response = new String(
        responseBytes,
        java.nio.charset.StandardCharsets.UTF_8);

            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "Ollama returned an empty response."
                );
            }

            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode result = jsonNode.path("response");

            if (result.isMissingNode()
                    || result.isNull()
                    || result.asText().isBlank()) {

                String error = jsonNode.path("error")
                        .asText("Ollama returned no generated text.");

                throw new IllegalStateException(error);
            }

            System.out.println("AI RESPONSE RECEIVED");

            return result.asText();

        } catch (Exception e) {

            e.printStackTrace();

            String reason = e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getSimpleName();

            throw new RuntimeException(
                    "Ollama request failed: " + reason,
                    e
            );
        }
    }

    private record OllamaRequest(
            String model,
            String prompt,
            boolean stream,
            Map<String, Object> options,
            String keep_alive
    ) {
    }
}