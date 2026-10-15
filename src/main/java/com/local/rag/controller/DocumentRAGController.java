package com.local.rag.controller;


import com.local.rag.retrival.DocumentRetrievalService;
import com.local.rag.service.RagService;
import org.springframework.ai.document.Document;
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

    public DocumentRAGController(RagService service) {
        this.service = service;
    }

    @GetMapping("/ask")
    public Map<String, String> search(@RequestBody String query) {
        String ask = service.ask(query);
        System.out.println("Ask : "+ask);
         return Map.of("answer", ask);
    }
}