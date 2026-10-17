package com.local.rag.dto;

import java.util.List;

public record AnswerDto(
        String answer,
        String confidence,
        List<String> keyPoints
) {}