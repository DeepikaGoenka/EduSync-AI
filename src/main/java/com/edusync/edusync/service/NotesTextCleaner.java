package com.edusync.edusync.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes extraction noise from uploaded notes before they are used for chat, quizzes and videos:
 *  - Word/PDF auto-numbering that keeps counting across the whole document
 *    (e.g. "84. Task-relevant data", "70. Pattern-oriented analysis"),
 *  - page footers such as "Data Mining - MCA 3rd Semester | 12",
 *  - table-of-contents lines such as "10. 10. Types of Data Mining".
 * Real numbering inside a list (1. 2. 3.) and real chapter headings (1. DATA PRE-PROCESSING) are kept.
 */
public final class NotesTextCleaner {

    private static final Pattern RUNNING_NUMBER = Pattern.compile("^(\\d{2,3})\\.\\s+(.+)$");
    private static final Pattern MAIN_HEADING = Pattern.compile("^\\d{1,2}\\.\\s+[A-Z][A-Z0-9 &,()/'-]{3,80}$");
    private static final Pattern PAGE_NOISE = Pattern.compile("^.{0,60}\\|\\s*\\d{1,3}$");
    private static final Pattern TOC_LINE = Pattern.compile("^\\d{1,2}\\.\\s+\\d{1,2}\\.\\s+.*");

    private NotesTextCleaner() {
    }

    /** Cleans a whole document, keeping the line structure. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = cleanLine(raw);
            if (line != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Cleans one line; returns null if the line is noise and should be dropped. */
    public static String cleanLine(String raw) {
        String l = raw.replace('\u00A0', ' ').replaceAll("[ \\t]+", " ").trim();
        if (l.isEmpty()) {
            return "";
        }
        if (PAGE_NOISE.matcher(l).matches() || TOC_LINE.matcher(l).matches()) {
            return null;
        }
        Matcher m = RUNNING_NUMBER.matcher(l);
        if (m.matches() && !MAIN_HEADING.matcher(l).matches()) {
            return m.group(2).trim();
        }
        return l;
    }
}