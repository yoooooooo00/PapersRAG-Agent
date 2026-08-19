# Current RAG Project Spec

Last updated: 2026-08-18

This document records the current backend structure of the RAG project before the literature-Agent refactor. It is intended as a working map for later modification.

## 1. Project Overview

Current project type:

- Java 21
- Spring Boot 3.5.11
- Spring AI 1.1.2
- PostgreSQL + pgvector
- Redis
- MinIO
- Sa-Token authentication
- Maven project

Current package root:

- `com.yooooo.rag`

Current product shape:

- Enterprise-style knowledge-base RAG backend.
- Supports knowledge-base management, document upload, indexing, retrieval, RAG QA, streaming chat, feedback, statistics, and evaluation.

Target refactor direction:

- Personal literature Agent.
- Keep RAG pipeline and document processing.
- Replace enterprise permission and demo-login semantics with single-user/personal literature semantics.

## 2. Runtime And Infrastructure

### 2.1 Main Config

File:

- `src/main/resources/application.yml`

Important config groups:

- `spring.datasource`: PostgreSQL connection.
- `spring.ai.openai.chat`: chat model endpoint and options.
- `spring.ai.openai.embedding`: embedding model endpoint.
- `spring.data.redis`: Redis connection.
- `minio`: object storage config.
- `reranker`: external reranker config.
- `rag.parser.pdf`: PDF crop header/footer config.
- `rag.chunk`: chunk size, overlap, structure-aware switch.
- `rag.retrieval`: vector/full-text top-k and min-score.
- `rag.context`: max context tokens.
- `rag.cache`: embedding/query cache settings.
- `rag.demo-data.enabled`: currently default false; controls demo data initialization.
- `rag.cache.query-enabled`: currently default false; controls query result cache.
- `sa-token`: authentication settings.
- `management`: actuator/Prometheus settings.

### 2.2 Docker Compose

File:

- `docker-compose.yml`

Services:

- `postgres`: `pgvector/pgvector:pg16`, exposes `5432`.
- `minio`: stores original documents, exposes `9000` and console `9001`.

Note:

- Redis is configured in application.yml but is not included in the current docker-compose file.

## 3. Database Schema

File:

- `src/main/resources/schema.sql`

### 3.1 `kb_knowledge_base`

Purpose:

- Stores knowledge-base/project metadata.

Columns:

- `id BIGSERIAL PRIMARY KEY`
- `name VARCHAR(100) NOT NULL`
- `description TEXT`
- `department_id VARCHAR(50) NOT NULL`
- `is_public BOOLEAN NOT NULL DEFAULT FALSE`
- `created_by BIGINT NOT NULL`
- `created_at TIMESTAMP NOT NULL DEFAULT NOW()`
- `updated_at TIMESTAMP NOT NULL DEFAULT NOW()`
- `is_deleted BOOLEAN NOT NULL DEFAULT FALSE`

Indexes:

- `idx_kb_department` on `department_id` where `is_deleted=false`

Literature refactor note:

- Keep table concept, but rename/reinterpret as research collection/topic library.
- `department_id` and `is_public` are enterprise-specific and should later be removed or ignored.

### 3.2 `kb_permission`

Purpose:

- Enterprise permission table for knowledge-base read/write/admin access.

Columns:

- `id`
- `kb_id`
- `subject_type`
- `subject_id`
- `permission`
- `granted_by`
- `granted_at`

Indexes/constraints:

- Unique `(kb_id, subject_type, subject_id)`
- `idx_permission_subject`

Literature refactor note:

- Candidate for removal after single-user mode is introduced.

### 3.3 `kb_document`

Purpose:

- Stores uploaded document metadata and indexing status.

Columns:

- `id`
- `kb_id`
- `file_name`
- `file_type`
- `file_size`
- `minio_path`
- `status`: `PENDING`, `PROCESSING`, `DONE`, `FAILED`
- `error_msg`
- `chunk_count`
- `token_count`
- `version`
- `uploaded_by`
- `uploaded_at`
- `indexed_at`
- `is_deleted`

Indexes:

- `idx_doc_kb_id`
- `idx_doc_status`

Literature refactor note:

