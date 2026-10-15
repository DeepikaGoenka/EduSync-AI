package com.edusync.edusync.controller;

import com.edusync.edusync.service.VideoService;
import com.edusync.edusync.service.TextToSpeechService;
import com.edusync.edusync.service.FFmpegService;
import com.edusync.edusync.service.LectureContentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private final VideoService videoService;
    private final TextToSpeechService textToSpeechService;
    private final FFmpegService ffmpegService;
    private final LectureContentService lectureContentService;

    public VideoController(
            VideoService videoService,
            TextToSpeechService textToSpeechService,
            FFmpegService ffmpegService,
            LectureContentService lectureContentService) {

        this.videoService = videoService;
        this.textToSpeechService = textToSpeechService;
        this.ffmpegService = ffmpegService;
        this.lectureContentService = lectureContentService;
    }

    @PostMapping("/generate")
    public String generateVideoLecture(
            @RequestParam("topic") String topic) {

        if (topic == null || topic.isBlank()) {
            return "Please provide a topic.";
        }

        return videoService.generateLectureScript(topic);
    }

    @PostMapping("/slides")
    public String generateSlides(
            @RequestParam("topic") String topic) {

        if (topic == null || topic.isBlank()) {
            return "Please provide a topic.";
        }

        return videoService.generateSlides(topic);
    }

    @PostMapping("/generate-images")
    public String generateSlideImages(
            @RequestParam("topic") String topic) {

        if (topic == null || topic.isBlank()) {
            return "Please provide a topic.";
        }

        return videoService.generateSlideImages(topic);
    }

    @GetMapping("/test-tts")
    public String testTTS(
            @RequestParam("text") String text) {

        if (text == null || text.isBlank()) {
            return "Please provide text.";
        }

        return textToSpeechService.generateNarration(
                text,
                1);
    }

    @PostMapping("/generate-narrations")
    public String generateNarrations(
            @RequestParam("topic") String topic) {

        if (topic == null || topic.isBlank()) {
            return "Please provide a topic.";
        }

        return videoService.generateAllNarrations(topic);
    }

    @PostMapping("/create-video")
    public String createVideo() {

        int slideCount = videoService.getGeneratedSlideCount();

        if (slideCount <= 0) {
            return "Generate slide images first.";
        }

        return ffmpegService.createVideo(slideCount);
    }

    @PostMapping("/extract-topics")
    public String extractLectureTopics() {

        return lectureContentService.extractLectureTopics();
    }
    @PostMapping("/lecture-structure")
public String generateLectureStructure() {

    return lectureContentService.generateLectureStructure();
}

@PostMapping("/complete-slides")
public String generateCompleteSlides(
        @RequestParam(defaultValue = "MATERIAL_ALIGNED") String mode) {

    return videoService.generateCompleteLectureSlides(mode);
}

@PostMapping("/complete-images")
public String generateCompleteSlideImages() {

    return videoService.generateSlideImages("Complete Lecture");
}

@PostMapping("/complete-narrations")
public String generateCompleteNarrations() {

    return videoService.generateAllNarrations("Complete Lecture");
}
@PostMapping("/generate-full-lecture")
public String generateFullLecture(
        @RequestParam(defaultValue = "MATERIAL_ALIGNED") String mode) {

    try {
        // Step 1: Generate slides from the complete uploaded material
        videoService.generateCompleteLectureSlides(mode);

        // Step 2: Generate images for all generated slides
        String images = videoService.generateSlideImages("Complete Lecture");

        // Step 3: Generate narration for all generated slides
        String narrations =
                videoService.generateAllNarrations("Complete Lecture");

        // Step 4: Check that slide images were generated
        int slideCount = videoService.getGeneratedSlideCount();

        if (slideCount <= 0) {
            return "No slide images found. Check the generation logs.";
        }

        // Step 5: Combine slides and narration into an MP4
        String video = ffmpegService.createVideo(slideCount);

        return "FULL LECTURE GENERATION RESULTS\n\n"
                + "Slide images:\n" + images + "\n\n"
                + "Narrations:\n" + narrations + "\n\n"
                + "Video:\n" + video;

    } catch (Exception e) {
        return "Lecture generation stopped: " + e.getMessage();
    }
}
}