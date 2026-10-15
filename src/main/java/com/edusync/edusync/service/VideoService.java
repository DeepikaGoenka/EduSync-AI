package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VideoService {

        private final AIService aiService;
        private final ContentService contentService;
        private final SlideImageService slideImageService;
        private final TextToSpeechService textToSpeechService;
        private final LectureContentService lectureContentService;
        private int generatedSlideCount = 0;

        // Stores the exact slide structure used for the current video
        private String savedSlideContent = "";

        public VideoService(
                        AIService aiService,
                        ContentService contentService,
                        SlideImageService slideImageService,
                        TextToSpeechService textToSpeechService,
                        LectureContentService lectureContentService) {

                this.aiService = aiService;
                this.contentService = contentService;
                this.slideImageService = slideImageService;
                this.textToSpeechService = textToSpeechService;
                this.lectureContentService = lectureContentService;
        }

        // Generate slides from the COMPLETE uploaded document.
        // Modes: GENERAL and MATERIAL_ALIGNED
public String generateCompleteLectureSlides(String mode) {

    String selectedMode = mode == null
            ? "MATERIAL_ALIGNED"
            : mode.trim().toUpperCase();

    if (!selectedMode.equals("GENERAL")
            && !selectedMode.equals("MATERIAL_ALIGNED")) {
        return "Mode must be GENERAL or MATERIAL_ALIGNED.";
    }

    String structure = lectureContentService.generateLectureStructure();

    List<String> sections = extractSections(structure);

    if (sections.isEmpty()) {
        return "No usable document sections found.";
    }

    // Keep related sections together without creating extremely large prompts.
    List<String> batches = createBatches(sections, 7000);

    StringBuilder completeDeck = new StringBuilder();
    int nextSlideNumber = 1;

    String modeInstructions;

    if (selectedMode.equals("MATERIAL_ALIGNED")) {
        modeInstructions = """
                Use only the supplied uploaded study material.
                Preserve its academic terminology and important details.
                Do not silently change source facts or formulas.
                If an apparent error exists, flag it briefly.
                Do not invent missing information.
                """;
    } else {
        modeInstructions = """
                Use the supplied material to define lecture topics.
                Explain concepts using general academic knowledge where useful.
                Verify calculations, formulas, definitions and examples.
                Correct clear factual errors.
                Do not claim to have researched external websites.
                """;
    }

    for (int i = 0; i < batches.size(); i++) {

        String prompt = """
                You are EduSync AI, generating an MCA university lecture.

                MODE: %s

                MODE RULES:
                %s

                DOCUMENT BATCH: %d of %d

                SOURCE CONTENT:
                %s

                CONTENT COMPLETENESS RULES:
                1. Read the entire source content before generating slides.
                2. Treat every distinct heading and important subsection
                   in this batch as required lecture content.
                3. Do not silently omit a heading or its explanation.
                4. Preserve procedures, numbered steps, algorithms,
                   formulas, examples, comparisons, advantages
                   and limitations when present in the source.
                5. If a topic contains steps, include the actual steps
                   and their explanations. Never show a heading such
                   as "Steps" with empty content.
                6. For PCA, when the source provides them, preserve
                   standardization, covariance calculation,
                   eigenvalues and eigenvectors, component ranking,
                   and data transformation.
                7. Combine related short subsections when appropriate,
                   but do not remove unique concepts.
                8. Do not repeat content already covered in this batch.
                9. Ignore PDF page numbers, headers, footers,
                   course labels and extraction artifacts.
                10. Keep all formulas and numerical examples faithful.
                11. If content cannot fit clearly, create another slide
                    rather than silently dropping it.
                12. Generate approximately 2 to 4 slides for this batch,
                    depending on how much content must be preserved.
                13. Use concise bullets, with no more than 5 bullets
                    per slide whenever possible.
                14. Do not invent content absent from the source
                    when MATERIAL_ALIGNED mode is selected.

                EXACT OUTPUT FORMAT:

                SLIDE 1
                TITLE: Clear topic title
                POINTS:
                - Definition or key concept
                - Explanation
                - Steps, formula or example when applicable

                SLIDE 2
                TITLE: Clear topic title
                POINTS:
                - Important concept
                - Explanation or example

                Repeat the same format for additional slides if required.
                Number slides sequentially within this batch, starting
                from SLIDE 1.

                Return only slides.
                Do not return an introduction, a summary outside the
                slides, or Markdown code fences.
                """.formatted(
                selectedMode,
                modeInstructions,
                i + 1,
                batches.size(),
                batches.get(i)
        );

        System.out.println(
                "GENERATING LECTURE BATCH "
                        + (i + 1) + "/" + batches.size()
        );

        String generated = aiService.askAIForLectureBatch(prompt);

        if (generated == null || generated.isBlank()) {
            throw new RuntimeException(
                    "Empty AI response for lecture batch " + (i + 1)
            );
        }

        int previousSlideNumber = nextSlideNumber;

        nextSlideNumber = appendGeneratedSlides(
                completeDeck,
                generated,
                nextSlideNumber
        );

        if (nextSlideNumber == previousSlideNumber) {
            throw new RuntimeException(
                    "No valid slides were parsed from batch " + (i + 1)
            );
        }
    }

    if (completeDeck.length() == 0) {
        return "No valid lecture slides were generated.";
    }

    savedSlideContent = completeDeck.toString();

    System.out.println("===== COMPLETE LECTURE SLIDES =====");
    System.out.println(savedSlideContent);
    System.out.println("TOTAL SLIDES: " + (nextSlideNumber - 1));

    return savedSlideContent;
}

        private List<String> extractSections(String structure) {

                List<String> sections = new ArrayList<>();

                String[] blocks = structure.split(
                                "(?m)(?=^SECTION\\s+\\d+\\s*$)");

                for (String block : blocks) {

                        int headingStart = block.indexOf("HEADING:");
                        int contentStart = block.indexOf("CONTENT:");

                        if (headingStart < 0 || contentStart < 0) {
                                continue;
                        }

                        int headingEnd = block.indexOf("\n", headingStart);

                        if (headingEnd < 0 || headingEnd > contentStart) {
                                continue;
                        }

                        String heading = block.substring(
                                        headingStart + "HEADING:".length(),
                                        headingEnd).trim();

                        String content = block.substring(
                                        contentStart + "CONTENT:".length()).trim();

                        if (!heading.isBlank() && !content.isBlank()) {
                                sections.add(
                                                "HEADING: " + heading
                                                                + "\nCONTENT: " + content);
                        }
                }

                return sections;
        }

        private List<String> createBatches(
                        List<String> sections,
                        int maxCharacters) {

                List<String> batches = new ArrayList<>();
                StringBuilder current = new StringBuilder();

                for (String section : sections) {

                        // Keep every section intact, even if one section
                        // is slightly larger than the preferred batch size.
                        if (current.length() > 0
                                        && current.length() + section.length() > maxCharacters) {

                                batches.add(current.toString());
                                current.setLength(0);
                        }

                        if (current.length() > 0) {
                                current.append("\n\n");
                        }

                        current.append(section);
                }

                if (current.length() > 0) {
                        batches.add(current.toString());
                }

                return batches;
        }

        private int appendGeneratedSlides(
                        StringBuilder destination,
                        String generated,
                        int nextSlideNumber) {

                if (generated == null || generated.isBlank()) {
                        return nextSlideNumber;
                }

                String cleaned = generated
                                .replaceAll("(?m)^\\s*```(?:\\w+)?\\s*$", "")
                                .replace("**", "")
                                .replaceAll("(?m)^\\s*#+\\s*", "")
                                .trim();

                String[] blocks = cleaned.split(
                                "(?i)(?=SLIDE\\s*\\d+)");

                for (String rawBlock : blocks) {

                        String block = rawBlock.trim();

                        if (block.isBlank()) {
                                continue;
                        }

                        if (block.toUpperCase().indexOf("TITLE:") < 0
                                        || block.toUpperCase().indexOf("POINTS:") < 0) {
                                continue;
                        }

                        block = block.replaceFirst(
                                        "(?i)SLIDE\\s*\\d+",
                                        "SLIDE " + nextSlideNumber);

                        destination.append(block)
                                        .append("\n\n");

                        nextSlideNumber++;
                }

                return nextSlideNumber;
        }

        public int getGeneratedSlideCount() {
                return generatedSlideCount;
        }

        // =========================================================
        // STEP 1 - GENERATE LECTURE SCRIPT
        // =========================================================

        public String generateLectureScript(String topic) {

                String content = contentService.getLatestContent();

                if (content == null || content.isBlank()) {
                        return "Please upload your study material first.";
                }

                String prompt = """
                                You are EduSync AI, a university lecture generator.

                                Create a clear lecture script for the topic below.

                                Use only the uploaded study material.

                                TOPIC:
                                %s

                                STUDY MATERIAL:
                                %s
                                """.formatted(topic, content);

                return aiService.askAI(prompt);
        }

        // =========================================================
        // STEP 2 - GENERATE SLIDES ONLY ONCE
        // =========================================================

        public String generateSlides(String topic) {

                String content = contentService.getLatestContent();

                if (content == null || content.isBlank()) {
                        return "Please upload your study material first.";
                }

                String prompt = """
                                You are an expert university lecture slide generator.

                                Create a complete lecture presentation for the given topic.

                                Use ONLY the uploaded study material.

                                Create enough slides to cover ALL major concepts
                                and important information in the uploaded study material.

                                Do NOT limit the presentation to 5 to 8 slides.
                                Do NOT omit important topics just to reduce the number of slides.

                                Every slide must follow EXACTLY this format:

                                SLIDE 1
                                TITLE: Slide title
                                POINTS:
                                - Point 1
                                - Point 2
                                - Point 3

                                SLIDE 2
                                TITLE: Slide title
                                POINTS:
                                - Point 1
                                - Point 2
                                - Point 3

                                Continue until the lecture is complete.

                                IMPORTANT:
                                - Do not add explanations outside the slide format.
                                - Do not use markdown headings.
                                - Do not number points manually.
                                - Do not create empty slides.
                                - Keep points concise.
                                - Cover the important concepts from the material.
                                - End with a final summary slide.

                                TOPIC:
                                %s

                                STUDY MATERIAL:
                                %s
                                """.formatted(topic, content);

                String result = aiService.askAI(prompt);

                // SAVE THE EXACT AI RESULT
                savedSlideContent = result;

                System.out.println("===== SLIDES GENERATED =====");
                System.out.println(savedSlideContent);
                System.out.println("============================");

                return savedSlideContent;
        }

        // =========================================================
        // GET SAVED SLIDES
        // =========================================================

        private String getSavedSlides(String topic) {

                // If slides have not been generated yet,
                // generate them once.
                if (savedSlideContent == null
                                || savedSlideContent.isBlank()) {

                        return generateSlides(topic);
                }

                return savedSlideContent;
        }

        // =========================================================
        // STEP 3 - CREATE PNG SLIDES
        // =========================================================

        public String generateSlideImages(String topic) {
                generatedSlideCount = 0;
                // IMPORTANT:
                // This DOES NOT call AI again if slides already exist.
                String slideContent = getSavedSlides(topic);

                if (slideContent == null || slideContent.isBlank()) {
                        return "Unable to generate slides.";
                }

                String[] slides = slideContent.split("(?i)(?=SLIDE\\s*\\d+)");

                System.out.println(
                                "TOTAL SLIDE BLOCKS: " + slides.length);

                StringBuilder result = new StringBuilder();

                int slideNumber = 1;

                for (String slide : slides) {

                        slide = slide.trim();

                        if (slide.isBlank()) {
                                continue;
                        }

                        int titleStart = slide.toUpperCase().indexOf("TITLE:");

                        int pointsStart = slide.toUpperCase().indexOf("POINTS:");

                        if (titleStart == -1 ||
                                        pointsStart == -1) {

                                continue;
                        }

                        String title = slide.substring(
                                        titleStart + 6,
                                        pointsStart).trim();

                        String pointsText = slide.substring(
                                        pointsStart + 7);

                        int summaryIndex = pointsText.toUpperCase()
                                        .indexOf("SUMMARY");

                        if (summaryIndex != -1) {

                                pointsText = pointsText.substring(
                                                0,
                                                summaryIndex);
                        }

                        String[] lines = pointsText.split("\\r?\\n");

                        List<String> pointList = new ArrayList<>();

                        for (String line : lines) {

                                line = line.trim();

                                if (line.startsWith("-")) {

                                        String point = line.substring(1).trim();

                                        if (!point.isBlank()) {
                                                pointList.add(point);
                                        }
                                }
                        }

                        if (pointList.isEmpty()) {
                                continue;
                        }

                        String imagePath = slideImageService.createSlideImage(
                                        title,
                                        pointList.toArray(
                                                        new String[0]),
                                        slideNumber);

                        result.append(
                                        "Slide "
                                                        + slideNumber
                                                        + ": "
                                                        + imagePath
                                                        + "\n");

                        slideNumber++;
                }

                if (slideNumber == 1) {
                        return "No slides could be generated.";
                }
                generatedSlideCount = slideNumber - 1;
                return result.toString();
        }

        // =========================================================
        // STEP 4 - CREATE NARRATIONS
        // =========================================================

        
public String generateAllNarrations(String topic) {

    String slideContent = getSavedSlides(topic);

    if (slideContent == null || slideContent.isBlank()) {
        return "Unable to generate slide content.";
    }

    String[] slides = slideContent.split("(?i)(?=SLIDE\\s*\\d+)");
    StringBuilder result = new StringBuilder();
    int slideNumber = 1;

    for (String slide : slides) {
        slide = slide.trim();

        if (slide.isBlank()) {
            continue;
        }

        int titleStart = slide.toUpperCase().indexOf("TITLE:");
        int pointsStart = slide.toUpperCase().indexOf("POINTS:");

        if (titleStart == -1 || pointsStart == -1) {
            continue;
        }

        String title = slide.substring(titleStart + 6, pointsStart).trim();
        String pointsText = slide.substring(pointsStart + 7);

        String[] lines = pointsText.split("\\r?\\n");
        StringBuilder narration = new StringBuilder(title).append(". ");

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("-")) {
                String point = line.substring(1).trim();

                if (!point.isBlank()) {
                    narration.append(point).append(". ");
                }
            }
        }

        String narrationText = narration.toString().trim();

        if (narrationText.length() <= title.length() + 2) {
            continue;
        }

        // Limit narration to keep each slide reasonably concise.
        int maxChars = 550;
        if (narrationText.length() > maxChars) {
            int cut = narrationText.lastIndexOf(". ", maxChars);
            if (cut > title.length()) {
                narrationText = narrationText.substring(0, cut + 1);
            } else {
                narrationText = narrationText.substring(0, maxChars);
            }
        }

        String audioPath = textToSpeechService.generateNarration(
                narrationText,
                slideNumber);

        result.append("Slide ")
              .append(slideNumber)
              .append(": ")
              .append(audioPath)
              .append("\n");

        System.out.println("NARRATION CREATED: Slide " + slideNumber);
        slideNumber++;
    }

    return result.toString();
}

}