- Keep as physical file/document record.
- Add or link a new `paper` table for academic metadata.

### 3.4 `kb_doc_chunk`

Purpose:

- Stores indexed text chunks, embeddings, and full-text search vector.

Columns:

- `id`
- `doc_id`
- `kb_id`
- `chunk_index`
- `content`
- `content_tsv`
- `embedding VECTOR(1024)`
- `page_num`
- `section_title`
- `token_count`
- `doc_version`
- `created_at`

Indexes:

- HNSW vector index on `embedding`
- GIN index on `content_tsv`
- `idx_chunk_kb_id`
- `idx_chunk_doc_id`

Literature refactor note:

- Keep, but add `paper_id`, `section_type`, and paragraph/caption metadata later.

### 3.5 `kb_index_task`

Purpose:

- Tracks async document indexing and reindexing tasks.

Columns:

- `id`
- `doc_id`
- `task_type`
- `status`: `PENDING`, `RUNNING`, `DONE`, `FAILED`
- `retry_count`
- `max_retry`
- `error_msg`
- `created_at`
- `started_at`
- `finished_at`

Literature refactor note:

- Keep. Paper parsing and metadata extraction can reuse this task mechanism.

### 3.6 `kb_chat_session`

Purpose:

- Stores user chat sessions.

Columns:

- `id VARCHAR(36) PRIMARY KEY`
- `user_id`
- `kb_ids TEXT`
- `title`
- `message_count`
- `created_at`
- `last_active_at`
- `is_deleted`

Literature refactor note:

- Keep. Later rename `kb_ids` concept to collection/paper scope.

### 3.7 `kb_chat_message`

Purpose:

- Stores user/assistant messages and response sources.

Columns:

- `id`
- `session_id`
- `role`
- `content`
- `sources JSONB`
- `latency_ms`
- `feedback`
- `created_at`

Literature refactor note:

- Keep. Later add mode/scope metadata if needed.

### 3.8 `kb_answer_feedback`

Purpose:

- Stores feedback on assistant answers.

Columns:

- `id`
- `message_id`
- `user_id`
- `feedback`
- `comment`
- `created_at`

Literature refactor note:

- Replace or merge into paper notes / answer correction workflow.

### 3.9 `kb_eval_dataset`

Purpose:

- Stores evaluation questions and expected answers/chunks.

Columns:

- `id`
- `kb_id`
- `question`
- `expected_answer`
- `expected_chunk_ids BIGINT[]`
- `created_by`
- `created_at`

Literature refactor note:

- Keep only if converted to literature QA/citation accuracy evaluation.

### 3.10 `kb_eval_result`

Purpose:

- Stores evaluation results.

Columns:

- `id`
- `dataset_id`
- `eval_version`
- `hit`
- `rank`
- `actual_answer`
- `faithfulness`
- `answer_relevancy`
- `eval_at`

Literature refactor note:

- Optional. Can become citation faithfulness evaluation.

## 4. Entity Classes

Path:

- `src/main/java/com/yooooo/rag/entity`

Entities:

- `KnowledgeBase`: maps `kb_knowledge_base`.
- `KbPermission`: maps `kb_permission`.
- `KbDocument`: maps `kb_document`; includes `DocumentStatus` enum.
- `DocChunk`: maps `kb_doc_chunk`; includes vector embedding.
- `IndexTask`: maps `kb_index_task`; includes `TaskStatus` enum and `canRetry()`.
- `ChatSession`: maps `kb_chat_session`.
- `ChatMessage`: maps `kb_chat_message`.
- `AnswerFeedback`: maps `kb_answer_feedback`.
- `EvalDataset`: maps `kb_eval_dataset`.
- `EvalResult`: maps `kb_eval_result`.

## 5. DTO Classes

Path:

- `src/main/java/com/yooooo/rag/dto`

DTOs:

