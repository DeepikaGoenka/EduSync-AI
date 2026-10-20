package com.edusync.edusync.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Finds the parts of the uploaded notes that are relevant to a question or topic, so the model gets the
 * right text instead of the first few thousand characters.
 *
 * Ranking = meaning match (embeddings) + keyword match (the words of the question, e.g. "task primitives").
 * Embeddings alone can miss a short list-like passage, so keywords rescue it.
 * The index is built once per uploaded document and cached.
 */
@Service
public class MaterialRetrievalService {

    private static final int CHUNK_CHARS = 900;
    private static final int CHUNK_OVERLAP = 150;

    private static final Set<String> STOP = Set.of("the", "of", "in", "and", "a", "an", "to", "for", "on", "with",
            "is", "are", "what", "which", "how", "why", "about", "between", "from", "by", "or", "as", "at", "it",
            "its", "me", "my", "please", "tell", "give", "can", "you", "do", "does", "this", "that", "these", "those");
    // words that appear everywhere in these notes or only describe the kind of answer wanted
    private static final Set<String> GENERIC = Set.of("explain", "describe", "define", "discuss", "list", "five",
            "four", "three", "two", "main", "major", "types", "type", "data", "mining", "meaning", "difference",
            "differentiate", "example", "examples");

    private final ContentService contentService;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;

    private String cachedKey = "";
    private MaterialIndex cachedIndex = null;

    public MaterialRetrievalService(
            ContentService contentService,
            ChunkService chunkService,
            EmbeddingService embeddingService) {
        this.contentService = contentService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
    }

    /** The uploaded notes with extraction noise removed (see NotesTextCleaner). */
    public String cleanedNotes() {
        return NotesTextCleaner.clean(contentService.getLatestContent());
    }

    /** True when notes have been uploaded. */
    public boolean hasMaterial() {
        String c = contentService.getLatestContent();
        return c != null && !c.isBlank();
    }

    /**
     * The k most relevant passages for the query, in the order they appear in the notes.
     * Falls back to the start of the notes if embeddings are unavailable.
     */
    public String relevantText(String query, int k, int maxChars) {
        String content = cleanedNotes();
        if (content.isBlank()) {
            return "";
        }

        try {
            MaterialIndex index = getIndex(content);
            double[] q = embeddingService.embed("search_query: " + query);

            int pool = Math.max(k * 4, 20);
            List<MaterialIndex.Hit> hits = index.topK(q, pool);
            List<String> texts = new ArrayList<>();
            for (MaterialIndex.Hit h : hits) {
                texts.add(h.text() == null ? "" : h.text().replaceAll("\\s+", " ").trim());
            }

            List<String> picked = rerank(query, texts, k);

            // keep the order of the notes (reads more naturally than score order)
            final String flat = content.replaceAll("\\s+", " ");
            picked.sort((a, b) -> Integer.compare(position(flat, a), position(flat, b)));

            int each = Math.max(300, maxChars / Math.max(1, picked.size()));
            StringBuilder sb = new StringBuilder();
            for (String p : picked) {
                String part = p.length() > each ? p.substring(0, each) : p;
                if (sb.length() + part.length() > maxChars) {
                    break;
                }
                sb.append(part).append("\n\n");
            }

            System.out.println("[RETRIEVAL] query=\"" + query + "\" passages=" + picked.size());
            for (String p : picked) {
                System.out.println("[RETRIEVAL]   - " + (p.length() > 90 ? p.substring(0, 90) : p));
            }
            if (sb.length() > 0) {
                return sb.toString().trim();
            }
        } catch (Exception e) {
            System.out.println("[RETRIEVAL] failed, using the start of the notes: " + e.getMessage());
        }

        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() > maxChars ? flat.substring(0, maxChars) : flat;
    }

    // ---------- ranking ----------

    // texts arrive in meaning-match order (best first). Combine that with keyword match.
    List<String> rerank(String query, List<String> texts, int k) {
        List<String> words = keywords(query);
        List<String> phrase = phrasePatterns(query);

        double[] score = new double[texts.size()];
        Integer[] order = new Integer[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String lower = texts.get(i).toLowerCase(Locale.ROOT);
            double semantic = 1.0 - i / (double) Math.max(1, texts.size());
            double lexical = 0;
            if (!words.isEmpty()) {
                int hit = 0;
                for (String w : words) {
                    if (lower.contains(w)) {
                        hit++;
                    }
                }
                lexical = hit / (double) words.size();
            }
            double bonus = 0;
            for (String p : phrase) {
                if (Pattern.compile(p).matcher(lower).find()) {
                    bonus += 0.6;
                }
            }
            score[i] = 0.35 * semantic + 1.0 * lexical + bonus;
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));

        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int idx : order) {
            if (out.size() >= k) {
                break;
            }
            String t = texts.get(idx);
            if (!t.isEmpty() && seen.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    // stems (first 5 letters) of the meaningful words of the question; generic words are ignored
    private List<String> keywords(String query) {
        List<String> all = new ArrayList<>();
        List<String> meaningful = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 3 || STOP.contains(t)) {
                continue;
            }
            String stem = t.length() > 5 ? t.substring(0, 5) : t;
            all.add(stem);
            if (!GENERIC.contains(t)) {
                meaningful.add(stem);
            }
        }
        return meaningful.isEmpty() ? all : meaningful;
    }

    // two neighbouring words of the question that appear together in the notes ("task primitives")
    private List<String> phrasePatterns(String query) {
        List<String> toks = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= 3 && !STOP.contains(t)) {
                toks.add(t.length() > 5 ? t.substring(0, 5) : t);
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < toks.size(); i++) {
            String a = toks.get(i);
            String b = toks.get(i + 1);
            if (GENERIC.contains(a) && GENERIC.contains(b)) {
                continue;   // "data mining" is everywhere, no signal
            }
            out.add(Pattern.quote(a) + "\\w*[\\s\\-]+" + Pattern.quote(b));
        }
        return out;
    }

    private int position(String flat, String passage) {
        String head = passage.length() > 60 ? passage.substring(0, 60) : passage;
        int p = flat.indexOf(head);
        return p < 0 ? Integer.MAX_VALUE : p;
    }

    // ---------- index ----------

    private synchronized MaterialIndex getIndex(String content) {
        String key = content.length() + ":" + content.hashCode();
        if (cachedIndex != null && key.equals(cachedKey)) {
            return cachedIndex;
        }

        List<String> chunks = new ArrayList<>();
        for (String c : chunkService.chunk(content, CHUNK_CHARS, CHUNK_OVERLAP)) {
            if (c != null && !c.isBlank()) {
                chunks.add(c);
            }
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No usable text found in the study material.");
        }

        List<String> docs = new ArrayList<>();
        for (String c : chunks) {
            docs.add("search_document: " + c);
        }

        cachedIndex = new MaterialIndex(chunks, embeddingService.embedAll(docs));
        cachedKey = key;
        return cachedIndex;
    }
}