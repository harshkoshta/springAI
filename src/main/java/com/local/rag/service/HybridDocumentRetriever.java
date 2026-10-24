package com.local.rag.service;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.StringWriter;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private static final int TOP_K = 20;
    private static final int CANDIDATE_K = 10;
    private static final int RRF_K = 60;
    private static final int FINAL_K = 5;

    private final VectorStore vectorStore;
    private final OpenSearchClient openSearchClient;
    private final LlmReranker llmReranker;

    public HybridDocumentRetriever(
            VectorStore vectorStore,
            OpenSearchClient openSearchClient,
            LlmReranker llmReranker
    ) {
        this.vectorStore = vectorStore;
        this.openSearchClient = openSearchClient;
        this.llmReranker = llmReranker;
    }

    @Override
    public List<Document> retrieve(Query query) {

        String searchText = query.text();

        // 1. Semantic search
        List<Document> semanticDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(searchText)
                        .similarityThreshold(0.20)
                        .topK(CANDIDATE_K)
                        .build()
        );

        // 2. BM25 keyword search
        List<Document> keywordDocs = keywordSearch(searchText);

        if (semanticDocs == null) {
            semanticDocs = List.of();
        }

        // 3. Reciprocal Rank Fusion
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> documents = new HashMap<>();

        addRrfScores(semanticDocs, scores, documents);
        addRrfScores(keywordDocs, scores, documents);


        // 4. Rank fused results by RRF score (highest first)
        List<Document> rrfCandidates = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(TOP_K)
                .map(entry -> {
                    Document original = documents.get(entry.getKey());
  return Document.builder()
                            .id(original.getId())
                            .text(original.getText())
                            .metadata(original.getMetadata())
                            .score(entry.getValue())   // RRF score
                            .build();
                })
                .collect(Collectors.toList());

        // 5. LLM reranking: grade actual content, keep the most relevant
        List<Document> topHits = llmReranker.rerank(searchText, rrfCandidates, FINAL_K);

        // 6. Context preservation: pull in adjacent chunks (c-1, c+1) from the
        //    same page so answers aren't cut mid-thought.
        return expandWithNeighbors(topHits);
    }

    /**
     * Fetches neighboring chunks for each hit. Chunk IDs follow the pattern
     * {@code {documentId}-p{pageId}-c{chunkIndex}} (or 'punknown' when the page
     * is unknown), so neighbor IDs are predictable and can be fetched with a
     * single ids query.
     */
    private List<Document> expandWithNeighbors(List<Document> hits) {
        if (hits.isEmpty()) {
            return hits;
        }

        List<String> neighborIds = new ArrayList<>();
        Map<String, String[]> neighborIdsByHit = new HashMap<>();

        for (Document hit : hits) {
            Object documentId = hit.getMetadata().get("documentId");
            Object pageId = hit.getMetadata().get("pageId");
            Object chunkIndex = hit.getMetadata().get("chunkIndex");
            if (documentId == null || pageId == null || chunkIndex == null) {
                continue;
            }

            int idx = toInt(chunkIndex);
            String base = documentId + "-" + pagePart(toInt(pageId)) + "-c";
            String prev = idx > 0 ? base + (idx - 1) : null;
            String next = base + (idx + 1);

            neighborIdsByHit.put(hit.getId(), new String[]{prev, next});
            if (prev != null) {
                neighborIds.add(prev);
            }
            neighborIds.add(next);
        }

        if (neighborIds.isEmpty()) {
            return hits;
        }

        Map<String, Document> neighbors = fetchChunksByIds(neighborIds);

        // Emit [prev, hit, next] per hit, deduplicated, hit order preserved.
        Set<String> emitted = new LinkedHashSet<>();
        List<Document> expanded = new ArrayList<>();
        for (Document hit : hits) {
            String[] ids = neighborIdsByHit.getOrDefault(hit.getId(), new String[2]);
            addIfPresent(expanded, emitted, neighbors.get(ids[0]), hit.getScore());
            if (emitted.add(hit.getId())) {
                expanded.add(hit);
            }
            addIfPresent(expanded, emitted, neighbors.get(ids[1]), hit.getScore());
        }
        return expanded;
    }

    private void addIfPresent(List<Document> out, Set<String> emitted,
                              Document neighbor, Double score) {
        if (neighbor == null || !emitted.add(neighbor.getId())) {
            return;
        }
        out.add(Document.builder()
                .id(neighbor.getId())
                .text(neighbor.getText())
                .metadata(neighbor.getMetadata())
                .score(score)   // inherit the parent hit's relevance score
                .build());
    }

    private Map<String, Document> fetchChunksByIds(List<String> ids) {
        try {
            var osRequest = org.opensearch.client.opensearch.core.SearchRequest.of(s -> s
                    .index("rag-chunks")
                    .size(ids.size())
                    .query(q -> q.ids(i -> i.values(ids))));

            var response = openSearchClient.search(osRequest, Map.class);

            Map<String, Document> result = new HashMap<>();
            for (Document doc : mapKeywordSearchResults(response)) {
                result.put(doc.getId(), doc);
            }
            return result;
        } catch (IOException e) {
            log.warn("Neighbor expansion failed, returning hits unexpanded: {}", e.getMessage());
            return Map.of();
        }
    }

    private String pagePart(int pageId) {
        return pageId < 0 ? "punknown" : "p" + pageId;
    }

    private int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    private List<Document> keywordSearch(String searchText) {
        try {
            org.opensearch.client.opensearch.core.SearchRequest osRequest =
                    org.opensearch.client.opensearch.core.SearchRequest.of(s -> s
                            .index("rag-chunks")
                            .size(CANDIDATE_K)
                            .query(q -> q.match(m -> m
                                    .field("content")
                                    .query(FieldValue.of(searchText)))));

            StringWriter writer = new StringWriter();
            JacksonJsonpMapper mapper = new JacksonJsonpMapper();
            try (var generator = mapper.jsonProvider().createGenerator(writer)) {
                osRequest.serialize(generator, mapper);
            }
            log.info("OpenSearch keyword query: {}", writer);

            var response = openSearchClient.search(osRequest, Map.class);

            return mapKeywordSearchResults(response);
        } catch (IOException e) {
            throw new IllegalStateException("OpenSearch keyword search failed", e);
        }
    }

    private List<Document> mapKeywordSearchResults(SearchResponse<Map> response) {
        return response.hits().hits().stream()
                .filter(hit -> hit.source() != null && hit.source().get("content") instanceof String)
                .map(hit -> {
                    Map<String, Object> metadata = new HashMap<>();
                    hit.source().forEach((key, value) -> {
                        if (!"content".equals(key) && !"embedding".equals(key)) {
                            metadata.put(key.toString(), value);
                        }
                    });
                    return Document.builder()
                            .id(hit.id())
                            .text((String) hit.source().get("content"))
                            .metadata(metadata)
                            .score(hit.score() != null ? hit.score().doubleValue() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private void addRrfScores(
            List<Document> results,
            Map<String, Double> scores,
            Map<String, Document> documents
    ) {
        for (int i = 0; i < results.size(); i++) {
            Document document = results.get(i);
            String id = document.getId();

            // Ranks are 1-based
            double rrfScore = 1.0 / (RRF_K + i + 1);

            scores.merge(id, rrfScore, Double::sum);
            documents.putIfAbsent(id, document);
        }
    }
}
