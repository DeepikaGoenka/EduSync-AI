
package com.edusync.edusync.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class SyllabusAlignmentService {

    private final TopicExtractionService topicExtractionService;
    private final AIService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SyllabusAlignmentService(
            TopicExtractionService topicExtractionService,
            AIService aiService) {
        this.topicExtractionService = topicExtractionService;
        this.aiService = aiService;
    }

    public String compareSyllabusAndMaterial(
            String syllabusText,
            String materialText) {

        if (syllabusText == null || syllabusText.isBlank()) {
            throw new IllegalArgumentException(
                    "Syllabus content cannot be empty.");
        }

        if (materialText == null || materialText.isBlank()) {
            throw new IllegalArgumentException(
                    "Study material content cannot be empty.");
        }

        String extractedSyllabus =
                topicExtractionService.extractTopics(syllabusText);

        if (extractedSyllabus == null || extractedSyllabus.isBlank()) {
            throw new IllegalStateException(
                    "Could not extract topics from the syllabus.");
        }

        List<String> syllabusTopics =
                parseSyllabusTopics(extractedSyllabus);

        if (syllabusTopics.isEmpty()) {
            throw new IllegalStateException(
                    "No syllabus topics were found. Check the syllabus extraction output.");
        }

        // Give each syllabus topic a stable numeric ID.
        StringBuilder topicList = new StringBuilder();

        for (int i = 0; i < syllabusTopics.size(); i++) {
            topicList.append("ID ")
                    .append(i + 1)
                    .append(": ")
                    .append(syllabusTopics.get(i))
                    .append("\n");
        }

        String prompt = """
                You are EduSync AI, an academic syllabus coverage evaluator.

                TASK:
                Evaluate each numbered syllabus topic against the FULL
                study material supplied below.

                Return valid JSON only in this format:
                {
                  "topics": [
                    {
                      "id": 1,
                      "status": "COVERED",
                      "evidence": "Short evidence from the study material"
                    }
                  ]
                }

                STATUS RULES:
                COVERED:
                The study material clearly discusses the topic with relevant
                explanation, details, examples, or a meaningful section.

                PARTIALLY COVERED:
                The study material mentions or explains only part of the topic,
                or gives related information without enough detail.

                MISSING:
                The topic is not supported by the study material.

                IMPORTANT RULES:
                1. Evaluate every supplied topic ID separately.
                2. Return the original numeric ID exactly.
                3. Never return a topic name instead of its ID.
                4. Never invent an ID.
                5. Use only COVERED, PARTIALLY COVERED, or MISSING.
                6. Do not mark a topic COVERED merely because a similar word appears.
                7. Judge meaning, not exact wording alone.
                8. Evidence must be based on the supplied study material.
                9. Do not claim that content exists if it is not present.
                10. Return one result per topic whenever possible.
                11. Do not calculate totals or percentages.
                12. Do not include Markdown fences or additional text.

                SYLLABUS TOPICS:
                """ + topicList + """

                FULL STUDY MATERIAL:
                """ + materialText;

        String aiResponse = aiService.askAIForJson(prompt);

        try {
            JsonNode root = objectMapper.readTree(cleanJson(aiResponse));
            JsonNode aiTopics = root.path("topics");

            Map<Integer, JsonNode> resultsById = new HashMap<>();

            if (aiTopics.isArray()) {
                for (JsonNode item : aiTopics) {
                    JsonNode idNode = item.path("id");

                    if (!idNode.canConvertToInt()) {
                        continue;
                    }

                    int id = idNode.asInt();

                    if (id >= 1 && id <= syllabusTopics.size()) {
                        resultsById.putIfAbsent(id, item);
                    }
                }
            }

            ObjectNode output = objectMapper.createObjectNode();
            ArrayNode finalTopics = objectMapper.createArrayNode();

            int covered = 0;
            int partiallyCovered = 0;
            int missing = 0;

            for (int i = 0; i < syllabusTopics.size(); i++) {
                int id = i + 1;
                String syllabusTopic = syllabusTopics.get(i);

                JsonNode aiItem = resultsById.get(id);

                String status = "MISSING";
                String evidence =
                        "The AI did not return a valid classification for this topic.";

                if (aiItem != null) {
                    String candidateStatus = aiItem.path("status")
                            .asText("")
                            .trim()
                            .toUpperCase(Locale.ROOT);

                    if (candidateStatus.equals("COVERED")
                            || candidateStatus.equals("PARTIALLY COVERED")
                            || candidateStatus.equals("MISSING")) {
                        status = candidateStatus;

                        String candidateEvidence = aiItem.path("evidence")
                                .asText("")
                                .trim();

                        if (!candidateEvidence.isBlank()) {
                            evidence = candidateEvidence;
                        }
                    }
                }

                if (status.equals("COVERED")) {
                    covered++;
                } else if (status.equals("PARTIALLY COVERED")) {
                    partiallyCovered++;
                } else {
                    missing++;
                }

                ObjectNode topicResult = objectMapper.createObjectNode();
                topicResult.put("topic", syllabusTopic);
                topicResult.put("status", status);
                topicResult.put("evidence", evidence);

                finalTopics.add(topicResult);
            }

            int totalTopics = finalTopics.size();

            double coveragePercentage = totalTopics == 0
                    ? 0.0
                    : Math.round(
                            (covered * 10000.0) / totalTopics) / 100.0;

            output.put("totalTopics", totalTopics);
            output.put("covered", covered);
            output.put("partiallyCovered", partiallyCovered);
            output.put("missing", missing);
            output.put("coveragePercentage", coveragePercentage);
            output.set("topics", finalTopics);

            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(output);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Could not validate the AI alignment response: "
                            + e.getMessage()
                            + ". Raw AI response: " + aiResponse,
                    e);
        }
    }

    private List<String> parseSyllabusTopics(String extractedSyllabus) {
        List<String> topics = new ArrayList<>();

        for (String rawLine : extractedSyllabus.split("\\R")) {
            String line = rawLine.trim();

            if (line.isBlank()) {
                continue;
            }

            // Skip unit headings.
            if (line.matches(
                    "(?i)^unit\\s+([ivxlcdm]+|\\d+)\\s*:?$")) {
                continue;
            }

            // Accept bullet points and numbered lists.
            if (line.matches("^[-*•]\\s+.+")) {
                line = line.replaceFirst("^[-*•]\\s+", "").trim();
            } else if (line.matches("^\\d+[.)]\\s+.+")) {
                line = line.replaceFirst("^\\d+[.)]\\s+", "").trim();
            } else {
                continue;
            }

            line = line.replaceAll("\\s+", " ").trim();

            if (line.isBlank()) {
                continue;
            }

            String normalized = normalize(line);

            boolean duplicate = topics.stream()
                    .anyMatch(existing ->
                            normalize(existing).equals(normalized));

            if (!duplicate) {
                topics.add(line);
            }
        }

        return topics;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

private String cleanJson(String response) {
    if (response == null || response.isBlank()) {
        throw new IllegalStateException(
                "AI returned an empty response.");
    }

    String cleaned = response.trim();

    // Remove Markdown JSON fences if present.
    cleaned = cleaned.replaceFirst(
            "(?is)^\\s*```(?:json)?\\s*", "");
    cleaned = cleaned.replaceFirst(
            "(?s)\\s*```\\s*$", "");

    // Extract a JSON object if the AI added introductory text.
    int start = cleaned.indexOf('{');
    int end = cleaned.lastIndexOf('}');

    if (start >= 0 && end > start) {
        cleaned = cleaned.substring(start, end + 1);
    }

    if (!cleaned.startsWith("{") || !cleaned.endsWith("}")) {
        throw new IllegalStateException(
                "AI did not return JSON. Response begins: "
                        + cleaned.substring(0, Math.min(250, cleaned.length())));
    }

    return cleaned;
}
}
