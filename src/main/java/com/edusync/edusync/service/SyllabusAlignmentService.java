package com.edusync.edusync.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class SyllabusAlignmentService {

    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_OVERLAP = 150;
    private static final int MAX_TOPICS = 150;

    // Key terms must appear within this many words of each other.
    private static final int TIGHT_WINDOW = 12;
    private static final int WIDE_WINDOW = 50;

    // Embedding similarity thresholds.
    private static final double SEM_PHRASE = 0.45;
    private static final double SEM_PARTIAL = 0.50;
    private static final double SEM_COVERED = 0.55;

    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "for", "to", "from",
            "with", "by", "vs", "via", "is", "are", "as", "at", "its", "their",
            "into", "over", "dbs", "dws");

    // Words too common in this subject to prove coverage on their own.
    private static final Set<String> GENERIC = Set.of(
            "data", "mining", "analysis", "technique", "techniques", "method",
            "methods", "various", "large", "based", "system", "systems", "types",
            "type", "concept", "concepts", "model", "models", "need", "definition",
            "introduction", "overview", "different", "efficient", "defining");

    // Words with optional inner hyphens, e.g. "pre-processing".
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern NUMBERED = Pattern.compile("\\b\\d{1,3}\\.\\s+[A-Z]");
    private static final Pattern EXPLAIN = Pattern.compile(
            "\\b(is|are|means|refers|defined|definition|stands for|consists|used to|"
                    + "process of|helps|allows|describes|designed|known as|called)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BOOKS = Pattern.compile(
            "(?i)\\b(text\\s*/\\s*reference\\s*books?|reference\\s*books?|text\\s*books?)\\b");
    // "B and C" or "B and C heads": two single words joined by "and".
    private static final Pattern AND_PAIR = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9-]*)\\s+(?:and|&)\\s+([A-Za-z0-9][A-Za-z0-9-]*)(?:\\s+(.+))?$",
            Pattern.CASE_INSENSITIVE);

    private record TopicSpec(String display, String sub, String parent) {
    }

    private record ChunkInfo(String text, String padded, int[] offsets,
                             Map<String, List<Integer>> exact,
                             Map<String, List<Integer>> prefix) {
    }

    private static class Win {
        double weight = 0;
        int matchedCount = 0;
        int specificMatched = 0;
        int startToken = -1;
        boolean[] matched;

        Win(int n) {
            matched = new boolean[n];
        }
    }

    private final TopicExtractionService topicExtractionService;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SyllabusAlignmentService(
            TopicExtractionService topicExtractionService,
            ChunkService chunkService,
            EmbeddingService embeddingService) {
        this.topicExtractionService = topicExtractionService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
    }

    // Kept so older callers still compile.
    public String compareSyllabusAndMaterial(String syllabusText, String materialText) {
        return compareWithRetrieval(syllabusText, materialText);
    }

    public String compareWithRetrieval(String syllabusText, String materialText) {

        long start = System.currentTimeMillis();

        if (syllabusText == null || syllabusText.isBlank()) {
            throw new IllegalArgumentException("Syllabus content cannot be empty.");
        }
        if (materialText == null || materialText.isBlank()) {
            throw new IllegalArgumentException("Study material content cannot be empty.");
        }

        // 1. Syllabus topics. The model lists them, the code splits them.
        String extracted = topicExtractionService.extractTopics(syllabusText);
        System.out.println("EXTRACTED TOPICS:\n" + extracted);

        List<TopicSpec> topics = parseSyllabusTopics(extracted);
        if (topics.isEmpty()) {
            throw new IllegalStateException(
                    "No syllabus topics were found. Check the syllabus extraction output.");
        }
        if (topics.size() > MAX_TOPICS) {
            topics = new ArrayList<>(topics.subList(0, MAX_TOPICS));
        }

        // 2. Chunk the material and drop junk chunks (question lists, indexes).
        List<String> rawChunks = chunkService.chunk(materialText, CHUNK_SIZE, CHUNK_OVERLAP);
        List<String> chunks = new ArrayList<>();
        int dropped = 0;
        for (String c : rawChunks) {
            if (c == null || c.isBlank()) {
                continue;
            }
            if (isJunkChunk(c)) {
                dropped++;
                continue;
            }
            chunks.add(c);
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No usable text found in the study material.");
        }
        System.out.println("[ALIGN] topics=" + topics.size()
                + " chunks=" + chunks.size() + " droppedJunk=" + dropped);

        // 3. Embed chunks and topics (batched, no LLM).
        List<String> docs = new ArrayList<>();
        List<ChunkInfo> infos = new ArrayList<>();
        for (String c : chunks) {
            docs.add("search_document: " + c);
            infos.add(buildInfo(c));
        }
        List<double[]> chunkVectors = embeddingService.embedAll(docs);

        List<String> queries = new ArrayList<>();
        for (TopicSpec t : topics) {
            queries.add("search_query: " + t.display());
        }
        List<double[]> topicVectors = embeddingService.embedAll(queries);
        System.out.println("[ALIGN] embeddings done ms=" + (System.currentTimeMillis() - start));

        // 4. Score every topic.
        int n = infos.size();
        ArrayNode finalTopics = objectMapper.createArrayNode();
        int covered = 0;
        int partiallyCovered = 0;
        int missing = 0;

        for (int t = 0; t < topics.size(); t++) {
            TopicSpec spec = topics.get(t);
            List<String> terms = keyTerms(spec.sub());
            int nT = terms.size();

            String status = "MISSING";
            String evidence = "No section of your notes explains this topic.";
            double bestLexRatio = 0;
            double bestSem = 0;
            ArrayNode found = objectMapper.createArrayNode();
            ArrayNode notFound = objectMapper.createArrayNode();

            if (nT == 0) {
                for (String x : tokenize(spec.sub())) {
                    notFound.add(x);
                }
            } else {
                List<String> stems = new ArrayList<>();
                boolean[] specific = new boolean[nT];
                int specificCount = 0;
                for (int j = 0; j < nT; j++) {
                    stems.add(stem(terms.get(j)));
                    specific[j] = !GENERIC.contains(terms.get(j));
                    if (specific[j]) {
                        specificCount++;
                    }
                }

                // Where does each term occur in each chunk?
                List<List<List<Integer>>> pos = new ArrayList<>();
                int[] df = new int[nT];
                for (int i = 0; i < n; i++) {
                    List<List<Integer>> row = new ArrayList<>();
                    for (int j = 0; j < nT; j++) {
                        List<Integer> p = positionsFor(infos.get(i), stems.get(j));
                        if (!p.isEmpty()) {
                            df[j]++;
                        }
                        row.add(p);
                    }
                    pos.add(row);
                }

                // Rare terms weigh more; generic terms weigh much less.
                double[] weight = new double[nT];
                double totalWeight = 0;
                int anchor = -1;
                for (int j = 0; j < nT; j++) {
                    double base = 0.3 + Math.log((n + 1.0) / (df[j] + 1.0));
                    weight[j] = specific[j] ? base : base * 0.25;
                    totalWeight += weight[j];
                    if (specific[j] && (anchor < 0 || weight[j] > weight[anchor])) {
                        anchor = j;
                    }
                }

                String topicNorm = normalize(spec.sub());
                double[] topicVec = topicVectors.get(t);

                // Pick the best chunk.
                double bestScore = -1;
                int bestChunk = 0;
                Win bestWide = null;
                Win bestTight = null;
                boolean bestPhrase = false;

                for (int i = 0; i < n; i++) {
                    Win wide = bestWindow(pos.get(i), WIDE_WINDOW, weight, specific);
                    Win tight = bestWindow(pos.get(i), TIGHT_WINDOW, weight, specific);
                    boolean phrase = topicNorm.length() >= 5
                            && infos.get(i).padded().contains(" " + topicNorm + " ");
                    double sem = EmbeddingService.cosine(topicVec, chunkVectors.get(i));
                    double semNorm = Math.max(0, Math.min(1, (sem - 0.45) / 0.40));
                    double lexWide = wide.weight / totalWeight;
                    double lexTight = tight.weight / totalWeight;
                    double score = phrase
                            ? 2.0 + sem
                            : 0.6 * lexWide + 0.1 * lexTight + 0.3 * semNorm;

                    if (score > bestScore) {
                        bestScore = score;
                        bestChunk = i;
                        bestWide = wide;
                        bestTight = tight;
                        bestPhrase = phrase;
                        bestSem = sem;
                    }
                }

                ChunkInfo info = infos.get(bestChunk);
                boolean allTight = bestTight.matchedCount == nT;
                boolean allWide = bestWide.matchedCount == nT;
                // Short topics (2-3 words) must match with all words close together.
                boolean nearOk = nT <= 1 || (nT <= 3 ? allTight : allWide);
                bestLexRatio = bestWide.weight / totalWeight;
                boolean anchorOk = anchor < 0 || bestWide.matched[anchor];
                double specificRatio = specificCount == 0
                        ? (allWide ? 1.0 : 0.0)
                        : bestWide.specificMatched / (double) specificCount;

                int offset = bestWide.startToken >= 0
                        ? info.offsets()[bestWide.startToken]
                        : 0;
                boolean explained = hasExplanation(info.text(), offset);

                boolean strong = (bestPhrase && bestSem >= SEM_PHRASE)
                        || (nearOk && anchorOk && specificRatio >= 0.999
                                && bestLexRatio >= 0.85 && bestSem >= SEM_COVERED);

                boolean partial;
                if (specificCount >= 2) {
                    partial = anchorOk && specificRatio >= 0.6 && bestSem >= SEM_PARTIAL;
                } else if (specificCount == 1) {
                    partial = anchorOk && bestSem >= SEM_PARTIAL
                            && (nT == 1
                                    || (allWide && bestSem >= SEM_COVERED)
                                    || (bestWide.matchedCount >= nT - 1 && bestSem >= 0.65));
                } else {
                    partial = allWide && bestSem >= 0.60;
                }
                if (nT >= 2 && nT <= 3 && !allTight) {
                    partial = false;
                }

                if (strong) {
                    // A mention without any explanation is only a partial match.
                    status = explained ? "COVERED" : "PARTIALLY COVERED";
                } else if (partial) {
                    status = "PARTIALLY COVERED";
                }

                for (int j = 0; j < nT; j++) {
                    if (bestWide.matched[j]) {
                        found.add(terms.get(j));
                    } else {
                        notFound.add(terms.get(j));
                    }
                }

                if (!status.equals("MISSING")) {
                    evidence = snippetAt(info.text(), offset);
                }
            }

            System.out.printf("[ALIGN] lex=%.2f sem=%.3f status=%s topic=%s%n",
                    bestLexRatio, bestSem, status, spec.display());

            if (status.equals("COVERED")) {
                covered++;
            } else if (status.equals("PARTIALLY COVERED")) {
                partiallyCovered++;
            } else {
                missing++;
            }

            ObjectNode item = objectMapper.createObjectNode();
            item.put("topic", spec.display());
            if (!spec.parent().isEmpty()) {
                item.put("parent", spec.parent());
            }
            item.put("status", status);
            item.put("evidence", evidence);
            item.put("keywordMatch", round3(bestLexRatio));
            item.put("matchScore", round3(bestSem));
            item.set("foundTerms", found);
            item.set("missingTerms", notFound);
            finalTopics.add(item);
        }

        int total = finalTopics.size();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("totalTopics", total);
        output.put("covered", covered);
        output.put("partiallyCovered", partiallyCovered);
        output.put("missing", missing);
        output.put("coveragePercentage",
                total == 0 ? 0.0 : Math.round((covered * 10000.0) / total) / 100.0);
        output.set("topics", finalTopics);

        System.out.println("[ALIGN] DONE topics=" + total
                + " total time ms=" + (System.currentTimeMillis() - start));

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            throw new RuntimeException("Could not build alignment result", e);
        }
    }

    // ---------- window matching ----------

    // Finds the window of 'window' words that covers the most term weight.
    private Win bestWindow(List<List<Integer>> posByTerm, int window,
                           double[] weight, boolean[] specific) {
        int nTerms = posByTerm.size();
        List<int[]> events = new ArrayList<>();
        for (int j = 0; j < nTerms; j++) {
            for (int p : posByTerm.get(j)) {
                events.add(new int[] { p, j });
            }
        }
        events.sort((a, b) -> a[0] != b[0]
                ? Integer.compare(a[0], b[0])
                : Integer.compare(a[1], b[1]));

        Win best = new Win(nTerms);
        int[] cnt = new int[nTerms];
        double cur = 0;
        int distinct = 0;
        int l = 0;

        for (int r = 0; r < events.size(); r++) {
            int[] ev = events.get(r);
            if (cnt[ev[1]]++ == 0) {
                cur += weight[ev[1]];
                distinct++;
            }
            while (ev[0] - events.get(l)[0] > window) {
                int t = events.get(l)[1];
                if (--cnt[t] == 0) {
                    cur -= weight[t];
                    distinct--;
                }
                l++;
            }
            if (cur > best.weight + 1e-9) {
                best.weight = cur;
                best.matchedCount = distinct;
                best.startToken = events.get(l)[0];
                int sm = 0;
                for (int t = 0; t < nTerms; t++) {
                    best.matched[t] = cnt[t] > 0;
                    if (cnt[t] > 0 && specific[t]) {
                        sm++;
                    }
                }
                best.specificMatched = sm;
            }
        }
        return best;
    }

    private List<Integer> positionsFor(ChunkInfo info, String termStem) {
        TreeSet<Integer> set = new TreeSet<>();
        List<Integer> e = info.exact().get(termStem);
        if (e != null) {
            set.addAll(e);
        }
        if (termStem.length() >= 7) {
            List<Integer> p = info.prefix().get(termStem.substring(0, 6));
            if (p != null) {
                set.addAll(p);
            }
        }
        return new ArrayList<>(set);
    }

    private ChunkInfo buildInfo(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = TOKEN.matcher(lower);

        List<Integer> offs = new ArrayList<>();
        Map<String, List<Integer>> exact = new HashMap<>();
        Map<String, List<Integer>> prefix = new HashMap<>();

        int idx = 0;
        while (m.find()) {
            String s = stem(m.group().replace("-", ""));
            offs.add(Math.min(m.start(), Math.max(0, text.length() - 1)));
            exact.computeIfAbsent(s, k -> new ArrayList<>()).add(idx);
            if (s.length() >= 7) {
                prefix.computeIfAbsent(s.substring(0, 6), k -> new ArrayList<>()).add(idx);
            }
            idx++;
        }

        int[] offsets = new int[offs.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = offs.get(i);
        }
        return new ChunkInfo(text, " " + normalize(text) + " ", offsets, exact, prefix);
    }

    // ---------- helpers ----------

    private boolean isJunkChunk(String chunk) {
        Matcher m = NUMBERED.matcher(chunk);
        int numbered = 0;
        while (m.find()) {
            numbered++;
        }
        if (numbered >= 6) {
            return true;   // numbered list of questions or an index
        }
        int questions = 0;
        for (int i = 0; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '?') {
                questions++;
            }
        }
        return questions >= 4;
    }

    private boolean hasExplanation(String text, int offset) {
        int from = Math.max(0, offset - 150);
        int to = Math.min(text.length(), offset + 450);
        if (from >= to) {
            return false;
        }
        return EXPLAIN.matcher(text.substring(from, to)).find();
    }

    private List<String> keyTerms(String sub) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String t : tokenize(sub)) {
            if (t.length() >= 2 && !STOP.contains(t)) {
                all.add(t);
            }
        }
        return new ArrayList<>(all);
    }

    private String stem(String word) {
        String s = word;
        if (s.length() > 5 && s.endsWith("ing")) {
            s = s.substring(0, s.length() - 3);
        } else if (s.length() > 5 && s.endsWith("ed")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group().replace("-", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private String snippetAt(String text, int offset) {
        int from = Math.max(0, Math.min(offset - 60, text.length()));
        int to = Math.min(text.length(), from + 320);
        String s = text.substring(from, to).replaceAll("\\s+", " ").trim();
        return (from > 0 ? "..." : "") + s + (to < text.length() ? "..." : "");
    }

    private double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ---------- topic parsing (splitting is done here, not by the model) ----------

    private List<TopicSpec> parseSyllabusTopics(String extractedSyllabus) {
        List<TopicSpec> topics = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (extractedSyllabus == null) {
            return topics;
        }

        for (String rawLine : extractedSyllabus.split("\\R")) {
            String line = rawLine.trim();

            if (line.isBlank()) {
                continue;
            }

            // Skip unit headings on their own line.
            if (line.matches("(?i)^unit[\\s-]*([ivxlcdm]+|\\d+)\\s*:?$")) {
                continue;
            }

            // Accept bullet points and numbered lists.
            if (line.matches("^[-*•]\\s+.+")) {
                line = line.replaceFirst("^[-*•]\\s+", "").trim();
            } else if (line.matches("^\\d+[.)]\\s+.+")) {
                line = line.replaceFirst("^\\d+[.)]\\s+", "").trim();
            } else {
                continue;
            }

            // Cut off the book list.
            Matcher bm = BOOKS.matcher(line);
            if (bm.find()) {
                line = line.substring(0, bm.start()).trim();
            }
            if (line.isBlank()) {
                continue;
            }

            splitLine(line, topics, seen);
        }

        return topics;
    }

    private void splitLine(String rawLine, List<TopicSpec> topics, Set<String> seen) {
        String line = rawLine;

        // A long merged line starts with the unit title: "Unit title: topic, topic, ..."
        int commas = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ',') {
                commas++;
            }
        }
        if (commas >= 3) {
            int c = line.indexOf(':');
            if (c > 0) {
                String before = line.substring(0, c);
                if (before.indexOf(',') < 0 && before.trim().split("\\s+").length <= 10) {
                    line = line.substring(c + 1).trim();
                }
            }
        }

        // Sentences end at ';' or at a '.' followed by space or end of line.
        for (String sentence : line.split(";|\\.(?=\\s|$)")) {
            processSentence(sentence.trim(), topics, seen);
        }
    }

    private void processSentence(String sentence, List<TopicSpec> topics, Set<String> seen) {
        if (sentence.isEmpty()) {
            return;
        }

        List<String> parts = new ArrayList<>();
        for (String p : sentence.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }

        String parent = "";

        for (int i = 0; i < parts.size(); i++) {
            String token = parts.get(i);

            // "Parent: item" sets the parent for the items that follow.
            int colon = token.lastIndexOf(':');
            if (colon >= 0) {
                String left = token.substring(0, colon);
                int prev = left.lastIndexOf(':');
                String newParent = (prev >= 0 ? left.substring(prev + 1) : left).trim();
                if (!newParent.isEmpty()) {
                    parent = newParent;
                }
                token = token.substring(colon + 1).trim();
            }

            token = token.replaceFirst("(?i)^and\\s+", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (token.isEmpty()) {
                continue;
            }

            List<String> items = new ArrayList<>();
            boolean endsList;

            // "Star, snowflake and galaxy schemas" -> three topics sharing the head noun.
            Matcher next = null;
            if (i + 1 < parts.size() && !token.contains(" ")
                    && parts.get(i + 1).indexOf(':') < 0) {
                next = AND_PAIR.matcher(parts.get(i + 1).trim());
            }

            if (next != null && next.matches() && next.group(3) != null
                    && pluralHead(next.group(3))) {
                String head = next.group(3).trim();
                items.add(token + " " + head);
                items.add(next.group(1) + " " + head);
                items.add(next.group(2) + " " + head);
                i++;
                endsList = true;
            } else {
                Matcher m = AND_PAIR.matcher(token);
                if (m.matches()) {
                    // "Linear and Non-linear regression" -> two topics.
                    String head = m.group(3) == null ? "" : " " + m.group(3).trim();
                    items.add(m.group(1) + head);
                    items.add(m.group(2) + head);
                    endsList = true;
                } else {
                    items.add(token);
                    endsList = token.toLowerCase(Locale.ROOT).contains(" and ");
                }
            }

            for (String item : items) {
                String clean = item.replaceAll("\\s+", " ").trim();
                if (!isUsableTopic(clean)) {
                    continue;
                }
                String display = parent.isEmpty() ? clean : parent + ": " + clean;
                if (seen.add(normalize(display))) {
                    topics.add(new TopicSpec(display, clean, parent));
                }
            }

            // An "X and Y" item closes the parent's list.
            if (endsList) {
                parent = "";
            }
        }
    }

    private boolean pluralHead(String head) {
        String w = head.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return w.length() > 3 && w.endsWith("s")
                && !w.endsWith("ss") && !w.endsWith("is") && !w.endsWith("us");
    }

    private boolean isUsableTopic(String token) {
        if (token == null || token.length() < 3 || token.length() > 120) {
            return false;
        }
        if (!token.matches(".*[A-Za-z]{3,}.*")) {
            return false;
        }
        return !token.toLowerCase(Locale.ROOT)
                .matches("^(unit|module|chapter|hours?|marks?)\\b.*");
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("(?<=[a-z0-9])-(?=[a-z0-9])", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}