- `ApiResponse<T>`: common response wrapper.
- `ChatRequest`: `question`, `kbIds`, `sessionId`.
- `RagQueryRequest`: `question`, `kbIds`.
- `RagResponse`: `answer`, `sources`, `latencyMs`, `notFound`.
- `RagResponse.Source`: `chunkId`, `docId`, `pageNum`, `sectionTitle`, `excerpt`, `score`, `docName`.
- `DocumentUploadResponse`: upload/index submission response.
- `IndexStatusResponse`: document indexing status response.
- `KnowledgeBaseCreateRequest`: `name`, `description`, `departmentId`, `isPublic`.
- `KnowledgeBaseVO`: list view with `permission`.
- `EvalReport`: evaluation summary/report.

Literature refactor note:

- Add `PaperCreateRequest`, `PaperUpdateRequest`, `PaperVO`, `PaperNoteRequest`, `PaperNoteVO`, `PaperCompareRequest`, and export DTOs.

## 6. Controllers And APIs

Path:

- `src/main/java/com/yooooo/rag/controller`

### 6.1 `AuthController`

Base path:

- `/api/v1/auth`

Endpoints:

- `POST /api/v1/auth/login`
  - Body: `username`, `password`
  - Uses hard-coded demo users: `hr001`, `tech001`, `admin`
  - Returns Sa-Token token.

- `POST /api/v1/auth/logout`
  - Logs out current user.

Refactor note:

- Replace with single-user local auth or remove for local-only usage.

### 6.2 `KnowledgeBaseController`

Base path:

- `/api/v1/kb`

Endpoints:

- `GET /api/v1/kb`
  - Lists accessible knowledge bases.

- `POST /api/v1/kb`
  - Creates knowledge base.
  - Body: `KnowledgeBaseCreateRequest`.

- `POST /api/v1/kb/{kbId}/documents`
  - Uploads a document file.
  - Multipart field: `file`.
  - Submits async indexing.

- `GET /api/v1/kb/{kbId}/documents/{docId}/status`
  - Returns indexing status.

- `GET /api/v1/kb/{kbId}/documents`
  - Lists documents under a knowledge base.

- `DELETE /api/v1/kb/{kbId}/documents/{docId}`
  - Soft-deletes a document.

- `GET /api/v1/kb/{kbId}/documents/{docId}/download`
  - Downloads original document from MinIO.

- `POST /api/v1/kb/{kbId}/documents/{docId}/reindex`
  - Submits reindex task.

Refactor note:

- Keep document APIs, but add paper metadata APIs around them.

### 6.3 `DocumentUpdateController`

Base path:

- `/api/v1/kb`

Endpoints:

- `PUT /api/v1/kb/{kbId}/documents/{docId}/content`
  - Replaces file content and reindexes.

- `POST /api/v1/kb/{kbId}/documents/{docId}/reindex-force`
  - Forces version bump and reindex.

Refactor note:

- Keep for paper PDF replacement.

### 6.4 `RagQueryController`

Base path:

- `/api/v1/rag`

Endpoints:

- `POST /api/v1/rag/query`
  - Body: `RagQueryRequest`
  - Runs full RAG pipeline.
  - Returns `ApiResponse<RagResponse>`.

Refactor note:

- Add literature mode and scope fields later, such as `paperIds`, `sectionTypes`, and `taskMode`.

### 6.5 `ChatController`

Base path:

- `/api/v1/chat`

Endpoints:

- `GET /api/v1/chat/stream`
  - Query params: `question`, `kbIds`, optional `sessionId`
  - Streams answer with SSE.

- `POST /api/v1/chat`
  - Body: `ChatRequest`
  - Synchronous chat answer.

- `GET /api/v1/chat/sessions`
  - Lists current user's chat sessions.

- `GET /api/v1/chat/sessions/{sessionId}/messages`
  - Lists messages in a session.

Refactor note:

- Keep. Later support paper-scoped and collection-scoped sessions.

### 6.6 `FeedbackController`

Base path:

- `/api/v1/feedback`

Endpoints:

- `POST /api/v1/feedback/{messageId}`
  - Params: `feedback`, optional `comment`.

Refactor note:

- Replace with paper note / answer correction flow.

### 6.7 `EvalController`

Base path:

- `/api/v1/eval`

Endpoints:

- `POST /api/v1/eval/{kbId}/run`
  - Runs evaluation.

- `GET /api/v1/eval/{kbId}/history`
  - Lists eval history/versions.

- `GET /api/v1/eval/{kbId}/dataset`
  - Lists eval dataset items.

