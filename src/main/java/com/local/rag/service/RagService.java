package com.local.rag.service;
import com.local.rag.dto.AnswerDto;
import com.local.rag.dto.ChatResponseDto;
import com.local.rag.dto.RagResponseDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;

    public RagService(
            VectorStore vectorStore,
            ChatClient.Builder chatClientBuilder
    ) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.chatClientBuilder = chatClientBuilder;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
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

    /**
     * Conversational RAG: remembers prior turns per conversationId and
     * compresses follow-up questions into standalone queries before retrieval.
     */
    public ChatResponseDto chat(String conversationId, String question) {

        Advisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.20)
                        .vectorStore(vectorStore)
                        .build())
                // Rewrites "what about X?" follow-ups into standalone queries
                // using the conversation history before retrieval.
                .queryTransformers(CompressionQueryTransformer.builder()
                        .chatClientBuilder(chatClientBuilder)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .promptTemplate(new PromptTemplate("""
                                Use the following document context and the conversation
                                history to answer the question.

                                You may use the conversation history for questions about
                                the conversation itself (e.g. "what did I ask before").

                                If neither the context nor the history contains the
                                answer, say "I don't know based on the provided documents."
                                Do not invent information.

                                Context:
                                {context}

                                Question:
                                {query}
                                """))
                        .build())
                .build();

        String answer = chatClient.prompt()
                .user(question)
                .advisors(memoryAdvisor, retrievalAugmentationAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        return new ChatResponseDto(conversationId, answer);
    }


}