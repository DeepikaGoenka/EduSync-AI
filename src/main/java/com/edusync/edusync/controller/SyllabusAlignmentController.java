
package com.edusync.edusync.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.edusync.edusync.service.ContentService;
import com.edusync.edusync.service.SyllabusAlignmentService;

@RestController
@RequestMapping("/api/alignment")
public class SyllabusAlignmentController {

    private final ContentService contentService;
    private final SyllabusAlignmentService alignmentService;

    public SyllabusAlignmentController(
            ContentService contentService,
            SyllabusAlignmentService alignmentService) {
        this.contentService = contentService;
        this.alignmentService = alignmentService;
    }

    @PostMapping(
        value = "/compare",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public String compare(
            @RequestParam("syllabus") MultipartFile syllabus,
            @RequestParam("material") MultipartFile material)
            throws Exception {

        String syllabusText =
                contentService.extractContent(syllabus);

        String materialText =
                contentService.extractContent(material);

        System.out.println("SYLLABUS EXTRACTED TEXT:\n" + syllabusText);
System.out.println("MATERIAL EXTRACTED LENGTH: " + materialText.length());

        return alignmentService.compareWithRetrieval(
                syllabusText, materialText);
    }
}
