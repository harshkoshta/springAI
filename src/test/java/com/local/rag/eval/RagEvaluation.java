package com.local.rag.eval;

import com.local.rag.dto.RagResponseDto;
import com.local.rag.service.HybridDocumentRetriever;
import com.local.rag.service.RagService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline RAG evaluation. Runs the golden cases in
 * {@code src/test/resources/evals/rag-eval-cases.json} against the live
 * retrieval and answer pipelines (requires Gemini + OpenSearch to be running).
 *
 * Run with: {@code mvn test -Drag.eval=true -Dtest=RagEvaluation}
 * Optional: {@code -Drag.eval.minPassRate=0.8}
 * Report:   {@code target/rag-eval/report.json}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
@ActiveProfiles("test")
class RagEvaluation {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluation.class);

    private static final double MIN_PASS_RATE =
            Double.parseDouble(System.getProperty("rag.eval.minPassRate", "0.8"));

    private static final List<String> REFUSAL_MARKERS = List.of(
            "don't know", "don’t know", "do not know", "not contain", "does not contain", "doesn't contain"
    );

    private static final double JUDGE_PASS_THRESHOLD = 0.6;

    record EvalCase(
            String id,
            String question,
            String referenceAnswer,
            List<String> expectedSources,
            List<String> expectedKeywords,
            boolean outOfScope
    ) {}

    record JudgeScore(int score, String reason) {}

    record CaseResult(
            String id,
            String question,
            boolean retrievalHit,
            double keywordCoverage,
            double semanticScore,
            double faithfulnessScore,
            boolean refused,
            boolean passed,
            List<String> retrievedFiles,
            String answer
    ) {}

    record Report(
            int totalCases,
            double retrievalRecall,
            double keywordCoverage,
            double avgSemanticScore,
            double avgFaithfulness,
            double refusalRate,
            double passRate,
            double minPassRate,
            List<CaseResult> results
    ) {}

    @Autowired
    private RagService ragService;

    @Autowired
    private HybridDocumentRetriever retriever;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    private ChatClient judgeClient() {
        return chatClientBuilder.build();
    }

    @Test
    void evaluateGoldenCases() throws IOException {
        List<EvalCase> cases = loadCases();
        Assumptions.assumeFalse(cases.isEmpty(),
                "No eval cases in evals/rag-eval-cases.json - add cases to run the evaluation");

        List<CaseResult> results = cases.stream().map(this::evaluate).toList();
        Report report = buildReport(results);

        Path reportPath = Path.of("target", "rag-eval", "report.json");
        Files.createDirectories(reportPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        log.info("RAG eval: cases={} retrievalRecall={} keywordCoverage={} semanticScore={} faithfulness={} refusalRate={} passRate={} (report: {})",
                report.totalCases(), report.retrievalRecall(), report.keywordCoverage(),
                report.avgSemanticScore(), report.avgFaithfulness(),
                report.refusalRate(), report.passRate(), reportPath.toAbsolutePath());

        results.stream().filter(r -> !r.passed()).forEach(r ->
                log.warn("FAILED {} - semantic={} faithful={} question='{}' retrievedFiles={} answer='{}'",
                        r.id(), r.semanticScore(), r.faithfulnessScore(),
                        r.question(), r.retrievedFiles(), r.answer()));

        assertThat(report.passRate())
                .as("RAG eval pass rate (see %s)", reportPath.toAbsolutePath())
                .isGreaterThanOrEqualTo(MIN_PASS_RATE);
    }

    private CaseResult evaluate(EvalCase evalCase) {
        List<Document> retrieved = retriever.retrieve(Query.builder().text(evalCase.question()).build());
        List<String> retrievedFiles = retrieved.stream()
                .map(d -> Objects.toString(d.getMetadata().get("fileName"), ""))
                .distinct()
                .toList();

        boolean retrievalHit = evalCase.expectedSources().isEmpty()
                || evalCase.expectedSources().stream().anyMatch(retrievedFiles::contains);

        RagResponseDto response = ragService.ask(evalCase.question());
        String answer = response != null && response.answer() != null && response.answer().answer() != null
                ? response.answer().answer()
                : "";
        String normalized = answer.toLowerCase(Locale.ROOT);

        double keywordCoverage = evalCase.expectedKeywords().isEmpty()
                ? 1.0
                : (double) evalCase.expectedKeywords().stream()
                        .filter(k -> normalized.contains(k.toLowerCase(Locale.ROOT)))
                        .count() / evalCase.expectedKeywords().size();

        boolean refused = REFUSAL_MARKERS.stream().anyMatch(normalized::contains);

        double semantic = (!evalCase.outOfScope() && evalCase.referenceAnswer() != null)
                ? semanticScore(evalCase.question(), answer, evalCase.referenceAnswer())
                : 1.0;

        double faithfulness = (!evalCase.outOfScope() && !retrieved.isEmpty())
                ? faithfulnessScore(answer, retrieved)
                : 1.0;

        boolean passed = retrievalHit && (evalCase.outOfScope()
                ? refused
                : semantic >= JUDGE_PASS_THRESHOLD && faithfulness >= JUDGE_PASS_THRESHOLD);

        return new CaseResult(evalCase.id(), evalCase.question(), retrievalHit, keywordCoverage,
                semantic, faithfulness, refused, passed, retrievedFiles, answer);
    }

    private double semanticScore(String question, String answer, String referenceAnswer) {
        try {
            JudgeScore result = judgeClient().prompt()
                    .user("""
                            You are an answer quality evaluator.

                            Question: %s

                            Reference answer: %s

                            Actual answer: %s

                            Score the actual answer from 1 (completely wrong or irrelevant) \
                            to 5 (fully correct, complete, and semantically equivalent to the reference).
                            Respond with JSON only: {"score": <1-5>, "reason": "<one sentence>"}
                            """.formatted(question, referenceAnswer, answer))
                    .call()
                    .entity(JudgeScore.class);
            double normalized = result != null ? result.score() / 5.0 : 0.0;
            log.debug("Semantic score={} reason={}", normalized, result != null ? result.reason() : "null");
            return normalized;
        } catch (Exception e) {
            log.warn("Semantic scoring failed, defaulting to 0.0: {}", e.getMessage());
            return 0.0;
        }
    }

    private double faithfulnessScore(String answer, List<Document> retrievedDocs) {
        try {
            String context = retrievedDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
            JudgeScore result = judgeClient().prompt()
                    .user("""
                            You are a faithfulness evaluator. Check whether every factual claim \
                            in the answer is supported by the provided context.

                            Context (retrieved documents):
                            %s

                            Answer to evaluate:
                            %s

                            Score from 1 (answer contains facts not in the context / hallucinated) \
                            to 5 (every claim in the answer is directly supported by the context).
                            Respond with JSON only: {"score": <1-5>, "reason": "<one sentence>"}
                            """.formatted(context, answer))
                    .call()
                    .entity(JudgeScore.class);
            double normalized = result != null ? result.score() / 5.0 : 0.0;
            log.debug("Faithfulness score={} reason={}", normalized, result != null ? result.reason() : "null");
            return normalized;
        } catch (Exception e) {
            log.warn("Faithfulness scoring failed, defaulting to 0.0: {}", e.getMessage());
            return 0.0;
        }
    }

    private Report buildReport(List<CaseResult> results) {
        double retrievalRecall = average(results.stream().map(r -> r.retrievalHit() ? 1.0 : 0.0));
        double keywordCoverage = average(results.stream().map(CaseResult::keywordCoverage));
        double avgSemantic     = average(results.stream().map(CaseResult::semanticScore));
        double avgFaithfulness = average(results.stream().map(CaseResult::faithfulnessScore));
        double refusalRate     = average(results.stream().map(r -> r.refused() ? 1.0 : 0.0));
        double passRate        = average(results.stream().map(r -> r.passed() ? 1.0 : 0.0));

        return new Report(results.size(), retrievalRecall, keywordCoverage,
                avgSemantic, avgFaithfulness, refusalRate, passRate, MIN_PASS_RATE, results);
    }

    private double average(java.util.stream.Stream<Double> values) {
        return values.mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private List<EvalCase> loadCases() throws IOException {
        try (InputStream in = new ClassPathResource("evals/rag-eval-cases.json").getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<EvalCase>>() {});
        }
    }
}
