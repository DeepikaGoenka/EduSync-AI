package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

@Service
public class QuizService {

    private final AIService aiService;
    private final ContentService contentService;

    public QuizService(AIService aiService, ContentService contentService) {
        this.aiService = aiService;
        this.contentService = contentService;
    }

    public String generateQuiz(String topic) {

        String content = contentService.getLatestContent();

        if (content == null || content.isBlank()) {
            return "Please upload your study material first.";
        }

        // Keep the prompt manageable for the small local model
        if (content.length() > 8000) {
            content = content.substring(0, 8000);
        }

        String prompt = """
                STUDY MATERIAL:
                %s

                TOPIC:
                %s

                Now generate a quiz.

                You MUST follow these rules:

                - Generate EXACTLY 5 questions.
                - Every question must be multiple choice.
                - Every question must have EXACTLY 4 options.
                - Options must be A, B, C and D.
                - Give the correct answer after every question.
                - Questions must be about the topic given above.
                - Use only information from the study material.
                - DO NOT summarize.
                - DO NOT explain the topic.
                - DO NOT write an introduction.
                - DO NOT write notes.
                - OUTPUT ONLY THE 5 QUESTIONS.

                Use EXACTLY this format:

                Q1. Question?
                A. Option
                B. Option
                C. Option
                D. Option
                Answer: B

                Q2. Question?
                A. Option
                B. Option
                C. Option
                D. Option
                Answer: A

                Q3. Question?
                A. Option
                B. Option
                C. Option
                D. Option
                Answer: C

                Q4. Question?
                A. Option
                B. Option
                C. Option
                D. Option
                Answer: D

                Q5. Question?
                A. Option
                B. Option
                C. Option
                D. Option
                Answer: A

                START NOW. OUTPUT ONLY THE QUIZ.
                """.formatted(content, topic);

        return aiService.askAI(prompt);
    }
}