package com.local.rag.controller;


import com.local.rag.retrival.DocumentRetrievalService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/retrieval")
public class DocumentRetrievalController {

    private final DocumentRetrievalService service;

    public DocumentRetrievalController(DocumentRetrievalService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public List<Document> search(@RequestBody String query) {
        return service.search(query);
    }
}