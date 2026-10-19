package com.edusync.edusync.controller;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.edusync.edusync.service.LectureVideoService;

@RestController
@RequestMapping("/api/lecture")
public class LectureVideoController {

    private final LectureVideoService service;

    public LectureVideoController(LectureVideoService service) {
        this.service = service;
    }

    // No topic  -> lecture on the whole uploaded notes.
    // With topic -> focused lecture on that topic.
    // maxSlides (3-20, default 12) limits the content slides for whole-notes mode.
    @PostMapping("/start")
    public Map<String, Object> start(
            @RequestParam(name = "topic", required = false) String topic,
            @RequestParam(name = "maxSlides", required = false) Integer maxSlides) {

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String id = service.start(topic, maxSlides);
            boolean whole = topic == null || topic.isBlank();
            out.put("jobId", id);
            out.put("mode", whole ? "WHOLE_NOTES" : "TOPIC");
            out.put("statusUrl", "/api/lecture/status/" + id);
            out.put("videoUrl", "/api/lecture/video/" + id);
            out.put("note", "Generation runs in the background. Poll statusUrl until state is DONE.");
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    @GetMapping("/status/{id}")
    public Map<String, Object> status(@PathVariable("id") String id) {
        return service.status(id);
    }

    @GetMapping("/video/{id}")
    public ResponseEntity<Resource> video(@PathVariable("id") String id) {
        File f = service.videoFile(id);
        if (f == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"lecture.mp4\"")
                .body(new FileSystemResource(f));
    }
}