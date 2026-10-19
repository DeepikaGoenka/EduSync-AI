package com.edusync.edusync.service;

import java.util.List;

public record LectureSlideSpec(
        String title,
        List<String> bullets,
        String narration,
        List<String> flow,
        String takeaway) {
}