- `POST /api/v1/eval/{kbId}/dataset`
  - Adds eval question.

- `PUT /api/v1/eval/{kbId}/dataset/{id}`
  - Updates eval question.

- `DELETE /api/v1/eval/{kbId}/dataset/{id}`
  - Deletes eval question.

- `GET /api/v1/eval/{kbId}/chunks`
  - Lists chunk summaries for selecting expected chunks.

Refactor note:

- Optional. Convert to literature QA and citation-evidence evaluation.

### 6.8 `StatsController`

Base path:

- `/api/v1/stats`

Endpoints:

- `GET /api/v1/stats/tokens`
  - Returns token usage and estimated CNY cost.

Refactor note:

- Optional. Can become paper processing/token cost dashboard.

## 7. Service Modules

Path:

- `src/main/java/com/yooooo/rag/service`

### 7.1 Chat

- `ChatSessionService`
  - Creates or reuses chat sessions.
  - Maintains session metadata and message counts.

### 7.2 Document

- `DocumentLoaderService`
  - Chooses parser by file extension.
  - Supports PDF, DOCX, MD, TXT.

- `DocumentUpdateService`
  - Replaces document file.
  - Bumps version and submits reindex.

### 7.3 Loader

- `DocumentParser`
  - Parser interface.

- `PdfParser`
  - Extracts PDF text by page with header/footer crop.

- `DocxParser`
  - Parses DOCX.

- `MarkdownParser`
  - Parses Markdown.

- `TxtParser`
  - Parses TXT.

- `ParseResult`
  - Structured parse result with pages and section titles.

Literature refactor note:

- Extend PDF parsing with academic section detection and reference extraction.

### 7.4 Splitter

- `ChunkConfig`
- `ChunkResult`
- `ChunkService`
- `ChunkSplitter`
- `SlidingWindowChunkSplitter`
- `StructureAwareChunkSplitter`

Purpose:

- Splits parsed documents into chunks.

Literature refactor note:

- Add section-aware academic chunking and `sectionType`.

### 7.5 Indexing

- `IndexService`
  - Parses, splits, embeds, and stores chunks.
  - Updates document status.

- `IndexTaskLauncher`
  - Runs async indexing tasks and retry flow.

Literature refactor note:

- Reuse for PDF indexing; add metadata extraction stage.

### 7.6 Embedding

- `EmbeddingService`
  - Calls configured embedding provider.
  - Uses 1024-dimensional vectors.

### 7.7 Retrieval

- `HybridRetrieverService`
  - Vector retrieval + full-text retrieval + RRF merge.

- `EnhancedRetrieverService`
  - Enhanced retrieval, including HyDE path for complex queries.

- `RerankerService`
  - Optional external reranking.

- `ConfidenceFilter`
  - Filters low-score results.

- `ContextTrimmerService`
  - Limits context token budget.

- `QueryNormalizerService`
  - Normalizes question for cache key.

- `QueryRewriterService`
  - Rewrites/expands query.

- `QueryRoutingService`
  - Classifies query as simple/standard/complex.

- `QueryCacheService`
  - Query result cache; currently disabled by default.

- `TsQueryBuilder`
  - Builds PostgreSQL full-text query.

Literature refactor note:

- Keep. Add filters by `paperId`, `sectionType`, `year`, `author`, and collection.

### 7.8 RAG

- `FullRagPipeline`
  - Normalizes query, checks cache, routes query, retrieves chunks, filters, trims context, generates answer, builds sources, optionally checks hallucination.

- `StreamingRagService`
  - Streaming and sync chat integration.

- `RagPromptTemplate`
  - Literature-oriented prompt after recent change.

- `CitationParser`
  - Extracts `[refN]` citations after recent change.

- `SourceBuilder`
  - Maps cited references to chunk/document source metadata.

- `HallucinationChecker`
  - Checks answer faithfulness against context.

Literature refactor note:

- Add task-mode prompts and section-aware source formatting.

### 7.9 Knowledge Base

- `KnowledgeBaseService`
  - Creates/list knowledge bases.
  - Uploads/deletes/reindexes documents.

Refactor note:

- Reinterpret as collection/project service.

