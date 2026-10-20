package com.edusync.edusync.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Quiz generator.
 *
 * 1. Main method (accurate): builds questions from the "Definition: X is ..." statements in the
 *    student's own notes. The correct answer is the notes' own wording, and the wrong answers are
 *    definitions of other terms from the same notes, so every answer can be checked against the notes.
 * 2. Fallback (only if the notes have too few definitions): asks the local model for questions and
 *    keeps only those whose answer is supported by the passages of the notes.
 */
@Service
public class QuizService {

    private static final int QUESTIONS = 5;
    private static final int PASSAGES = 6;
    private static final int MAX_CONTEXT_CHARS = 5400;
    private static final int MAX_MODEL_CALLS = 3;
    private static final int MAX_OPTION_CHARS = 150;

    private static final Set<String> STOP = Set.of("the", "of", "in", "and", "a", "an", "to", "for", "on",
            "with", "is", "are", "what", "about", "between", "from", "by", "or", "as", "at", "it", "its");
    private static final Set<String> GENERIC = Set.of("data");

    // Placeholder text the small model sometimes copies (fallback path only).
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)^\\W*(option|choice|answer)\\s*[a-d1-4]?\\W*$|^\\W*question\\W*$"
                    + "|.*(first|second|third|fourth) option text.*|.*the full question text.*");

    private static final Pattern DEF_LINE = Pattern.compile(
            "^([A-Za-z][A-Za-z\\- ]{2,40}):\\s+(.+)$");
    private static final Pattern SUBJECT_VERB = Pattern.compile(
            "^(?:(?:A|An|The)\\s+)?(.{2,70}?)\\s+(?:is|are|means|refers to)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUB_HEADING = Pattern.compile("^\\d{1,2}\\.\\d{1,2}\\.?\\s+[^.]{3,90}$");
    private static final Pattern MAIN_HEADING = Pattern.compile("^\\d{1,2}\\.\\s+[A-Z][A-Z0-9 &,()/'-]{3,80}$");

    private final AIService aiService;
    private final MaterialRetrievalService retrieval;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    private record Question(String text, List<String> options, int answer) {
    }

    private record Def(String term, String meaning, String section, int chapter, int pos) {
    }

    public QuizService(AIService aiService, MaterialRetrievalService retrieval) {
        this.aiService = aiService;
        this.retrieval = retrieval;
    }

    public String generateQuiz(String topic) {

        String t = topic == null ? "" : topic.trim();
        if (t.isEmpty()) {
            return "Please give a topic for the quiz.";
        }
        if (!retrieval.hasMaterial()) {
            return "Please upload your study material first.";
        }

        List<Question> quiz = new ArrayList<>();

        // 1) accurate questions straight from the definitions in the notes
        List<Def> all = extractDefinitions(retrieval.cleanedNotes());
        List<Def> picked = pickForTopic(all, t, QUESTIONS);
        for (int i = 0; i < picked.size(); i++) {
            Question q = (i % 2 == 0) ? whatIs(picked.get(i), all) : whichTerm(picked.get(i), all);
            if (q != null) {
                quiz.add(q);
            }
        }
        System.out.println("[QUIZ] definitions in notes=" + all.size() + ", matched topic=" + picked.size()
                + ", questions built=" + quiz.size());

        // 2) fallback: ask the model for the rest, keeping only answers the notes support
        if (quiz.size() < QUESTIONS) {
            fillWithModel(t, quiz);
        }

        if (quiz.isEmpty()) {
            return "Could not generate a quiz for \"" + t + "\" from your notes. "
                    + "Try a topic name that appears in your uploaded material.";
        }
        return format(quiz);
    }

    // ================= definition-based questions =================

    private List<Def> extractDefinitions(String notes) {
        List<String> lines = new ArrayList<>();
        for (String l : notes.split("\n")) {
            if (!l.trim().isEmpty()) {
                lines.add(l.trim());
            }
        }

        List<Def> defs = new ArrayList<>();
        Set<String> seenTerms = new HashSet<>();
        String section = "";

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if ((SUB_HEADING.matcher(l).matches() || MAIN_HEADING.matcher(l).matches()) && !l.endsWith(".")) {
                section = l;
                continue;
            }

            Matcher m = DEF_LINE.matcher(l);
            if (!m.matches()) {
                continue;
            }
            String label = m.group(1).trim();
            StringBuilder sb = new StringBuilder(m.group(2).trim());

            // a definition can wrap over several lines: read until the sentence ends
            int j = i;
            while (!sb.toString().endsWith(".") && j + 1 < lines.size() && j - i < 3) {
                String next = lines.get(j + 1);
                if (DEF_LINE.matcher(next).matches() || SUB_HEADING.matcher(next).matches()
                        || MAIN_HEADING.matcher(next).matches() || next.startsWith("•")) {
                    break;
                }
                sb.append(' ').append(next);
                j++;
            }

            Matcher sv = SUBJECT_VERB.matcher(sb.toString().trim());
            if (!sv.matches()) {
                continue;
            }
            String term = sv.group(1).trim();
            if (term.contains(",")) {
                term = term.substring(0, term.indexOf(',')).trim();   // "Attribute construction, also called ..."
            }
            String meaning = sv.group(2).trim();

            boolean isDefLabel = label.equalsIgnoreCase("Definition") || label.equalsIgnoreCase("Formal Definition");
            boolean labelRepeatsTerm = label.equalsIgnoreCase(term);
            if (!isDefLabel && !labelRepeatsTerm) {
                continue;
            }
            if (term.split("\\s+").length > 8 || term.matches("(?i)^(this|it|these|those|there|that)\\b.*")) {
                continue;
            }
            meaning = stripEnd(meaning);
            if (meaning.length() < 25 || meaning.length() > 420) {
                continue;
            }
            if (!seenTerms.add(term.toLowerCase(Locale.ROOT))) {
                continue;
            }
            defs.add(new Def(capitalize(term), capitalize(meaning), section, chapterOf(section), defs.size()));
        }
        return defs;
    }

    private List<Def> pickForTopic(List<Def> defs, String topic, int max) {
        Set<String> words = contentWords(topic, 3);
        if (words.size() > 1) {
            words.removeAll(GENERIC);   // "data" is in almost every term; ignore it unless it is the only word
        }
        if (words.isEmpty()) {
            return new ArrayList<>();
        }

        List<double[]> scored = new ArrayList<>();
        for (Def d : defs) {
            double termHit = overlap(words, contentWords(d.term(), 3));
            double sectionHit = overlap(words, contentWords(d.section(), 3));
            double textHit = overlap(words, contentWords(d.meaning(), 3));
            double score = 4 * termHit + 3 * sectionHit + 0.5 * textHit;
            if (termHit > 0 || sectionHit > 0) {
                scored.add(new double[] { d.pos(), score });
            }
        }
        scored.sort((a, b) -> b[1] != a[1] ? Double.compare(b[1], a[1]) : Double.compare(a[0], b[0]));

        List<Def> out = new ArrayList<>();
        for (double[] s : scored) {
            if (out.size() < max) {
                out.add(defs.get((int) s[0]));
            }
        }

        // Few direct matches (e.g. a single term like "Normalization"): add the neighbouring
        // definitions from the same chapter of the notes, so the quiz still has enough questions.
        if (!out.isEmpty() && out.size() < max) {
            final Def first = out.get(0);
            List<Def> same = new ArrayList<>();
            for (Def d : defs) {
                if (d.chapter() == first.chapter() && first.chapter() > 0 && !out.contains(d)) {
                    same.add(d);
                }
            }
            same.sort((a, b) -> Integer.compare(Math.abs(a.pos() - first.pos()), Math.abs(b.pos() - first.pos())));
            for (Def d : same) {
                if (out.size() < max) {
                    out.add(d);
                }
            }

            // still short (the chapter has few definitions): use the nearest definitions in the notes
            if (out.size() < max) {
                List<Def> near = new ArrayList<>(defs);
                near.removeAll(out);
                near.sort((x, y) -> Integer.compare(Math.abs(x.pos() - first.pos()), Math.abs(y.pos() - first.pos())));
                for (Def d : near) {
                    if (out.size() < max) {
                        out.add(d);
                    }
                }
            }
        }
        return out;
    }

    private int chapterOf(String section) {
        Matcher m = Pattern.compile("^(\\d{1,2})[.\\s]").matcher(section);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // "What is X?"  -> correct: X's definition; wrong: definitions of nearby, different terms
    private Question whatIs(Def d, List<Def> all) {
        List<Def> wrong = distractors(d, all, true);
        if (wrong.size() < 3) {
            return null;
        }
        List<String> options = new ArrayList<>();
        options.add(shorten(d.meaning()));
        for (Def w : wrong) {
            options.add(shorten(w.meaning()));
        }
        return new Question("What is " + d.term() + "?", options, 0);
    }

    // "Which term is defined as ...?" -> correct: X; wrong: other terms from the notes
    private Question whichTerm(Def d, List<Def> all) {
        List<Def> wrong = distractors(d, all, false);
        if (wrong.size() < 3) {
            return null;
        }
        List<String> options = new ArrayList<>();
        options.add(d.term());
        for (Def w : wrong) {
            options.add(w.term());
        }
        String q = "Which term is defined as: \"" + shorten(d.meaning()) + "\"?";
        return new Question(q, options, 0);
    }

    // The 3 closest definitions in the notes (they sit in the same chapter, so they are believable)
    // that are clearly different from the right answer.
    private List<Def> distractors(Def d, List<Def> all, boolean byMeaning) {
        List<Def> cand = new ArrayList<>(all);
        cand.remove(d);
        cand.sort((a, b) -> Integer.compare(Math.abs(a.pos() - d.pos()), Math.abs(b.pos() - d.pos())));

        List<Def> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Def c : cand) {
            if (out.size() == 3) {
                break;
            }
            // skip wrong answers that are too close in wording to the right one (could also be "correct")
            if (jaccard(c.meaning(), d.meaning()) >= 0.30) {
                continue;
            }
            // for term options, a term that is a part of the right term (or the reverse) is confusing
            String ct = c.term().toLowerCase(Locale.ROOT);
            String dt = d.term().toLowerCase(Locale.ROOT);
            if (!byMeaning && (ct.contains(dt) || dt.contains(ct))) {
                continue;
            }
            String key = byMeaning ? shorten(c.meaning()).toLowerCase(Locale.ROOT) : ct;
            if (used.add(key)) {
                out.add(c);
            }
        }
        return out;
    }

    private String shorten(String s) {
        if (s.length() <= MAX_OPTION_CHARS) {
            return s;
        }
        int cut = s.lastIndexOf(' ', MAX_OPTION_CHARS - 3);
        if (cut < MAX_OPTION_CHARS / 2) {
            cut = MAX_OPTION_CHARS - 3;
        }
        return s.substring(0, cut).replaceAll("[,;:\\s]+$", "") + "...";
    }

    private String stripEnd(String s) {
        return s.replaceAll("[\\s.]+$", "");
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ================= model fallback =================

    private void fillWithModel(String topic, List<Question> quiz) {
        String context = retrieval.relevantText(topic, PASSAGES, MAX_CONTEXT_CHARS);
        if (context.isBlank()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Question q : quiz) {
            seen.add(q.text().toLowerCase(Locale.ROOT));
        }

        for (int call = 1; call <= MAX_MODEL_CALLS && quiz.size() < QUESTIONS; call++) {
            int want = Math.min(3, QUESTIONS - quiz.size());
            try {
                int added = 0;
                for (Question q : parse(aiService.askAIForJson(buildPrompt(topic, context, want, quiz)))) {
                    if (quiz.size() >= QUESTIONS) {
                        break;
                    }
                    if (!supported(q, context)) {
                        System.out.println("[QUIZ] dropped (answer not supported by the notes): " + q.text());
                        continue;
                    }
                    if (seen.add(q.text().toLowerCase(Locale.ROOT))) {
                        quiz.add(q);
                        added++;
                    }
                }
                System.out.println("[QUIZ] model call " + call + ": " + added + " question(s) kept");
            } catch (Exception e) {
                System.out.println("[QUIZ] model call " + call + " failed: " + e.getMessage());
            }
        }
    }

    private String buildPrompt(String topic, String content, int want, List<Question> already) {
        StringBuilder avoid = new StringBuilder();
        for (Question q : already) {
            avoid.append("- ").append(q.text()).append('\n');
        }
        return """
                You are a university teacher writing a multiple-choice quiz.

                TOPIC: %s

                STUDY MATERIAL (passages from the student's notes):
                %s

                Write exactly %d multiple-choice questions about the TOPIC, using ONLY facts from the
                study material above.

                Rules:
                - Each question tests one specific fact, definition, step, formula or example that is
                  stated in the material and has ONE clear answer.
                - Every question has exactly 4 different options, each short (at most 12 words). Only one
                  option is correct according to the material. The other three must be clearly wrong
                  according to the material; never use another valid item from the material as a wrong option.
                - "answerIndex" is the position of the correct option in "options": 0, 1, 2 or 3.
                - Do not write placeholders such as "Question?" or "Option A". No introduction, no explanations.
                %s
                Return JSON only, in this shape (the values below only describe what goes there):
                {"questions":[{"question":"the full question text","options":["first option text","second option text","third option text","fourth option text"],"answerIndex":0}]}
                """.formatted(topic, content, want,
                avoid.length() == 0 ? "" : "- Do NOT repeat these questions:\n" + avoid);
    }

    // The right answer must really be in the notes, next to what the question asks about.
    private boolean supported(Question q, String context) {
        Set<String> answer = contentWords(q.options().get(q.answer()), 4);
        Set<String> question = contentWords(q.text(), 4);
        question.removeAll(GENERIC);
        question.remove("mining");
        question.remove("goal");
        question.remove("main");
        question.remove("primary");
        question.remove("purpose");
        if (answer.isEmpty()) {
            return false;
        }
        for (String passage : context.split("\n\n")) {
            Set<String> pw = contentWords(passage, 4);
            if (overlap(answer, pw) >= 0.6 && (question.isEmpty() || overlap(question, pw) >= 0.5)) {
                return true;
            }
        }
        return false;
    }

    private List<Question> parse(String json) throws Exception {
        List<Question> out = new ArrayList<>();
        JsonNode root = readLenient(json);
        JsonNode arr = root.has("questions") ? root.get("questions") : root;
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            String text = clean(n.path("question").asText(""));
            if (text.length() < 15 || PLACEHOLDER.matcher(text).matches()) {
                continue;
            }
            List<String> options = new ArrayList<>();
            for (JsonNode o : n.path("options")) {
                options.add(clean(o.asText("")));
            }
            if (options.size() != 4) {
                continue;
            }
            boolean bad = false;
            Set<String> distinct = new HashSet<>();
            for (String o : options) {
                if (o.isEmpty() || PLACEHOLDER.matcher(o).matches()) {
                    bad = true;
                }
                distinct.add(o.toLowerCase(Locale.ROOT));
            }
            if (bad || distinct.size() != 4 || tooSimilar(options)) {
                continue;
            }
            int answer = answerIndex(n);
            if (answer < 0 || answer > 3) {
                continue;
            }
            out.add(new Question(text, options, answer));
        }
        return out;
    }

    private JsonNode readLenient(String json) throws Exception {
        try {
            return mapper.readTree(json);
        } catch (Exception first) {
            int cut = json.lastIndexOf("},");
            if (cut > 0) {
                return mapper.readTree(json.substring(0, cut + 1) + "]}");
            }
            throw first;
        }
    }

    private int answerIndex(JsonNode n) {
        if (n.has("answerIndex")) {
            int v = n.get("answerIndex").asInt(-1);
            if (v >= 0) {
                return v;
            }
        }
        String a = n.path("answer").asText("").trim().toUpperCase(Locale.ROOT);
        if (!a.isEmpty() && a.charAt(0) >= 'A' && a.charAt(0) <= 'D') {
            return a.charAt(0) - 'A';
        }
        return -1;
    }

    // ================= shared helpers =================

    private String format(List<Question> quiz) {
        StringBuilder sb = new StringBuilder();
        int no = 1;
        for (Question q : quiz) {
            List<String> opts = new ArrayList<>(q.options());
            String correct = opts.get(q.answer());
            Collections.shuffle(opts, random);

            sb.append("Q").append(no++).append(". ").append(q.text()).append('\n');
            for (int i = 0; i < 4; i++) {
                sb.append((char) ('A' + i)).append(". ").append(opts.get(i)).append('\n');
            }
            sb.append("Answer: ").append((char) ('A' + opts.indexOf(correct))).append("\n\n");
        }
        return sb.toString().trim();
    }

    private boolean tooSimilar(List<String> options) {
        for (int i = 0; i < options.size(); i++) {
            for (int j = i + 1; j < options.size(); j++) {
                if (jaccard(options.get(i), options.get(j)) >= 0.7) {
                    return true;
                }
            }
        }
        return false;
    }

    private double jaccard(String a, String b) {
        Set<String> x = contentWords(a, 1);
        Set<String> y = contentWords(b, 1);
        if (x.size() < 4 || y.size() < 4) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return inter.size() / (double) union.size();
    }

    // lower-case word stems without stop words
    private Set<String> contentWords(String s, int minLen) {
        Set<String> w = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= minLen && !STOP.contains(t)) {
                w.add(stem(t));
            }
        }
        return w;
    }

    private String stem(String t) {
        if (t.length() > 5 && t.endsWith("ing")) {
            return t.substring(0, t.length() - 3);
        }
        if (t.length() > 4 && t.endsWith("es")) {
            return t.substring(0, t.length() - 2);
        }
        if (t.length() > 4 && t.endsWith("s")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

    // share of the wanted words that appear (exactly or as a prefix) in the other set
    private double overlap(Set<String> wanted, Set<String> in) {
        if (wanted.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String w : wanted) {
            for (String x : in) {
                if (x.equals(w) || (w.length() >= 5 && x.startsWith(w.substring(0, 5)) && x.length() >= 5)) {
                    hit++;
                    break;
                }
            }
        }
        return hit / (double) wanted.size();
    }

    private String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[*_#`]", "").replaceAll("\\s+", " ").trim();
        return t.replaceFirst("^(\\d{1,3}|[A-Da-d])[.)]\\s+", "");
    }
}