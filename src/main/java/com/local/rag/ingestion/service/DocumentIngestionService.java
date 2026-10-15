package com.local.rag.ingestion.service;


import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int ingest(Resource pdfResource) {

        // Step 1: Extract PDF text
        PagePdfDocumentReader reader =
                new PagePdfDocumentReader(pdfResource);

        List<Document> pages = reader.read();

        // Step 2: Split into chunks
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(5000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(pages);

        // Step 3: Generate embeddings and index
        vectorStore.add(chunks);

        return chunks.size();
    }
}