### 7.10 Permission

- `PermissionService`
  - Enforces enterprise read/write access.

Refactor note:

- Remove or simplify after single-user mode.

### 7.11 Feedback / Eval / Metrics / Storage

- `FeedbackService`
  - Saves answer feedback.

- `EvalService`
  - Runs retrieval/answer evaluation.

- `TokenMetrics`
  - Tracks token usage and estimated cost.

- `MinioStorageService`
  - Upload/download/delete original files.

Refactor note:

- Storage stays. Feedback/eval/metrics are optional or should be adapted to literature workflows.

## 8. Repository Interfaces

Path:

- `src/main/java/com/yooooo/rag/repository`

Repositories:

- `KnowledgeBaseRepository`
- `KbPermissionRepository`
- `KbDocumentRepository`
- `DocChunkRepository`
- `IndexTaskRepository`
- `ChatSessionRepository`
- `ChatMessageRepository`
- `AnswerFeedbackRepository`
- `EvalDatasetRepository`
- `EvalResultRepository`

Important repository concern:

- `DocChunkRepository` contains vector similarity and full-text search queries.

Literature refactor note:

- Add `PaperRepository`, `PaperNoteRepository`, and `PaperRelationRepository`.
- Extend chunk queries to filter by paper and section type.

## 9. Security Modules

Path:

- `src/main/java/com/yooooo/rag/security`

Classes:

- `SecurityConfig`
  - Sa-Token servlet filter.
  - Requires login for `/api/**` except `/api/v1/auth/**` and `/actuator/**`.

- `KbAuthInterceptor`
  - Loads `userId`, `departmentId`, and `role` into `UserContext`.

- `UserContext`
  - Thread-local user context.

- `TraceFilter`
  - Trace/log correlation.

Refactor note:

- Keep trace filter.
- Simplify auth to single-user local mode.
- Remove department/role dependency.

## 10. Config Classes

Path:

- `src/main/java/com/yooooo/rag/config`

Classes:

- `AsyncConfig`: async executor config.
- `DataInitializer`: demo data import; currently disabled by default.
- `GlobalExceptionHandler`: API exception handling.
- `MinioConfig`: MinIO client config.
- `RedisConfig`: Redis template/config.
- `SpringAiConfig`: Spring AI client config.
- `WebMvcConfig`: MVC/interceptor config.

Refactor note:

- Keep most config.
- Later remove demo initializer entirely after paper sample/import flow exists.

## 11. Current Refactor Status

Completed soft changes:

- Demo data initialization is now controlled by `rag.demo-data.enabled`, default false.
- Query result cache is now controlled by `rag.cache.query-enabled`, default false.
- Prompt has been changed from enterprise KB assistant to literature assistant.
- Reference markers now use ASCII format: `[ref1]`, `(source: [ref1])`.

Known issue:

- Several existing source files contain garbled Chinese comments/strings from prior encoding mismatch. New changes should prefer ASCII in Java source unless the project encoding is normalized.

## 12. Recommended Next Code Changes

Immediate next step:

- Add `Paper` entity/table and connect it to `KbDocument`.

Suggested new tables:

- `paper`
- `paper_note`
- `paper_relation`

Suggested new API groups:

- `/api/v1/papers`
- `/api/v1/papers/{paperId}/notes`
- `/api/v1/papers/{paperId}/summary`
- `/api/v1/papers/compare`
- `/api/v1/papers/export`

Suggested migration strategy:

1. Add new paper tables without deleting old KB tables.
2. Make upload optionally create a paper record.
3. Add paper-scoped retrieval filters.
4. Add note APIs.
5. After MVP works, remove or simplify enterprise auth/permission modules.
## 13. Implemented Literature Agent Additions

Updated: 2026-08-19

### 13.1 New Entities

- `Paper`
  - Table: `paper`
  - Purpose: first-class academic paper metadata.
  - Fields include `kbId`, `docId`, `title`, `authors`, `year`, `venue`, `doi`, `arxivId`, `abstractText`, `keywords`, `bibtex`, `sourceUrl`, `pdfUrl`, `readingStatus`, `rating`, `note`, `createdBy`, timestamps, and soft delete flag.
  - Enum: `ReadingStatus`: `UNREAD`, `READING`, `READ`, `ARCHIVED`.

