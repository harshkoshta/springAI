package com.local.rag.ingestion.service;

import com.local.nativespace.PythonExecutor;
import com.local.rag.service.DoclingJsonReader;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class Chunking {

    private final PythonExecutor pythonExecutor;
    private final DoclingJsonReader jsonReader;

    public Chunking(
            PythonExecutor pythonExecutor,
            DoclingJsonReader jsonReader) {

        this.pythonExecutor = pythonExecutor;
        this.jsonReader = jsonReader;
    }

    public CompletableFuture<List<Document>> chunk(
            MultipartFile file) throws IOException {

        Resource resource = file.getResource();

        String jobId = UUID.randomUUID().toString();

        Path directory = Path.of("uploads");
        Files.createDirectories(directory);

        Path pdfPath = directory.resolve(jobId + ".pdf");

        try (InputStream input = resource.getInputStream()) {
            Files.copy(input, pdfPath);
        }

        Path absolutePdfPath =
                pdfPath.toAbsolutePath().normalize();

        // Your Python script writes a .json file
        // next to the input PDF.
        Path jsonPath = absolutePdfPath.resolveSibling(
                jobId + ".json"
        );

        return pythonExecutor
                .processPdf(absolutePdfPath.toString())
                .thenApply(result -> {

                    try {
                        return jsonReader.read(jsonPath);
                    } catch (IOException e) {
                        throw new java.util.concurrent
                                .CompletionException(e);
                    }
                });
    }
}