# Enterprise Knowledge Intelligence Platform (EKIP) — Requirements & Implementation Phases

Stack: Java 21, Spring Boot, Spring AI, OpenSearch, LangGraph4j (later).

**Primary objective:** learn RAG and implement it correctly. The project is RAG-first and learning-focused. Enterprise infrastructure (PostgreSQL, auth, Kafka, Redis, Grafana) is deferred.

---

## 1. Learning Rule (every phase)

- **Why:** why is this component needed?
- **How:** how does it work internally?
- **Implementation:** how to implement it in Spring AI?
- **Verification:** how to prove it works?

---

## 2. Core Architecture Decisions

- **Language/Framework:** Java 21, Spring Boot, Spring AI as the primary AI abstraction.
- **Search engine:** OpenSearch for lexical (BM25) and vector (k-NN) retrieval.
- **Metadata (initially):** stored in OpenSearch alongside chunks. No PostgreSQL in Phase 1.
- **Object storage:** local filesystem first; MinIO/S3 later.
- **etcd:** not used. It is a coordination store, not a vector database.
- **Orchestration:** LangGraph4j, introduced only after baseline RAG works (Phase 4).
- **Deployment:** Docker Compose locally; Kubernetes only if warranted.
- **Flows:** ingestion and query are independent. Ingestion uses plain Spring services. LangGraph is for query orchestration only.
- **Retained data:** original text, page provenance, chunk offsets, embedding model/version, source provenance, so results are explainable and reproducible.

### Primary pipeline (first milestone)

```
PDF -> Spring AI PDF Reader -> Text + page metadata
    -> TokenTextSplitter / paragraph-aware chunking
    -> Spring AI EmbeddingModel
    -> OpenSearch VectorStore (text + vector + metadata)
```

Out of scope for now: LLM answers, LangGraph, security, caching, dashboards, microservices.

---

## 3. Functional Requirements

| ID | Requirement | Phase |
|----|-------------|-------|
| FR-1 | Read text-based PDF with page provenance | 1 |
| FR-2 | Inspect extracted text and metadata | 1 |
| FR-3 | Token-based chunking with overlap | 1 |
| FR-4 | Generate embeddings via Spring AI | 1 |
| FR-5 | Index chunks, vectors, metadata in OpenSearch | 1 |
| FR-6 | Verify indexed records and vector dimension | 1 |
| FR-7 | k-NN, BM25 and hybrid retrieval | 2 |
| FR-8 | Grounded answers with citations (ChatClient) | 3 |
| FR-9 | LangGraph4j query graph (validate, retrieve, grade, generate, verify) | 4 |
| FR-10 | Multiple chunking strategies, reranking, evaluation | 5 |
| FR-11 | TXT, DOCX, Markdown, advanced parsing (Tika) | 6 |
| FR-12 | Observability, performance, resilience | 7 |
| FR-13 | JWT auth, document ACL, Kafka, Redis, deployment | 8 (optional) |

## 4. Non-Functional Requirements

- Do not log full document text, prompts, API keys or private data by default.
- Secrets (API keys) via environment variables only.
- Chunking must be measurable with a fixed Q/A evaluation dataset.
- Enforce document permissions in retrieval (not just the controller) before multi-user use.
- Metrics and structured logs added progressively; full observability in Phase 7.

---

## 5. Chunk Metadata Schema

Spring AI `TokenTextSplitter` copies source metadata. The application must explicitly manage `chunk_index`, `document_version`, `content_hash`, offsets, and embedding version.

```json
{
  "chunk_id": "doc101-v1-c007",
  "document_id": "doc101",
  "document_version": 1,
  "file_name": "architecture.pdf",
  "page_start": 4,
  "page_end": 5,
  "section_title": "Retry Policy",
  "chunk_index": 7,
  "chunking_strategy": "PARAGRAPH_TOKEN",
  "chunk_size_tokens": 500,
  "overlap_tokens": 75,
  "embedding_model": "configured-model",
  "embedding_version": "v1",
  "content_hash": "sha256-value",
  "content": "...",
  "embedding": [0.12, 0.35, -0.21]
}
```

## 6. Chunking Strategies

| Strategy | Trade-off |
|----------|-----------|
| Fixed characters | Simple, breaks meaning |
| Line-by-line | Poor for wrapped PDF text; not a default |
| Paragraph | Preserves context, variable size |
| Token-based | Predictable model input |
| Recursive | Strong general baseline |
| Semantic | More compute, complex |
| Parent-child | Better context, more storage |

Start: Spring AI `TokenTextSplitter` baseline, then custom paragraph-aware + token-budgeted chunking with overlap; compare retrieval quality.

## 7. Parsing Tools

| Tool | Use | Decision |
|------|-----|----------|
| `PagePdfDocumentReader` | Text PDFs with page provenance | First choice |
| `ParagraphPdfDocumentReader` | PDFs with catalog structure | Experiment |
| Apache PDFBox | Low-level fallback | Fallback |
| Apache Tika | Multi-format | Phase 6 |
| OCR / layout-aware parser | Scanned PDFs, tables | Later |

## 8. Spring AI: Automatic vs Manual