- `PaperNote`
  - Table: `paper_note`
  - Purpose: personal reading notes linked to a paper.
  - Fields include `paperId`, `noteType`, `content`, `pageNum`, `sectionTitle`, `linkedChunkId`, `tags`, timestamps, and soft delete flag.
  - Enum: `NoteType`: `SUMMARY`, `QUESTION`, `IDEA`, `QUOTE`, `CRITIQUE`, `TODO`.

- `PaperRelation`
  - Table: `paper_relation`
  - Purpose: paper-to-paper relation records.
  - Fields include `sourcePaperId`, `targetPaperId`, `relationType`, `evidenceChunkId`, `note`, `createdAt`, and soft delete flag.
  - Enum: `RelationType`: `CITES`, `EXTENDS`, `COMPARES_WITH`, `USES_METHOD`, `USES_DATASET`, `CONTRADICTS`, `SAME_TOPIC`.

### 13.2 New Repositories

- `PaperRepository`
- `PaperNoteRepository`
- `PaperRelationRepository`

### 13.3 New DTOs

- `PaperCreateRequest`
- `PaperUpdateRequest`
- `PaperVO`
- `PaperNoteRequest`
- `PaperNoteVO`
- `PaperRelationRequest`
- `PaperRelationVO`

### 13.4 New Service

- `PaperService`
  - Manages paper CRUD.
  - Manages paper notes.
  - Manages paper relations.
  - Validates paper status, note type, relation type, default KB, and optional document binding.

### 13.5 New Controller And APIs

Base path:

- `/api/v1/papers`

Paper endpoints:

- `GET /api/v1/papers`
  - Optional query params: `kbId`, `status`, `keyword`.
  - Returns paper list.

- `POST /api/v1/papers`
  - Body: `PaperCreateRequest`.
  - Creates a paper metadata record.

- `GET /api/v1/papers/{paperId}`
  - Returns paper detail.

- `PUT /api/v1/papers/{paperId}`
  - Body: `PaperUpdateRequest`.
  - Updates paper metadata.

- `DELETE /api/v1/papers/{paperId}`
  - Soft-deletes paper.

Paper note endpoints:

- `GET /api/v1/papers/{paperId}/notes`
- `POST /api/v1/papers/{paperId}/notes`
- `PUT /api/v1/papers/{paperId}/notes/{noteId}`
- `DELETE /api/v1/papers/{paperId}/notes/{noteId}`

Paper relation endpoints:

- `GET /api/v1/papers/{paperId}/relations`
- `POST /api/v1/papers/{paperId}/relations`
- `DELETE /api/v1/papers/{paperId}/relations/{relationId}`

### 13.6 Verification

- `mvn -q -DskipTests compile` passed after adding the first literature CRUD layer.

### 13.7 Next Recommended Step

Add paper upload flow:

- `POST /api/v1/papers/upload`
- Upload PDF through existing `KnowledgeBaseService.uploadDocument`.
- Create a `Paper` record bound to the created `KbDocument`.
- Use file name as initial paper title when metadata is not provided.

### 13.8 Paper Upload Endpoint

Updated: 2026-08-19

A literature-oriented upload wrapper has been added.

Endpoint:

- `POST /api/v1/papers/upload`

Request type:

- `multipart/form-data`

Required form field:

- `file`: uploaded paper file. The current document loader supports PDF, DOCX, MD, and TXT through the existing RAG pipeline.

Optional form fields:

- `kbId`: defaults to `1`.
- `title`: defaults to the uploaded file name without extension.
- `authors`
- `year`
- `venue`
- `doi`
- `arxivId`
- `abstractText`
- `keywords`
- `bibtex`
- `sourceUrl`
- `readingStatus`: `UNREAD`, `READING`, `READ`, or `ARCHIVED`.
- `rating`
- `note`

Behavior:

1. Reuses `KnowledgeBaseService.uploadDocument` to upload the file, create `KbDocument`, store the original file in MinIO, and submit an indexing task.
2. Creates a `Paper` row bound to the generated `KbDocument` through `paper.docId`.
3. Stores the MinIO path in `paper.pdfUrl` for now.
4. Returns `PaperUploadResponse` with `paperId`, `docId`, `kbId`, `title`, `fileName`, `documentStatus`, and message.

