package com.local.rag.controller;


import com.local.rag.ingestion.service.DocumentIngestionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService service;

    public DocumentController(DocumentIngestionService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file
    ) throws IOException {

        int chunks = service.ingest(
                file.getResource()
        );

        return Map.of(
                "status", "INDEXED",
                "chunks", chunks
        );
    }


    @GetMapping(            value = "/upload"    )
    public Map<String, Object> getupload() throws IOException {
        System.out.println("Executed Upload");
        Resource resource =
                new ClassPathResource("documents/sample.pdf");
        int chunks = service.ingest(
                resource
        );

        return Map.of(
                "status", "INDEXED",
                "chunks", chunks
        );
    }
}