package com.local.rag.controller;


import com.local.rag.dto.RagResponseDto;
import com.local.rag.retrival.DocumentRetrievalService;
import com.local.rag.service.HybridDocumentRetriever;
import com.local.rag.service.RagService;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class DocumentRAGController {

    private final RagService service;

    private final HybridDocumentRetriever retriever;

    public DocumentRAGController(RagService service, HybridDocumentRetriever retriever) {
        this.service = service;
        this.retriever = retriever;
    }

    @GetMapping("/ask")
    public RagResponseDto search(@RequestBody Map<String, String>  query) {
        System.out.println("Query : "+query.get("query"));
        RagResponseDto ask = service.ask(query.get("query"));
         return ask;
    }

    @GetMapping("/hybrid")
    public  List<Document> searchRetriver(@RequestBody Map<String, String>  query) {
        System.out.println("Query : "+query.get("query"));
        List<Document> ask = retriever.retrieve(Query.builder().text(query.get("query")).build());
        return ask;
    }
}