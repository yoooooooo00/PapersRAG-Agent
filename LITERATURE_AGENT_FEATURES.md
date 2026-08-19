# Literature Agent Feature Plan

Last updated: 2026-08-18

This document describes the planned direction for turning the current RAG knowledge-base project into a personal literature Agent for academic reading, paper management, and writing support.

## 1. Product Direction

The future system should become a personal literature workspace rather than a generic enterprise knowledge base.

Core use cases:

- Collect papers from PDF, DOI, arXiv, BibTeX, and later Zotero.
- Read papers with reliable citations to page, section, and source chunks.
- Manage papers by project, topic, tag, reading status, notes, and relations.
- Use papers for survey writing, method comparison, related work drafting, and BibTeX/Markdown export.

Non-goals for the first stage:

- Multi-tenant enterprise permission management.
- Department-based knowledge-base sharing.
- Complex public/private knowledge-base workflows.
- Large frontend or collaboration features before the backend literature model is stable.

## 2. Features To Add

### 2.1 Paper Metadata

Add a first-class `Paper` model instead of treating every uploaded file as only a generic document.

Suggested fields:

- `id`
- `kbId` or `collectionId`
- `documentId`
- `title`
- `authors`
- `year`
- `venue`
- `doi`
- `arxivId`
- `abstractText`
- `keywords`
- `bibtex`
- `sourceUrl`
- `pdfUrl`
- `readingStatus`: `UNREAD`, `READING`, `READ`, `ARCHIVED`
- `rating`
- `createdAt`
- `updatedAt`

Purpose:

- Let the Agent answer paper-level questions.
- Support filtering and browsing by author, year, venue, topic, status.
- Support BibTeX and Markdown export.

### 2.2 Literature Collections

Keep the current `KnowledgeBase` concept, but rename or reinterpret it as one of:

- `LiteratureCollection`
- `ResearchProject`
- `TopicLibrary`

Suggested meaning:

- A collection represents one research project, paper topic, thesis chapter, or survey area.
- One paper can later belong to multiple collections if needed.

### 2.3 Structured Paper Parsing

The current PDF parser extracts text by page. Literature work needs stronger structure.

Add section detection for:

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

Suggested chunk fields to add:

- `paperId`
- `sectionType`
- `sectionTitle`
- `pageNum`
- `paragraphIndex`
- `isReferenceSection`
- `figureOrTableCaption`

Purpose:

- Better answers to questions such as “what is the method?”, “what are the limitations?”, and “what datasets were used?”.
- More precise survey and comparison generation.

### 2.4 Paper Notes

Add personal notes as a separate model. Do not mix user notes into paper source text.

Suggested `PaperNote` fields:

- `id`
- `paperId`
- `noteType`: `SUMMARY`, `QUESTION`, `IDEA`, `QUOTE`, `CRITIQUE`, `TODO`
- `content`
- `pageNum`
- `sectionTitle`
- `linkedChunkId`
- `tags`
- `createdAt`
- `updatedAt`

Purpose:

- Store personal reading thoughts.
- Let the Agent distinguish “paper says” from “my note says”.
- Support future writing workflows.

### 2.5 Citation Relations

Add paper-to-paper relation tracking.

Suggested `PaperRelation` fields:

- `id`
- `sourcePaperId`
- `targetPaperId`
- `relationType`: `CITES`, `EXTENDS`, `COMPARES_WITH`, `USES_METHOD`, `USES_DATASET`, `CONTRADICTS`, `SAME_TOPIC`
- `evidenceChunkId`
- `note`
- `createdAt`

Purpose:

- Build related-work maps.
- Support “which papers are close to this one?”
- Support survey organization by method or dataset.

### 2.6 Literature QA Modes

Add explicit task modes instead of using one generic RAG prompt for everything.

Suggested modes:

- `SINGLE_PAPER_QA`: ask questions about one paper.
- `PAPER_SUMMARY`: contribution, method, experiments, conclusion, limitations.
- `MULTI_PAPER_COMPARE`: compare several papers by problem, method, dataset, metrics, results.
- `RELATED_WORK`: generate related-work notes with citations.
- `METHOD_EXTRACTION`: extract algorithm, assumptions, inputs, outputs, training/inference details.
- `EXPERIMENT_EXTRACTION`: extract datasets, baselines, metrics, settings, results.
- `LIMITATION_ANALYSIS`: find limitations and future work.
- `WRITING_ASSISTANT`: draft paragraphs with traceable references.

### 2.7 Export

Add export endpoints later:

- Paper card as Markdown.
- Collection summary as Markdown.
- BibTeX export.
- Comparison table export as Markdown/CSV.
- Notes export for Obsidian or local files.

### 2.8 External Source Import

Later-stage integrations:

- DOI metadata lookup.
- arXiv metadata lookup.
- Semantic Scholar / OpenAlex metadata lookup.
- Zotero import/export.
- BibTeX import.

These need network access and rate-limit handling, so they should come after the local PDF workflow is stable.

## 3. Current Features To Keep

Keep these modules as the foundation:

- Document upload/download/reindex flow.
- PDF, DOCX, Markdown, TXT parser framework.
- Chunk splitting framework.
- PostgreSQL + pgvector storage.
- Full-text search and vector search.
- Hybrid retrieval and RRF merge.
- Reranker support.
- Query rewriting/routing/HyDE retrieval.
- Context trimming.
- Chat sessions and SSE streaming.
- Citation parser and source builder.
- Hallucination/faithfulness checker.
- MinIO original-file storage.

