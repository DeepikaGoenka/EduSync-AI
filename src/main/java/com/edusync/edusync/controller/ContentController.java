package com.edusync.edusync.controller;

import com.edusync.edusync.service.ContentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping("/upload")
    public String uploadContent(
            @RequestParam("file") MultipartFile file) {

        try {

            if (file.isEmpty()) {
                return "Please select a file.";
            }

            String text = contentService.extractContent(file);

            return text;

        } catch (Exception e) {

            return "Upload failed: " + e.getMessage();
        }
    }
}