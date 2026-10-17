package com.edusync.edusync.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkService {

    public List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        String clean = text.replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + size, clean.length());
            if (end < clean.length()) {
                int p = clean.lastIndexOf(". ", end);   // cut at a sentence end
                if (p > start + size / 2) end = p + 1;
            }
            chunks.add(clean.substring(start, end).trim());
            if (end >= clean.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}