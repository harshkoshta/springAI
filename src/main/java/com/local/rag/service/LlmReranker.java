package com.local.rag.service;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * LLM-based reranker: scores each (query, document) pair by having the model
 * read the actual chunk text, then keeps the top-N most relevant documents.
 *
 * Applied after retrieval (e.g., RRF fusion) to improve precision of the
 * context passed to generation. Falls back to the input order on failure.
 */
@Service
public class LlmReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    /** Max chars of each chunk sent to the grader, to bound token usage. */
    private static final int SNIPPET_CHARS = 1200;

    private final ChatClient chatClient;

    public record ScoredChunk(
            @JsonPropertyDescription("The 1-based index of the document in the provided list")
            int index,
            @JsonPropertyDescription("Relevance of the document to the question, 0-10")
            double score
    ) {}

    public record RerankResult(List<ScoredChunk> scores) {}

    public LlmReranker(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Reranks candidates by LLM-graded relevance and returns the top-N.
     *
     * @param query      the user question
     * @param candidates documents from retrieval/fusion
     * @param topN       number of documents to keep
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() <= topN) {
            return candidates;
        }

        String docList = IntStream.range(0, candidates.size())
                .mapToObj(i -> "[" + (i + 1) + "] " + snippet(candidates.get(i).getText()))
                .collect(Collectors.joining("\n\n"));

        RerankResult result;
        try {
            result = chatClient.prompt()
                    .user(u -> u.text("""
                            You are a relevance grader for a retrieval system.

                            Question: {question}

                            Documents:
                            {documents}

                            Score each document's relevance to the question from 0-10.
                            A document is relevant only if it contains information that
                            helps answer the question. Partial relevance scores 4-6.
                            Return scores for ALL {count} documents.
                            """)
                            .param("question", query)
                            .param("documents", docList)
                            .param("count", candidates.size()))
                    .call()
                    .entity(RerankResult.class);
        } catch (Exception e) {
            log.warn("LLM reranking failed, falling back to input order: {}", e.getMessage());
            return new ArrayList<>(candidates.subList(0, topN));
        }

        if (result == null || result.scores() == null || result.scores().isEmpty()) {
            log.warn("LLM reranking returned no scores, falling back to input order");
            return new ArrayList<>(candidates.subList(0, topN));
        }

        return result.scores().stream()
                .filter(s -> s.index() >= 1 && s.index() <= candidates.size())
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topN)
                .map(s -> {
                    Document original = candidates.get(s.index() - 1);
                    return Document.builder()
                            .id(original.getId())
                            .text(original.getText())
                            .metadata(original.getMetadata())
                            .score(s.score() / 10.0)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS);
    }
}
