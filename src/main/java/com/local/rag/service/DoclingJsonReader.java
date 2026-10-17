package com.local.rag.service;


import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class DoclingJsonReader {

    private final ObjectMapper objectMapper;

    public DoclingJsonReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Document> read(Path jsonPath) throws IOException {

        JsonNode root = objectMapper.readTree(jsonPath.toFile());

        String fileName = root.path("fileName").asText();
        String documentId = root.path("documentId").asText();

        JsonNode chunks = root.path("chunks");

        if (!chunks.isArray()) {
            throw new IOException("Missing chunks array: " + jsonPath);
        }

        List<Document> documents = new ArrayList<>();

        for (JsonNode chunk : chunks) {

            String chunkId = chunk.path("chunkId").asText();
            String text = chunk.path("text").asText();

            if (chunkId.isBlank() || text.isBlank()) {
                throw new IOException("Invalid chunk in " + jsonPath);
            }

            Map<String, Object> metadata = new HashMap<>();

            metadata.put("documentId", documentId);
            metadata.put("fileName", fileName);
            metadata.put("pageId", chunk.path("pageId").isNull()
                    ? -1
                    : chunk.path("pageId").asInt());
            metadata.put("chunkIndex",
                    chunk.path("chunkIndex").asInt());

            metadata.put("totalChunksOnPage",
                    chunk.path("totalChunksOnPage").asInt());

            metadata.put("headings",
                    objectMapper.convertValue(
                            chunk.path("headings"),
                            List.class
                    ));

            metadata.put("pageNumbers",
                    objectMapper.convertValue(
                            chunk.path("pageNumbers"),
                            List.class
                    ));

            metadata.put("elementIds",
                    objectMapper.convertValue(
                            chunk.path("elementIds"),
                            List.class
                    ));

            Document document = Document.builder()
                    .id(chunkId)
                    .text(text)
                    .metadata(metadata)
                    .build();

            documents.add(document);
        }

        return documents;
    }
}