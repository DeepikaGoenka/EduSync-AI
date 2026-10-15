package com.edusync.edusync.controller;

import com.edusync.edusync.service.QuizService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    @PostMapping("/generate")
    public String generateQuiz(
            @RequestParam("topic") String topic) {

        return quizService.generateQuiz(topic);
    }
}