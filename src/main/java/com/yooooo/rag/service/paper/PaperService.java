package com.yooooo.rag.service.paper;

import com.yooooo.rag.dto.PaperCreateRequest;
import com.yooooo.rag.dto.PaperNoteRequest;
import com.yooooo.rag.dto.PaperNoteVO;
import com.yooooo.rag.dto.PaperRelationRequest;
import com.yooooo.rag.dto.PaperRelationVO;
import com.yooooo.rag.dto.PaperUpdateRequest;
import com.yooooo.rag.dto.PaperUploadResponse;
import com.yooooo.rag.dto.PaperVO;
import com.yooooo.rag.entity.Paper;
import com.yooooo.rag.entity.PaperNote;
import com.yooooo.rag.entity.PaperRelation;
import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.exception.BizException;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.repository.KnowledgeBaseRepository;
import com.yooooo.rag.repository.PaperNoteRepository;
import com.yooooo.rag.repository.PaperRelationRepository;
import com.yooooo.rag.repository.PaperRepository;
import com.yooooo.rag.security.UserContext;
import com.yooooo.rag.service.kb.KnowledgeBaseService;
import com.yooooo.rag.service.indexing.IndexService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaperService {
    private final PaperRepository paperRepository;
    private final PaperNoteRepository noteRepository;
    private final PaperRelationRepository relationRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KbDocumentRepository documentRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IndexService indexService;


    public PaperUploadResponse uploadPaper(
            MultipartFile file,
            String title,
            String authors,
            String affiliations,
            Integer year,
            String venue,
            String doi,
            String arxivId,
            String abstractText,
            String keywords,
            String bibtex,
            String sourceUrl,
            String readingStatus,
            Integer rating,
            String note) {
        Long targetKbId = knowledgeBaseService.ensurePersonalKnowledgeBase().getId();
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("Paper file must not be empty");
        }

        KbDocument doc = knowledgeBaseService.createDocumentRecord(targetKbId, file);
        Paper paper = new Paper();
        paper.setKbId(targetKbId);
        paper.setDocId(doc.getId());
        paper.setTitle(resolvePaperTitle(title, doc.getFileName()));
        paper.setAuthors(authors);
        paper.setAffiliations(affiliations);
        paper.setYear(year);
        paper.setVenue(venue);
        paper.setDoi(doi);
        paper.setArxivId(arxivId);
        paper.setAbstractText(abstractText);
        paper.setKeywords(keywords);
        paper.setBibtex(bibtex);
        paper.setSourceUrl(sourceUrl);
        paper.setPdfUrl(doc.getMinioPath());
        paper.setReadingStatus(parseReadingStatusOrDefault(readingStatus, Paper.ReadingStatus.UNREAD));
        paper.setRating(rating);
        paper.setNote(note);
        paper.setCreatedBy(UserContext.getUserId());

        Paper saved = paperRepository.saveAndFlush(paper);
        indexService.submitIndexTask(doc.getId());
        log.info("[Paper] uploaded paperId={} docId={} fileName={}", saved.getId(), doc.getId(), doc.getFileName());
        return PaperUploadResponse.builder()
                .paperId(saved.getId())
                .docId(doc.getId())
                .kbId(targetKbId)
                .title(saved.getTitle())
                .fileName(doc.getFileName())
                .documentStatus(doc.getStatus().name())
                .message("Paper uploaded and indexing task submitted")
                .build();
    }
    public List<PaperVO> list(Long kbId, String status, String keyword) {
        List<Paper> papers;
        if (keyword != null && !keyword.isBlank()) {
            papers = paperRepository.findByTitleContainingIgnoreCaseAndIsDeletedFalseOrderByUpdatedAtDesc(keyword.strip());
        } else if (status != null && !status.isBlank()) {
            papers = paperRepository.findByReadingStatusAndIsDeletedFalseOrderByUpdatedAtDesc(parseReadingStatus(status));
        } else if (kbId != null) {
            papers = paperRepository.findByKbIdAndIsDeletedFalseOrderByUpdatedAtDesc(kbId);
        } else {
            papers = paperRepository.findByIsDeletedFalseOrderByUpdatedAtDesc();
        }
        return papers.stream().map(this::toVO).toList();
    }

    public PaperVO get(Long paperId) {
        return toVO(requirePaper(paperId));
    }

    @Transactional
    public PaperVO create(PaperCreateRequest req) {
        validateCreate(req);
        validateDocument(req.getDocId());

        Long targetKbId = resolveKbId(req.getKbId());

        Paper paper = new Paper();
        paper.setKbId(targetKbId);
        paper.setDocId(req.getDocId());
        paper.setTitle(req.getTitle().strip());
        paper.setAuthors(req.getAuthors());
        paper.setAffiliations(req.getAffiliations());
        paper.setYear(req.getYear());
        paper.setVenue(req.getVenue());
        paper.setDoi(req.getDoi());
        paper.setArxivId(req.getArxivId());
        paper.setAbstractText(req.getAbstractText());
        paper.setKeywords(req.getKeywords());
        paper.setBibtex(req.getBibtex());
        paper.setSourceUrl(req.getSourceUrl());
        paper.setPdfUrl(req.getPdfUrl());
        paper.setReadingStatus(parseReadingStatusOrDefault(req.getReadingStatus(), Paper.ReadingStatus.UNREAD));
        paper.setRating(req.getRating());
        paper.setNote(req.getNote());
        paper.setCreatedBy(UserContext.getUserId());

        Paper saved = paperRepository.saveAndFlush(paper);
        log.info("[Paper] created paperId={} title={}", saved.getId(), saved.getTitle());
        return toVO(saved);
    }

    @Transactional
    public PaperVO update(Long paperId, PaperUpdateRequest req) {
        Paper paper = requirePaper(paperId);

        if (req.getKbId() != null) {
            paper.setKbId(resolveKbId(req.getKbId()));
        }
        if (req.getDocId() != null) {
            validateDocument(req.getDocId());
            paper.setDocId(req.getDocId());
        }
        if (req.getTitle() != null) {
            if (req.getTitle().isBlank()) {
                throw BizException.badRequest("Paper title must not be empty");
            }
            paper.setTitle(req.getTitle().strip());
        }
        if (req.getAuthors() != null) paper.setAuthors(req.getAuthors());
        if (req.getAffiliations() != null) paper.setAffiliations(req.getAffiliations());
        if (req.getYear() != null) paper.setYear(req.getYear());
        if (req.getVenue() != null) paper.setVenue(req.getVenue());
        if (req.getDoi() != null) paper.setDoi(req.getDoi());
        if (req.getArxivId() != null) paper.setArxivId(req.getArxivId());
        if (req.getAbstractText() != null) paper.setAbstractText(req.getAbstractText());
        if (req.getKeywords() != null) paper.setKeywords(req.getKeywords());
        if (req.getBibtex() != null) paper.setBibtex(req.getBibtex());
        if (req.getSourceUrl() != null) paper.setSourceUrl(req.getSourceUrl());
        if (req.getPdfUrl() != null) paper.setPdfUrl(req.getPdfUrl());
        if (req.getReadingStatus() != null) paper.setReadingStatus(parseReadingStatus(req.getReadingStatus()));
        if (req.getRating() != null) paper.setRating(req.getRating());
        if (req.getNote() != null) paper.setNote(req.getNote());

        Paper saved = paperRepository.saveAndFlush(paper);
        log.info("[Paper] updated paperId={}", saved.getId());
        return toVO(saved);
    }

    @Transactional
    public void delete(Long paperId) {
        Paper paper = requirePaper(paperId);
        paper.setIsDeleted(true);
        paperRepository.save(paper);
        log.info("[Paper] deleted paperId={}", paperId);
    }

    public List<PaperNoteVO> listNotes(Long paperId) {
        requirePaper(paperId);
        return noteRepository.findByPaperIdAndIsDeletedFalseOrderByUpdatedAtDesc(paperId)
                .stream().map(this::toNoteVO).toList();
    }

    @Transactional
    public PaperNoteVO addNote(Long paperId, PaperNoteRequest req) {
        requirePaper(paperId);
        validateNoteRequest(req);

        PaperNote note = new PaperNote();
        note.setPaperId(paperId);
        note.setNoteType(parseNoteTypeOrDefault(req.getNoteType(), PaperNote.NoteType.SUMMARY));
        note.setContent(req.getContent().strip());
        note.setPageNum(req.getPageNum());
        note.setSectionTitle(req.getSectionTitle());
        note.setLinkedChunkId(req.getLinkedChunkId());
        note.setTags(req.getTags());
        return toNoteVO(noteRepository.save(note));
    }

    @Transactional
    public PaperNoteVO updateNote(Long paperId, Long noteId, PaperNoteRequest req) {
        requirePaper(paperId);
        PaperNote note = requireNote(paperId, noteId);

        if (req.getNoteType() != null) note.setNoteType(parseNoteType(req.getNoteType()));
        if (req.getContent() != null) {
            if (req.getContent().isBlank()) throw BizException.badRequest("Note content must not be empty");
            note.setContent(req.getContent().strip());
        }
        if (req.getPageNum() != null) note.setPageNum(req.getPageNum());
        if (req.getSectionTitle() != null) note.setSectionTitle(req.getSectionTitle());
        if (req.getLinkedChunkId() != null) note.setLinkedChunkId(req.getLinkedChunkId());
        if (req.getTags() != null) note.setTags(req.getTags());

        return toNoteVO(noteRepository.save(note));
    }

    @Transactional
    public void deleteNote(Long paperId, Long noteId) {
        requirePaper(paperId);
        PaperNote note = requireNote(paperId, noteId);
        note.setIsDeleted(true);
        noteRepository.save(note);
    }

    public List<PaperRelationVO> listRelations(Long paperId) {
        requirePaper(paperId);
        return relationRepository.findBySourcePaperIdAndIsDeletedFalseOrderByCreatedAtDesc(paperId)
                .stream().map(this::toRelationVO).toList();
    }

    @Transactional
    public PaperRelationVO addRelation(Long paperId, PaperRelationRequest req) {
        requirePaper(paperId);
        validateRelationRequest(req);
        requirePaper(req.getTargetPaperId());

        PaperRelation relation = new PaperRelation();
        relation.setSourcePaperId(paperId);
        relation.setTargetPaperId(req.getTargetPaperId());
        relation.setRelationType(parseRelationType(req.getRelationType()));
        relation.setEvidenceChunkId(req.getEvidenceChunkId());
        relation.setNote(req.getNote());
        return toRelationVO(relationRepository.save(relation));
    }

    @Transactional
    public void deleteRelation(Long paperId, Long relationId) {
        requirePaper(paperId);
        PaperRelation relation = relationRepository.findById(relationId)
                .orElseThrow(() -> BizException.notFound("Paper relation not found"));
        if (!paperId.equals(relation.getSourcePaperId()) || Boolean.TRUE.equals(relation.getIsDeleted())) {
            throw BizException.notFound("Paper relation not found");
        }
        relation.setIsDeleted(true);
        relationRepository.save(relation);
    }


    private String resolvePaperTitle(String title, String fileName) {
        if (title != null && !title.isBlank()) {
            return title.strip();
        }
        if (fileName == null || fileName.isBlank()) {
            return "Untitled Paper";
        }
        String normalized = fileName.strip();
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex > 0) {
            return normalized.substring(0, dotIndex);
        }
        return normalized;
    }
    private Paper requirePaper(Long paperId) {
        return paperRepository.findById(paperId)
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> BizException.notFound("Paper not found"));
    }

    private PaperNote requireNote(Long paperId, Long noteId) {
        return noteRepository.findById(noteId)
                .filter(n -> paperId.equals(n.getPaperId()))
                .filter(n -> !Boolean.TRUE.equals(n.getIsDeleted()))
                .orElseThrow(() -> BizException.notFound("Paper note not found"));
    }

    private void validateCreate(PaperCreateRequest req) {
        if (req == null || req.getTitle() == null || req.getTitle().isBlank()) {
            throw BizException.badRequest("Paper title must not be empty");
        }
    }

    private void validateNoteRequest(PaperNoteRequest req) {
        if (req == null || req.getContent() == null || req.getContent().isBlank()) {
            throw BizException.badRequest("Note content must not be empty");
        }
    }

    private void validateRelationRequest(PaperRelationRequest req) {
        if (req == null || req.getTargetPaperId() == null) {
            throw BizException.badRequest("Target paper id must not be empty");
        }
        if (req.getRelationType() == null || req.getRelationType().isBlank()) {
            throw BizException.badRequest("Relation type must not be empty");
        }
    }

    private void validateKb(Long kbId) {
        if (kbId == null) {
            throw BizException.badRequest("Knowledge base id must not be empty");
        }
        kbRepository.findById(kbId)
                .filter(kb -> !Boolean.TRUE.equals(kb.getIsDeleted()))
                .orElseThrow(() -> BizException.notFound("Knowledge base not found"));
    }

    private Long resolveKbId(Long kbId) {
        if (kbId != null) {
            validateKb(kbId);
            return kbId;
        }
        return knowledgeBaseService.ensurePersonalKnowledgeBase().getId();
    }

    private void validateDocument(Long docId) {
        if (docId == null) {
            return;
        }
        documentRepository.findById(docId)
                .filter(doc -> !Boolean.TRUE.equals(doc.getIsDeleted()))
                .orElseThrow(() -> BizException.notFound("Document not found"));
    }

    private Paper.ReadingStatus parseReadingStatusOrDefault(String value, Paper.ReadingStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return parseReadingStatus(value);
    }

    private Paper.ReadingStatus parseReadingStatus(String value) {
        try {
            return Paper.ReadingStatus.valueOf(value.strip().toUpperCase());
        } catch (Exception e) {
            throw BizException.badRequest("Invalid reading status: " + value);
        }
    }

    private PaperNote.NoteType parseNoteTypeOrDefault(String value, PaperNote.NoteType fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return parseNoteType(value);
    }

    private PaperNote.NoteType parseNoteType(String value) {
        try {
            return PaperNote.NoteType.valueOf(value.strip().toUpperCase());
        } catch (Exception e) {
            throw BizException.badRequest("Invalid note type: " + value);
        }
    }

    private PaperRelation.RelationType parseRelationType(String value) {
        try {
            return PaperRelation.RelationType.valueOf(value.strip().toUpperCase());
        } catch (Exception e) {
            throw BizException.badRequest("Invalid relation type: " + value);
        }
    }

    private PaperVO toVO(Paper paper) {
        return PaperVO.builder()
                .id(paper.getId())
                .kbId(paper.getKbId())
                .docId(paper.getDocId())
                .title(paper.getTitle())
                .authors(paper.getAuthors())
                .affiliations(paper.getAffiliations())
                .year(paper.getYear())
                .venue(paper.getVenue())
                .doi(paper.getDoi())
                .arxivId(paper.getArxivId())
                .abstractText(paper.getAbstractText())
                .keywords(paper.getKeywords())
                .bibtex(paper.getBibtex())
                .sourceUrl(paper.getSourceUrl())
                .pdfUrl(paper.getPdfUrl())
                .readingStatus(paper.getReadingStatus() != null ? paper.getReadingStatus().name() : null)
                .rating(paper.getRating())
                .note(paper.getNote())
                .createdBy(paper.getCreatedBy())
                .createdAt(paper.getCreatedAt())
                .updatedAt(paper.getUpdatedAt())
                .build();
    }

    private PaperNoteVO toNoteVO(PaperNote note) {
        return PaperNoteVO.builder()
                .id(note.getId())
                .paperId(note.getPaperId())
                .noteType(note.getNoteType() != null ? note.getNoteType().name() : null)
                .content(note.getContent())
                .pageNum(note.getPageNum())
                .sectionTitle(note.getSectionTitle())
                .linkedChunkId(note.getLinkedChunkId())
                .tags(note.getTags())
                .createdAt(note.getCreatedAt())
                .updatedAt(note.getUpdatedAt())
                .build();
    }

    private PaperRelationVO toRelationVO(PaperRelation relation) {
        return PaperRelationVO.builder()
                .id(relation.getId())
                .sourcePaperId(relation.getSourcePaperId())
                .targetPaperId(relation.getTargetPaperId())
                .relationType(relation.getRelationType() != null ? relation.getRelationType().name() : null)
                .evidenceChunkId(relation.getEvidenceChunkId())
                .note(relation.getNote())
                .createdAt(relation.getCreatedAt())
                .build();
    }
}