Example curl:

```bash
curl -X POST "http://localhost:8080/api/v1/papers/upload" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/paper.pdf" \
  -F "title=Example Paper" \
  -F "authors=Author A; Author B" \
  -F "year=2026" \
  -F "venue=arXiv" \
  -F "readingStatus=UNREAD"
```

Verification:

- `mvn -q -DskipTests compile` passed after adding this endpoint.

## 14. Upload-Parse-Index-Save Module Refactor

Updated: 2026-08-19

This section records the focused refactor of the upload, parsing, indexing, and database save path.

### 14.1 Module Boundary

This pass only changes the ingestion/indexing module:

- File upload
- MinIO storage
- `KbDocument` file/index record creation
- `Paper` metadata record creation during paper upload
- Async indexing task submission
- Parsing and chunking
- Embedding generation
- `DocChunk` save with literature metadata

It does not change the QA/retrieval module yet.

### 14.2 Current Upload Paths

Existing generic document upload still exists:

- `POST /api/v1/kb/{kbId}/documents`

Behavior:

- Upload file to MinIO.
- Create `KbDocument`.
- Submit indexing task.
- Does not create `Paper`.

Literature paper upload:

- `POST /api/v1/papers/upload`

Behavior after this refactor:

1. Validate target KB, default `kbId=1`.
2. Upload file to MinIO and create `KbDocument` through `KnowledgeBaseService.createDocumentRecord`.
3. Create and flush `Paper`, bound by `paper.docId = kb_document.id`.
4. Submit async indexing task only after `Paper` exists.
5. Return `PaperUploadResponse`.

### 14.3 Service Changes

`KnowledgeBaseService`:

- Added `createDocumentRecord(kbId, file)`.
- `createDocumentRecord` uploads to MinIO and saves `KbDocument`, but does not submit indexing.
- Existing `uploadDocument(kbId, file)` remains compatible and now calls `createDocumentRecord`, then submits indexing.

`PaperService`:

- `uploadPaper(...)` now uses the explicit order:
  - `createDocumentRecord`
  - `paperRepository.saveAndFlush`
  - `indexService.submitIndexTask`

Reason:

- Prevents a race where async indexing starts before the `Paper` row exists.
- Allows `IndexService` to resolve `paperId` by `docId` while saving chunks.

### 14.4 Chunk Save Changes

`DocChunk` now includes:

- `paperId`, mapped to `kb_doc_chunk.paper_id`
- `sectionType`, mapped to `kb_doc_chunk.section_type`

`IndexService` now:

- Resolves `paperId` from `PaperRepository.findFirstByDocIdAndIsDeletedFalse(docId)`.
- Writes `paperId` into each `DocChunk` when indexing a paper upload.
- Infers a lightweight `sectionType` from `sectionTitle` or the first non-empty content line.

Supported inferred section types:

- `ABSTRACT`
- `INTRODUCTION`
- `RELATED_WORK`
- `BACKGROUND`
- `METHOD`
- `EXPERIMENTS`
- `RESULTS`
- `DISCUSSION`
- `LIMITATIONS`
- `CONCLUSION`
- `REFERENCES`
- `APPENDIX`

### 14.5 Database Requirement

The database must contain these columns:

```sql
ALTER TABLE kb_doc_chunk ADD COLUMN IF NOT EXISTS paper_id BIGINT;
ALTER TABLE kb_doc_chunk ADD COLUMN IF NOT EXISTS section_type VARCHAR(50);
CREATE INDEX IF NOT EXISTS idx_chunk_paper_id ON kb_doc_chunk (paper_id);
CREATE INDEX IF NOT EXISTS idx_chunk_section_type ON kb_doc_chunk (section_type);
```

The generated `database_init_literature_agent.sql` and updated `schema.sql` already include these columns and indexes.

### 14.6 Verification

- `mvn -q -DskipTests compile` passed.

### 14.7 Next Module To Refactor

After this ingestion module is stable, the next isolated module should be paper-scoped retrieval/query:

