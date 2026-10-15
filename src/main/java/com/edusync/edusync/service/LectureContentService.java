
package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class LectureContentService {

    private final ContentService contentService;

    private static final Pattern NUMBERED_HEADING =
            Pattern.compile(
                    "^\\d+(?:\\.\\d+)*[.)]?\\s+[A-Za-z].{0,110}$"
            );

    private static final Pattern UNIT_HEADING =
            Pattern.compile(
                    "(?i)^(chapter|unit|module|section)\\s+\\S+.*$"
            );

    public LectureContentService(
            ContentService contentService) {
        this.contentService = contentService;
    }

    // Keeps the existing controller endpoint working.
    public String extractLectureTopics() {
        return generateLectureStructure();
    }

    // Detect document headings and retain their content.
    public String generateLectureStructure() {

        String content = contentService.getLatestContent();

        if (content == null || content.isBlank()) {
            throw new RuntimeException(
                    "Please upload study material first."
            );
        }

        String[] lines = content.split("\\R");

        Map<String, StringBuilder> sections =
                new LinkedHashMap<>();

        String currentHeading = "Introduction";
        StringBuilder currentContent = new StringBuilder();

        for (String rawLine : lines) {

            String line = cleanLine(rawLine);

            if (line.isBlank()) {
                continue;
            }

            if (isHeading(line)) {

                saveSection(
                        sections,
                        currentHeading,
                        currentContent
                );

                currentHeading = line;
                currentContent = new StringBuilder();

            } else {

                currentContent.append(line).append(" ");
            }
        }

        saveSection(
                sections,
                currentHeading,
                currentContent
        );

        if (sections.isEmpty()) {
            return "No document sections detected.";
        }

        StringBuilder result = new StringBuilder();
        result.append("DOCUMENT SECTIONS:\n\n");

        int number = 1;

        for (Map.Entry<String, StringBuilder> entry
                : sections.entrySet()) {

            result.append("SECTION ")
                    .append(number++)
                    .append("\nHEADING: ")
                    .append(entry.getKey())
                    .append("\nCONTENT: ")
                    .append(entry.getValue().toString().trim())
                    .append("\n\n");
        }

        System.out.println(
                "===== DOCUMENT STRUCTURE ====="
        );
        System.out.println(result);

        return result.toString();
    }

    
private boolean isHeading(String line) {

    if (line.length() < 3 || line.length() > 120) {
        return false;
    }

    // Explicit chapter/unit headings
    if (UNIT_HEADING.matcher(line).matches()) {
        return true;
    }

    // Numbered headings and subheadings
    if (NUMBERED_HEADING.matcher(line).matches()) {

        String numberPart = line.split("\\s+", 2)[0]
                .replaceAll("[.)]+$", "");

        String title = line.replaceFirst(
                "^\\d+(?:\\.\\d+)*[.)]?\\s+", ""
        ).trim();

        // Reject table rows, numeric data and email addresses
        if (title.matches(".*\\d.*")
                || title.matches(".*[@₹=→|].*")) {
            return false;
        }

        // Reject long sentences or table-like lines
        if (title.split("\\s+").length > 10) {
            return false;
        }

        // Filter suspicious standalone numbering artifacts
        if (numberPart.matches("\\d+")) {
            try {
                if (Integer.parseInt(numberPart) > 15) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return title.matches("^[A-Za-z].*");
    }

    // ALL-CAPS headings, excluding rows containing numbers
    if (line.matches(".*\\d.*")) {
        return false;
    }

    return line.equals(line.toUpperCase())
            && line.matches("[A-Z0-9 &(),:/-]+")
            && line.matches(".*[A-Z].*")
            && line.split("\\s+").length <= 12;
}

    private String cleanLine(String line) {

        return line
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void saveSection(
        Map<String, StringBuilder> sections,
        String heading,
        StringBuilder text) {

    String body = text.toString().trim();

    if (body.isBlank()) {
        return;
    }

    String normalizedHeading = normalize(heading);
    String lowerBody = body.toLowerCase();

    // Skip title-page text containing only a title and page number.
    if (normalizedHeading.equals("introduction")
            && body.length() < 100
            && body.matches("(?s).*\\|\\s*\\d+\\s*$")) {
        return;
    }

    // Skip table-of-contents sections, not actual lecture content.
    if (looksLikeContentsPage(lowerBody)) {
        return;
    }

    String key = heading.trim();

    String existingKey = sections.keySet()
            .stream()
            .filter(k -> normalize(k).equals(normalize(key)))
            .findFirst()
            .orElse(null);

    if (existingKey != null) {
        sections.get(existingKey)
                .append(" ")
                .append(body);
    } else {
        sections.put(key, new StringBuilder(body));
    }
}

private boolean looksLikeContentsPage(String lowerBody) {

    boolean containsContents =
            lowerBody.contains("table of contents")
            || lowerBody.matches("(?s)^.*\\bcontents\\b.*");

    boolean hasManyNumberedEntries =
            Pattern.compile("\\b\\d+\\.\\s+[A-Za-z]")
                    .matcher(lowerBody)
                    .results()
                    .count() >= 5;

    return containsContents && hasManyNumberedEntries;
}

    private String normalize(String text) {

        return text.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}