package com.edusync.edusync.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

@Service
public class TopicExtractionService {

    private final AIService aiService;

    public TopicExtractionService(AIService aiService) {
        this.aiService = aiService;
    }

    public String extractTopics(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text.replace("\r", "")
                .replaceAll("[ \\t]+", " ")
                .trim();

        String prompt = """
                You are extracting topics from a university syllabus.
                The text may come from OCR and can contain small spelling errors.

                Return plain text only, in exactly this format:
                Unit I
                - Topic
                - Main topic: Subtopic

                Rules:
                1. Write one topic per line, each line starting with "- ".
                2. If subtopics are listed under a main topic, write each one as
                   "- Main topic: Subtopic" and repeat the main topic name on every line.
                3. If items are separated by commas or semicolons, put each item on its own line.
                4. Keep the original wording. Only fix obvious OCR spelling mistakes.
                5. Do not merge different topics and do not repeat a topic.
                6. Ignore course objectives, outcomes, books, marks and hours.
                7. Do not invent topics. Return no introduction and no explanation.

                SYLLABUS:
                """ + cleaned;

        String out = "";
        try {
            out = aiService.askAI(prompt);
        } catch (Exception e) {
            System.out.println("[TOPICS] model extraction failed: " + e.getMessage());
        }

        int bullets = countBullets(out);
        if (bullets < 3) {
            System.out.println("[TOPICS] model output unusable (" + bullets
                    + " bullets). Using plain text fallback.");
            return fallbackTopics(cleaned);
        }

        return out;
    }

    public String extractMaterialTopics(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String prompt = """
                Extract the academic topics actually discussed in this study material.

                Rules:
                1. Include only topics supported by the supplied text.
                2. Preserve specific topic names.
                3. Do not invent syllabus units.
                4. Remove repeated topic names.
                5. Ignore page numbers, headers and footers.
                6. Return concise plain-text bullet points only.

                STUDY MATERIAL:
                """ + text;

        return aiService.askAI(prompt);
    }

    private int countBullets(String s) {
        if (s == null) {
            return 0;
        }
        int count = 0;
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (t.matches("^[-*•]\\s+.+") || t.matches("^\\d+[.)]\\s+.+")) {
                count++;
            }
        }
        return count;
    }

    // Used only when the model output is unusable: split the OCR text into short fragments.
    private String fallbackTopics(String text) {
        List<String> seen = new ArrayList<>();
        StringBuilder sb = new StringBuilder("Unit I\n");

        for (String frag : text.split("[\\n;,•]+")) {
            String t = frag.trim().replaceFirst("^\\d+[.)]\\s*", "").trim();
            if (t.length() < 3 || t.length() > 90) {
                continue;
            }
            if (t.toLowerCase(Locale.ROOT)
                    .matches("^(unit|module|chapter|lecture|hours?|marks?)\\b.*")) {
                continue;
            }
            String key = t.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            sb.append("- ").append(t).append("\n");
        }
        return sb.toString();
    }
}