- **Automatic:** PDF read, token splitting, metadata copy, embedding generation, `VectorStore.add()`.
- **Manual:** heading/table extraction, chunk strategy choice, business metadata, grounding guarantees, retrieval evaluation, tenant access control.

---

## 9. Dependencies (use Spring Boot and Spring AI BOMs)

| Artifact | Purpose | Phase |
|----------|---------|-------|
| `spring-boot-starter-web` | Trigger ingestion via API | 1 |
| `spring-ai-pdf-document-reader` | PDF extraction | 1 |
| `spring-ai-starter-model-openai` (or other embedding model) | Embeddings | 1 |
| `spring-ai-starter-vector-store-opensearch` | OpenSearch VectorStore | 1 |
| `spring-boot-starter-actuator` | Health/metrics | 1 |
| `spring-boot-starter-test` | Tests | 1 |
| `langgraph4j-core` (verify coordinates) | Graph orchestration | 4 |
| `spring-ai-tika-document-reader` | More formats | 6 |
| `micrometer-registry-prometheus` | Metrics | 7 |
| `spring-boot-starter-security`, JPA, PostgreSQL, Flyway | Production extension | 8 |

---

## 10. Implementation Phases

### Phase 1 — PDF -> Parse -> Chunk -> Embed -> OpenSearch (current focus)

**Tasks**
- [ ] Create minimal Spring Boot application
- [ ] Run single-node OpenSearch locally (Docker Compose)
- [ ] Read one text-based PDF with Spring AI
- [ ] Inspect extracted text and page metadata
- [ ] Apply `TokenTextSplitter` and inspect chunks
- [ ] Generate embeddings via Spring AI
- [ ] Store chunks, embeddings, metadata in OpenSearch
- [ ] Verify indexed records and vector dimensions

**Definition of done** (illustrative values)
```
PDF: rag-paper.pdf
Pages extracted: 12
Chunks generated: 48
Embedding dimension: 1536
OpenSearch indexed chunks: 48
```
Inspect the OpenSearch index to confirm data is actually stored. Inspect chunks before generating embeddings.

**Endpoints (Phase 1):** `POST /api/v1/documents` (upload/ingest), `GET /api/v1/documents/{id}/chunks` (inspect), `GET /actuator/health`.

### Phase 2 — Retrieval
- OpenSearch k-NN, BM25, hybrid search (may need native client and search pipeline)
- `POST /api/v1/search`
- Done when: relevant chunks returned with scores and page metadata.

### Phase 3 — RAG answers
- Spring AI `ChatClient`, prompt construction, context injection
- `POST /api/v1/chat` returning answer + citations
- Done when: answers are grounded and cite document/page; "insufficient evidence" case handled.

### Phase 4 — LangGraph4j agentic RAG
- Typed state: question, conversationId, tenantId, retrievedChunks, relevanceScores, retryCount, answer, citations, errors
- Nodes: validate, retrieve, grade, generate, verify
- Bounded retry with query rewriting; insufficient-evidence fallback
- Later: tool calling, human approval, streaming, persistent checkpoints

### Phase 5 — Retrieval quality
- Multiple chunking strategies, parent-child, reranking
- Fixed Q/A evaluation set; metrics: Recall@K, citation accuracy, faithfulness

### Phase 6 — Multi-format documents
- TXT, DOCX, Markdown via Tika; advanced parsing, OCR/layout (optional)

### Phase 7 — Observability, performance, resilience
- Micrometer + Prometheus, OpenTelemetry + Tempo, Grafana, OpenSearch Dashboards
- Token/cost metrics, load testing, retries/timeouts

### Phase 8 — Optional production extension
- JWT auth, document ACL enforced in retrieval, PostgreSQL metadata, Kafka/Redis async jobs (202 + job ID), Kubernetes

---

## 11. Full API Roadmap

| Endpoint | Purpose | Phase |
|----------|---------|-------|
| `POST /api/v1/documents` | Upload document | 1 |
| `GET /api/v1/documents/{id}/chunks` | Inspect chunks | 1 |
| `POST /api/v1/search` | Retrieve chunks | 2 |
| `POST /api/v1/chat` | Grounded answer | 3 |
| `POST /api/v1/documents/{id}/reindex` | Reprocess | 5+ |
| `DELETE /api/v1/documents/{id}` | Delete doc + index data | 5+ |
| `GET /api/v1/jobs/{id}` | Job status | 8 |
| `GET /actuator/health`, `/actuator/prometheus` | Health/metrics | 1 / 7 |

## 12. Test Documents

1. Attention Is All You Need (Transformer paper)
2. Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks
3. Retrieval-Augmented Generation for Large Language Models: A Survey (later, longer-doc tests)

Start with the first two.

## 13. Final Engineering Decisions

1. Spring AI first for readers, splitters, models, VectorStore.
2. OpenSearch stores chunks and embeddings; PostgreSQL only when needed (Phase 8).
3. LangGraph4j after baseline retrieval works.
4. Hybrid retrieval after vector search is understood.
5. Chunking is evaluated, not assumed.
6. Security enforced at retrieval before multi-user use.
7. Observability grows incrementally.
