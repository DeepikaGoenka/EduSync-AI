package com.edusync.edusync.service;

import java.util.*;

public class MaterialIndex {
    public record Hit(String text, double score) {}

    private final List<String> chunks;
    private final List<double[]> vectors;

    public MaterialIndex(List<String> chunks, List<double[]> vectors) {
        this.chunks = chunks;
        this.vectors = vectors;
    }

    public List<Hit> topK(double[] query, int k) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            hits.add(new Hit(chunks.get(i), EmbeddingService.cosine(query, vectors.get(i))));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(k, hits.size()));
    }
}