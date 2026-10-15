package com.edusync.edusync.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ContentService {

    private final PdfTextExtractorService pdfTextExtractorService;
    private final OcrService ocrService;
    private final TextCleaningService textCleaningService;

    private String latestContent = "";

    public ContentService(
            PdfTextExtractorService pdfTextExtractorService,
            OcrService ocrService,
            TextCleaningService textCleaningService) {

        this.pdfTextExtractorService = pdfTextExtractorService;
        this.ocrService = ocrService;
        this.textCleaningService = textCleaningService;
    }

    public String extractContent(MultipartFile file) throws Exception {

        String type = file.getContentType();

        String text;

        if ("application/pdf".equals(type)) {

            text = pdfTextExtractorService.extractText(file);

        } else if (type != null && type.startsWith("image/")) {

            text = ocrService.extractText(file);

        } else {

            throw new RuntimeException(
                    "Only PDF and image files are supported."
            );
        }

        latestContent = textCleaningService.cleanText(text);

        System.out.println("Extracted content length: " + latestContent.length());
        System.out.println("Extracted content preview: " +
            latestContent.substring(0, Math.min(1000, latestContent.length())));

        return latestContent;
    }

    public String getLatestContent() {
        return latestContent;
    }
}