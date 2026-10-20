package com.local.rag.service;
import com.local.rag.dto.AnswerDto;
import com.local.rag.dto.RagResponseDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;

    public RagService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.chatClientBuilder = chatClientBuilder;
    }

    public RagResponseDto ask(String question) {

        // new code
        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.20)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false)
                        .build())
//                .queryTransformers(RewriteQueryTransformer.builder()
//                        .chatClientBuilder(chatClientBuilder)
//                        .build())
//                .queryTransformers(TranslationQueryTransformer.builder()
//                        .chatClientBuilder(chatClientBuilder)
//                        .targetLanguage("english")
//                        .build())
                .queryExpander(MultiQueryExpander.builder().numberOfQueries(3).chatClientBuilder(chatClientBuilder).includeOriginal(true).build())
                .build();

        AnswerDto answer = chatClient.prompt()
//                .system("""
////                        You are a question-answering assistant.
////
////                        Answer the user's question using only the
////                        provided context.
////
////                        If the context does not contain the answer,
////                        say: "I don't know based on the provided documents."
////
////                        Do not invent information.
////                        """)
                .user(question)
                .advisors(retrievalAugmentationAdvisor)
                .call()

                .entity(AnswerDto.class);
        return new RagResponseDto(answer,null);
    }


}