package com.local.rag.service;
import com.local.rag.dto.AnswerDto;
import com.local.rag.dto.RagResponseDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public RagResponseDto ask(String question) {

        // Step 1: Retrieve relevant chunks from OpenSearch
//        Get 1 document and extract heading from it
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(1)
                        .build()
        );
        List<String> headings = (List<String>)documents.get(0).getMetadata().get("headings");

        String filterText = headings.isEmpty() ? "" : headings.get(0);
        // extract all document with same heading metadata
         documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5 )
                        .filterExpression("headings.keyword == '" + filterText + "'")
                        .build()
        );


        // Step 2: Combine retrieved chunks into context
        String context = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        // Step 3: Send context + question to Gemini
        AnswerDto answer =  chatClient.prompt()
                .system("""
                        You are a question-answering assistant.

                        Answer the user's question using only the
                        provided context.

                        If the context does not contain the answer,
                        say: "I don't know based on the provided documents."

                        Do not invent information.
                        """)
                .user("""
                        Context:
                        %s

                        Question:
                        %s
                        """.formatted(context, question))
                .call()
                .entity(AnswerDto.class);
        return new RagResponseDto(answer,documents);
    }
}