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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class HybridDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private static final int TOP_K = 50;
    private static final int CANDIDATE_K = 10;
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final OpenSearchClient openSearchClient;

    public HybridDocumentRetriever(
            VectorStore vectorStore,
            OpenSearchClient openSearchClient
    ) {
        this.vectorStore = vectorStore;
        this.openSearchClient = openSearchClient;
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


        // 4. Return highest-ranked documents
        List<Document> result = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue())
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
        return result;
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
