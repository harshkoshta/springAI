package com.local.rag.ingestion.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final Chunking chunking;

    public DocumentIngestionService(VectorStore vectorStore, Chunking chunking) {
        this.vectorStore = vectorStore;
        this.chunking = chunking;
    }

    public CompletableFuture<Integer> process(MultipartFile file) throws IOException {
        return chunking.chunk(file)
                .thenApply(this::index);
    }
    public int index(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        vectorStore.add(documents);
        return documents.size();
    }
}
