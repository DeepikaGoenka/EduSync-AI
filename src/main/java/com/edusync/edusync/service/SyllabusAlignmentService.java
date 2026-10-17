package com.edusync.edusync.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final int NEAR_SHORT = 12;   // topics with up to 3 words
    private static final int NEAR_LONG = 25;    // longer topics
    private static final int WIDE_WINDOW = 50;

    // Embedding similarity thresholds.
    private static final double SEM_PHRASE = 0.45;
    private static final double SEM_PARTIAL = 0.55;
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
    private static final Pattern UNIT_MARK = Pattern.compile(
            "(?i)\\bunit[\\s\\-\u2013]*(?:[ivx]{1,4}|\\d{1,2})\\b\\s*[:.\\-\u2013]?");
    // "B and C" or "B and C heads": two single words joined by "and".
    private static final Pattern AND_PAIR = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9-]*)\\s+(?:and|&)\\s+([A-Za-z0-9][A-Za-z0-9-]*)(?:\\s+(.+))?$",
            Pattern.CASE_INSENSITIVE);
    // "data integration and transformation": two words, "and", one word.
    private static final Pattern LEFT2 = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9-]*)\\s+([A-Za-z0-9][A-Za-z0-9-]*)\\s+(?:and|&)\\s+([A-Za-z0-9][A-Za-z0-9-]*)$",
            Pattern.CASE_INSENSITIVE);

    private record TopicSpec(String display, String sub, String parent) {
    }

    private record ChunkInfo(String text, String padded, int[] offsets,
                             Map<String, List<Integer>> exact) {
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

        // 1. Syllabus topics: split the OCR text in code. Use the model only as a fallback.
        String direct = directUnits(syllabusText);
        List<TopicSpec> topics = direct != null
                ? parseSyllabusTopics(direct)
                : new ArrayList<>();

        if (topics.size() >= 5) {
            System.out.println("[TOPICS] source=direct\n" + direct);
        } else {
            String extracted = topicExtractionService.extractTopics(syllabusText);
            System.out.println("[TOPICS] source=model\n" + extracted);
            topics = parseSyllabusTopics(extracted);
        }

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

                int nearWin = nT <= 3 ? NEAR_SHORT : NEAR_LONG;
                int allowance = specificCount >= 2 ? 1 : 0;   // generic words that may be absent
                String topicNorm = normalize(spec.sub());
                double[] topicVec = topicVectors.get(t);

                // Pick the best chunk. Chunks that actually explain the topic get a bonus.
                double bestScore = -1;
                int bestChunk = 0;
                Win bestWide = null;
                Win bestNear = null;
                boolean bestPhrase = false;
                boolean bestExplained = false;

                for (int i = 0; i < n; i++) {
                    ChunkInfo ci = infos.get(i);
                    Win wide = bestWindow(pos.get(i), WIDE_WINDOW, weight, specific);
                    Win near = bestWindow(pos.get(i), nearWin, weight, specific);
                    boolean phrase = topicNorm.length() >= 5
                            && ci.padded().contains(" " + topicNorm + " ");
                    double sem = EmbeddingService.cosine(topicVec, chunkVectors.get(i));
                    double semNorm = Math.max(0, Math.min(1, (sem - 0.45) / 0.40));
                    double lexWide = wide.weight / totalWeight;
                    double lexNear = near.weight / totalWeight;
                    boolean explainedHere = near.matchedCount > 0 && near.startToken >= 0
                            && hasExplanation(ci.text(), ci.offsets()[near.startToken]);

                    double base = phrase
                            ? 2.0 + sem
                            : 0.4 * lexWide + 0.3 * lexNear + 0.3 * semNorm;
                    double score = base + (explainedHere ? 0.1 : 0.0);

                    if (score > bestScore) {
                        bestScore = score;
                        bestChunk = i;
                        bestWide = wide;
                        bestNear = near;
                        bestPhrase = phrase;
                        bestSem = sem;
                        bestExplained = explainedHere;
                    }
                }

                ChunkInfo info = infos.get(bestChunk);
                bestLexRatio = bestWide.weight / totalWeight;
                double lexNearRatio = bestNear.weight / totalWeight;
                boolean anchorWide = anchor < 0 || bestWide.matched[anchor];

                // All specific terms close together (generic terms may be missing if allowed).
                boolean nearOk;
                if (specificCount == 0) {
                    nearOk = bestNear.matchedCount == nT;
                } else {
                    int genericTotal = nT - specificCount;
                    int genericMatched = bestNear.matchedCount - bestNear.specificMatched;
                    nearOk = bestNear.specificMatched == specificCount
                            && (genericTotal - genericMatched) <= allowance;
                }

                boolean strong = (bestPhrase && bestSem >= SEM_PHRASE)
                        || (nearOk && lexNearRatio >= 0.85 && bestSem >= SEM_COVERED);

                boolean partial;
                if (specificCount >= 2) {
                    partial = anchorWide
                            && bestWide.specificMatched
                                    >= Math.max(2, (int) Math.ceil(0.6 * specificCount))
                            && bestSem >= SEM_PARTIAL;
                    if (nT <= 3 && bestNear.specificMatched < 2) {
                        partial = false;
                    }
                } else if (specificCount == 1) {
                    partial = anchorWide && bestSem >= SEM_PARTIAL
                            && (nT == 1 || bestNear.matchedCount == nT);
                } else {
                    partial = bestNear.matchedCount == nT && bestSem >= 0.60;
                }

                if (strong) {
                    // A mention without any explanation is only a partial match.
                    status = bestExplained ? "COVERED" : "PARTIALLY COVERED";
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
                    int offset = 0;
                    if (bestNear.matchedCount > 0 && bestNear.startToken >= 0) {
                        offset = info.offsets()[bestNear.startToken];
                    } else if (bestWide.startToken >= 0) {
                        offset = info.offsets()[bestWide.startToken];
                    }
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

    // A chunk word matches a term if it is the same word or the term plus up to
    // three extra letters (dimension -> dimensions, dimensional; not dimensionality).
    private List<Integer> positionsFor(ChunkInfo info, String termStem) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : info.exact().entrySet()) {
            String k = e.getKey();
            if (k.equals(termStem)
                    || (termStem.length() >= 5
                            && k.length() <= termStem.length() + 3
                            && k.startsWith(termStem))) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    private ChunkInfo buildInfo(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = TOKEN.matcher(lower);

        List<Integer> offs = new ArrayList<>();
        Map<String, List<Integer>> exact = new HashMap<>();

        int idx = 0;
        while (m.find()) {
            String s = stem(m.group().replace("-", ""));
            offs.add(Math.min(m.start(), Math.max(0, text.length() - 1)));
            exact.computeIfAbsent(s, k -> new ArrayList<>()).add(idx);
            idx++;
        }

        int[] offsets = new int[offs.size()];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = offs.get(i);
        }
        return new ChunkInfo(text, " " + normalize(text) + " ", offsets, exact);
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
        if (s.length() > 4 && s.endsWith("ies")) {
            s = s.substring(0, s.length() - 3) + "y";
        } else if (s.length() > 5 && s.endsWith("ing")) {
            s = s.substring(0, s.length() - 3);
        } else if (s.length() > 5 && s.endsWith("ed")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() > 5 && s.endsWith("e")) {
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

    // Cuts the syllabus text into units ("Unit-I ... Unit-II ...") and drops the book list.
    // Returns one bullet line per unit, or null if no unit headings were found.
    private String directUnits(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replace("\r", " ").replaceAll("\\s+", " ").trim();

        Matcher bm = BOOKS.matcher(flat);
        String body = bm.find() ? flat.substring(0, bm.start()) : flat;

        List<int[]> marks = new ArrayList<>();
        Matcher um = UNIT_MARK.matcher(body);
        while (um.find()) {
            marks.add(new int[] { um.start(), um.end() });
        }
        if (marks.size() < 2) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i)[1];
            int to = i + 1 < marks.size() ? marks.get(i + 1)[0] : body.length();
            String seg = body.substring(from, to).trim();
            if (seg.length() > 10) {
                sb.append("- ").append(seg).append("\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

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

        // Sentences end at ';' or at a '.' followed by space or end of line ("vs." excluded).
        for (String sentence : line.split(";|(?<![Vv][Ss])\\.(?=\\s|$)")) {
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
        String prevText = null;

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
                Matcher l2 = LEFT2.matcher(token);
                if (m.matches()) {
                    String head = m.group(3) == null ? "" : m.group(3).trim();
                    if (!head.isEmpty() && isGenericHead(head)) {
                        // "fact and dimension data": keep as one topic.
                        items.add(token);
                    } else {
                        // "Linear and Non-linear regression" -> two topics.
                        String h = head.isEmpty() ? "" : " " + head;
                        items.add(m.group(1) + h);
                        items.add(m.group(2) + h);
                    }
                    endsList = true;
                } else if (l2.matches()) {
                    // "data integration and transformation" -> two topics.
                    items.add(l2.group(1) + " " + l2.group(2));
                    items.add(l2.group(1) + " " + l2.group(3));
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

                String sub = clean;
                String display = parent.isEmpty() ? clean : parent + ": " + clean;

                // A lone generic word like "definition" is tied to the previous topic.
                if (parent.isEmpty() && !clean.contains(" ") && prevText != null
                        && GENERIC.contains(clean.toLowerCase(Locale.ROOT))) {
                    String ctx = lastWords(prevText, 2);
                    sub = clean + " " + ctx;
                    display = clean + " of " + ctx;
                }

                if (seen.add(normalize(display))) {
                    topics.add(new TopicSpec(display, sub, parent));
                }
                prevText = clean;
            }

            // An "X and Y" item closes the parent's list.
            if (endsList) {
                parent = "";
            }
        }
    }

    private String lastWords(String text, int count) {
        String[] w = text.trim().split("\\s+");
        int from = Math.max(0, w.length - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < w.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w[i]);
        }
        return sb.toString();
    }

    private boolean isGenericHead(String head) {
        String[] w = head.trim().split("\\s+");
        return GENERIC.contains(w[w.length - 1].toLowerCase(Locale.ROOT));
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