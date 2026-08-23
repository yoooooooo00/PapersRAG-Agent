package com.yooooo.rag.controller;

import com.yooooo.rag.dto.ApiResponse;
import com.yooooo.rag.dto.PaperCreateRequest;
import com.yooooo.rag.dto.PaperNoteRequest;
import com.yooooo.rag.dto.PaperNoteVO;
import com.yooooo.rag.dto.PaperRelationRequest;
import com.yooooo.rag.dto.PaperRelationVO;
import com.yooooo.rag.dto.PaperUpdateRequest;
import com.yooooo.rag.dto.PaperUploadResponse;
import com.yooooo.rag.dto.PaperVO;
import com.yooooo.rag.service.paper.PaperService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/papers")
@RequiredArgsConstructor
public class PaperController {
    private final PaperService paperService;


    @PostMapping("/upload")
    public ApiResponse<PaperUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String authors,
            @RequestParam(required = false) String affiliations,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String venue,
            @RequestParam(required = false) String doi,
            @RequestParam(required = false) String arxivId,
            @RequestParam(required = false) String abstractText,
            @RequestParam(required = false) String keywords,
            @RequestParam(required = false) String bibtex,
            @RequestParam(required = false) String sourceUrl,
            @RequestParam(required = false) String readingStatus,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String note) {
        return ApiResponse.ok(paperService.uploadPaper(
                file, title, authors, affiliations, year, venue, doi, arxivId,
                abstractText, keywords, bibtex, sourceUrl, readingStatus, rating, note));
    }
    @GetMapping
    public ApiResponse<List<PaperVO>> list(
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(paperService.list(kbId, status, keyword));
    }

    @PostMapping
    public ApiResponse<PaperVO> create(@RequestBody PaperCreateRequest request) {
        return ApiResponse.ok(paperService.create(request));
    }

    @GetMapping("/{paperId}")
    public ApiResponse<PaperVO> get(@PathVariable Long paperId) {
        return ApiResponse.ok(paperService.get(paperId));
    }

    @PutMapping("/{paperId}")
    public ApiResponse<PaperVO> update(
            @PathVariable Long paperId,
            @RequestBody PaperUpdateRequest request) {
        return ApiResponse.ok(paperService.update(paperId, request));
    }

    @DeleteMapping("/{paperId}")
    public ApiResponse<Void> delete(@PathVariable Long paperId) {
        paperService.delete(paperId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{paperId}/notes")
    public ApiResponse<List<PaperNoteVO>> listNotes(@PathVariable Long paperId) {
        return ApiResponse.ok(paperService.listNotes(paperId));
    }

    @PostMapping("/{paperId}/notes")
    public ApiResponse<PaperNoteVO> addNote(
            @PathVariable Long paperId,
            @RequestBody PaperNoteRequest request) {
        return ApiResponse.ok(paperService.addNote(paperId, request));
    }

    @PutMapping("/{paperId}/notes/{noteId}")
    public ApiResponse<PaperNoteVO> updateNote(
            @PathVariable Long paperId,
            @PathVariable Long noteId,
            @RequestBody PaperNoteRequest request) {
        return ApiResponse.ok(paperService.updateNote(paperId, noteId, request));
    }

    @DeleteMapping("/{paperId}/notes/{noteId}")
    public ApiResponse<Void> deleteNote(
            @PathVariable Long paperId,
            @PathVariable Long noteId) {
        paperService.deleteNote(paperId, noteId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{paperId}/relations")
    public ApiResponse<List<PaperRelationVO>> listRelations(@PathVariable Long paperId) {
        return ApiResponse.ok(paperService.listRelations(paperId));
    }

    @PostMapping("/{paperId}/relations")
    public ApiResponse<PaperRelationVO> addRelation(
            @PathVariable Long paperId,
            @RequestBody PaperRelationRequest request) {
        return ApiResponse.ok(paperService.addRelation(paperId, request));
    }

    @DeleteMapping("/{paperId}/relations/{relationId}")
    public ApiResponse<Void> deleteRelation(
            @PathVariable Long paperId,
            @PathVariable Long relationId) {
        paperService.deleteRelation(paperId, relationId);
        return ApiResponse.ok(null);
    }
}