- Add repository queries by `paper_id`.
- Add `LiteratureQueryRequest`.
- Add `/api/v1/literature/query`.
- Keep existing `/api/v1/rag/query` unchanged during the first pass.

## 15. PDF-Only Parser Refactor

Updated: 2026-08-19

This pass only changed the document parsing/upload file-type module.

### 15.1 Scope

Changed:

- Document parser registration/loading.
- Upload file type validation.
- Parser dependencies.
- Parser tests and test resources.

Not changed:

- QA pipeline.
- Retrieval logic.
- Chunk splitting strategy.
- Paper-scoped querying.

### 15.2 Removed Parsers

Removed from `src/main/java/com/yooooo/rag/service/loader`:

- `DocxParser`
- `MarkdownParser`
- `TxtParser`

Remaining parser files:

- `DocumentParser`
- `ParseResult`
- `PdfParser`

### 15.3 Dependency Cleanup

Removed from `pom.xml`:

- `org.apache.poi:poi-ooxml`
- `com.vladsch.flexmark:flexmark-all`

Kept:

- `org.apache.pdfbox:pdfbox`

### 15.4 Runtime Behavior

`DocumentLoaderService` now:

- Requires a configured PDF parser.
- Accepts only `.pdf` file names.
- Returns parse failure for any non-PDF file.

`KnowledgeBaseService` now:

- Allows upload only when the file name ends with `.pdf`.
- Stores uploaded files with `fileType = PDF`.
- Rejects DOCX, Markdown, TXT, and unknown extensions.

### 15.5 Test Cleanup

`DocumentLoaderServiceTest` now covers:

- PDF parsing.
- Non-PDF rejection.

Removed non-PDF test resources:

- Markdown test document.
- TXT test documents.

Kept PDF test resources:

- `src/main/resources/test-docs/policy.pdf`
- `src/test/resources/test-docs/policy.pdf`

### 15.6 Verification

Passed:

- `mvn -q -DskipTests compile`

Attempted:

- `mvn -q "-Dtest=DocumentLoaderServiceTest,ChunkServiceTest" test`

Result:

- Test execution failed while loading Spring ApplicationContext because the local test run could not obtain a JDBC connection to PostgreSQL.
- The failure is environmental/database connectivity related, not a Java compilation error from the PDF-only parser refactor.

## 16. PDF Academic Section Parsing Refactor

Updated: 2026-08-19

This pass only changed the PDF parsing and metadata propagation module.

### 16.1 Scope

Changed:

- PDF section heading detection.
- Parsed page metadata.
- Chunk metadata propagation.
- Index save behavior for `section_type`.

Not changed:

- QA endpoints.
- Retrieval queries.
- Prompt modes.
- Paper CRUD/upload APIs.

### 16.2 New Section Detector

Added:

- `AcademicSectionDetector`

Purpose:

- Detect common academic paper section headings from extracted text.
- Support common English and Chinese section names.

Supported section types:

- `ABSTRACT`
- `INTRODUCTION`
- `RELATED_WORK`
- `BACKGROUND`
- `METHOD`
- `EXPERIMENTS`
- `RESULTS`
- `DISCUSSION`
- `LIMITATIONS`
- `CONCLUSION`
- `REFERENCES`
- `APPENDIX`

### 16.3 Parse Metadata Changes

`ParseResult.PageContent` now includes:

- `sectionType`

`PdfParser` now:

- Extracts text by page as before.
- Detects section heading from the first several non-empty lines of each page.
- Carries the current section title/type across subsequent pages until a new section is detected.
- Extracts a lightweight paper title from the first page.

### 16.4 Chunk Metadata Changes

`ChunkResult` now includes:

- `sectionType`

`SlidingWindowChunkSplitter` now propagates:

- `sectionTitle`
- `sectionType`

`StructureAwareChunkSplitter` now groups by parser-provided section metadata instead of using the old garbled heading regex.

### 16.5 Index Save Changes

`IndexService` now:

- Saves `chunk.getSectionType()` when available.
- Falls back to local inference only when chunk section type is empty.

### 16.6 Verification

Passed:

- `mvn -q -DskipTests compile`
