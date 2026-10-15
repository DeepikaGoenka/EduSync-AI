package com.edusync.edusync;

import com.edusync.edusync.service.AIService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    private final AIService aiService;

    public HelloController(AIService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/api/hello")
    public String hello() {
        return "Welcome to EduSync AI!";
    }

    @GetMapping("/api/ai/test")
    public String testAI() {
        return aiService.askAI(
                "Explain Data Warehousing in 3 simple points for an MCA student."
        );
    }
}