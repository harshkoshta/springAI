package com.local.rag.controller;


import com.local.rag.ingestion.service.DocumentIngestionService;
import com.local.rag.ingestion.service.Chunking;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService service;
    private final Chunking chunking;

    public DocumentController(DocumentIngestionService service, Chunking chunking) {
        this.service = service;
        this.chunking = chunking;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        service.process(file);
        return Map.of(
                "status", "PROCESSING"
        );
    }


}
