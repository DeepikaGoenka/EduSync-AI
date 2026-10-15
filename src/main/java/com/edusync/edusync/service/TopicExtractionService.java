package com.edusync.edusync.service;

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

    String prompt = """
            You are extracting topics from a university syllabus.

            Return plain text only.

            Format:
            Unit I
            - Topic name
            - Subtopic name

            Rules:
            1. Extract every examinable topic and subtopic.
            2. Preserve the original wording and order.
            3. Keep each topic on a separate bullet line.
            4. Preserve unit headings.
            5. Do not combine different topics.
            6. Do not repeat a topic.
            7. Do not invent topics or units.
            8. Ignore course objectives and learning outcomes.
            9. If the text is unclear, do not guess.
            10. Return no introduction or explanation.

            SYLLABUS:
            """ + text;

    return aiService.askAI(prompt);
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

}