## 4. Current Features To Disable Or Replace

Already soft-disabled:

- Demo data initialization: `rag.demo-data.enabled=false` by default.
- Query result cache: `rag.cache.query-enabled=false` by default.
- Enterprise prompt replaced by literature prompt.

Replace later:

- Demo login users.
- Department and role-based permission model.
- `KnowledgeBase` enterprise semantics.
- Generic feedback into reading notes / answer correction.
- Generic eval into literature-specific citation and answer quality evaluation.

## 5. Suggested Implementation Roadmap

### Stage 1: Literature MVP

- Add `Paper` entity and table.
- Link uploaded PDF document to `Paper`.
- Add paper metadata create/update/list/detail endpoints.
- Improve paper section detection.
- Add literature prompt modes for summary and QA.
- Add Markdown/BibTeX export.

### Stage 2: Reading Workspace

- Add `PaperNote` entity and endpoints.
- Add note-aware QA.
- Add paper reading status and tags.
- Add collection-level paper browsing/filtering.

### Stage 3: Survey And Comparison Agent

- Add multi-paper comparison endpoint.
- Add related-work draft endpoint.
- Add method/experiment extraction endpoint.
- Add structured comparison table generation.

### Stage 4: Citation Graph And External Import

- Add `PaperRelation` entity.
- Extract reference section and cited papers.
- Add DOI/arXiv/BibTeX metadata import.
- Add Zotero integration if still useful.

## 6. Design Rules For The Agent

- Do not fabricate paper facts, authors, years, venues, datasets, numbers, or conclusions.
- Every substantive answer should cite source chunks.
- Separate source text, model analysis, and personal notes.
- Prefer section-aware retrieval over generic whole-document retrieval.
- Preserve academic terms, variables, metrics, and datasets.
- When evidence is missing, say so explicitly.
## 7. Implementation Progress

Updated: 2026-08-19

Completed in the first implementation pass:

- Added `Paper` entity, repository, DTOs, service logic, and REST endpoints.
- Added `PaperNote` entity, repository, DTOs, service logic, and REST endpoints.
- Added `PaperRelation` entity, repository, DTOs, service logic, and REST endpoints.
- Updated local database initialization files so the default seed data is `Papers Library` instead of enterprise demo knowledge bases.
- Generated `database_init_literature_agent.sql` for manual Linux Docker database initialization.
- Verified Java compilation with `mvn -q -DskipTests compile`.

Next step:

- Add PDF upload endpoint for papers and bind each uploaded file to a `Paper` record.

Updated: 2026-08-19

Completed upload wrapper:

- Added `POST /api/v1/papers/upload`.
- The endpoint uploads a file through the existing RAG document pipeline and automatically creates a bound `Paper` record.
- If no title is provided, the file name without extension is used as the paper title.
- Indexing still happens asynchronously through the existing `KbDocument` and `IndexTask` flow.

Updated: 2026-08-19

Completed focused ingestion/indexing refactor:

- Paper upload now creates the file record first, then creates the `Paper`, then submits indexing.
- Indexed chunks now store `paperId` when they come from a paper upload.
- Indexed chunks now store a lightweight inferred `sectionType` for future paper-scoped retrieval.
- Generic document upload remains compatible with the original RAG behavior.

The QA module has not been refactored in this pass.

Updated: 2026-08-19

Completed PDF-only parser refactor:

- Removed DOCX, Markdown, and TXT parsers.
- Removed DOCX/Markdown parser dependencies.
- Upload validation now accepts only PDF files.
- `DocumentLoaderService` now routes only PDF documents.
- Non-PDF test resources were removed, leaving PDF parser coverage only.

The QA module was not changed in this pass.

Updated: 2026-08-19

Completed PDF academic section parsing pass:

- Added `AcademicSectionDetector`.
- PDF parsing now detects common academic section types.
- Page and chunk metadata now carry `sectionType`.
- Indexing now saves parser-provided `sectionType` before falling back to inference.

The QA module was not changed in this pass.

Updated: 2026-08-19

Completed PDF metadata extraction pass:

- Added `PaperMetadataExtractor` for lightweight metadata extraction from parsed PDFs.
- Indexing now backfills missing `Paper` fields after parsing and before chunking.
- Current extracted fields: title, abstract, keywords, DOI, arXiv ID, year, and simple author candidates.
- User-provided metadata is preserved; title is replaced only when it still looks like the uploaded file name or an untitled placeholder.

The QA module was not changed in this pass.

Updated: 2026-08-19

Completed PDF table preservation pass:

- PDF parsing now detects table-like row groups and preserves them as Markdown tables.
- Preserved tables are wrapped with `[TABLE]` and `[/TABLE]` markers.
- Page, chunk, and database metadata now carry `contentType`.
- Indexed chunks can now be filtered as `TEXT` or `TABLE` later.
- This pass keeps original table evidence and does not generate LLM table summaries yet.

Manual SQL for an existing database:

```sql
ALTER TABLE kb_doc_chunk ADD COLUMN IF NOT EXISTS content_type VARCHAR(50);
CREATE INDEX IF NOT EXISTS idx_chunk_content_type ON kb_doc_chunk (content_type);
```

The QA module was not changed in this pass.
