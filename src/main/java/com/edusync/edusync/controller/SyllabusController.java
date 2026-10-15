package com.edusync.edusync.controller;

import java.io.IOException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.edusync.edusync.service.OcrService;
import com.edusync.edusync.service.PdfTextExtractorService;
import com.edusync.edusync.service.TextCleaningService;
import com.edusync.edusync.service.TopicExtractionService;

import net.sourceforge.tess4j.TesseractException;

@RestController
public class SyllabusController {

    private final OcrService ocrService;
    private final PdfTextExtractorService pdfTextExtractorService;
    private final TextCleaningService textCleaningService;
    private final TopicExtractionService topicExtractionService;

    public SyllabusController(
            OcrService ocrService,
            PdfTextExtractorService pdfTextExtractorService,
            TextCleaningService textCleaningService,
            TopicExtractionService topicExtractionService) {

        this.ocrService = ocrService;
        this.pdfTextExtractorService = pdfTextExtractorService;
        this.textCleaningService = textCleaningService;
        this.topicExtractionService = topicExtractionService;
    }

    @PostMapping("/api/syllabus/analyze")
    public String analyzeSyllabus(
            @RequestParam("syllabus") MultipartFile syllabus) {

        try {

            String syllabusType = syllabus.getContentType();

            String syllabusText;

            if ("application/pdf".equals(syllabusType)) {

                syllabusText =
                        pdfTextExtractorService.extractText(syllabus);

            } else if (syllabusType != null
                    && syllabusType.startsWith("image/")) {

                syllabusText =
                        ocrService.extractText(syllabus);

            } else {

                throw new RuntimeException(
                        "Unsupported syllabus file type.");
            }

            System.out.println("STEP 1: File text extracted");

            syllabusText =
                    textCleaningService.cleanText(syllabusText);

            System.out.println("STEP 2: Text cleaned");
            System.out.println(
                    "Extracted text length: " + syllabusText.length());

            System.out.println("STEP 3: Sending text to AI");

            String result =
                    topicExtractionService.extractTopics(syllabusText);

            System.out.println("STEP 4: AI response received");

            return result;

        } catch (IOException | TesseractException e) {

            throw new RuntimeException(
                    "Unable to process syllabus: "
                            + e.getMessage(),
                    e);
        }
    }
}