package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

@Service
public class TextCleaningService {

    public String cleanText(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        // Replace multiple spaces with one space
        text = text.replaceAll("[ \t]+", " ");

        // Remove spaces at the beginning and end of lines
        text = text.replaceAll("(?m)^\\s+|\\s+$", "");

        // Replace more than two blank lines with one blank line
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.trim();
    }
}