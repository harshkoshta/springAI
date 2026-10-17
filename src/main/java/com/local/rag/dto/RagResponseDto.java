package com.local.rag.dto;

import org.springframework.ai.document.Document;

import java.util.List;

public record RagResponseDto(
        AnswerDto answer,
        List<Document> sources
) {
}
