package com.yooooo.rag.service.document;

import com.yooooo.rag.service.loader.GrobidMetadataClient;
import com.yooooo.rag.service.loader.ParseResult;
import com.yooooo.rag.service.loader.PdfParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Loads PDF content with PDFBox and enriches paper metadata with GROBID.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentLoaderService {
    private final PdfParser pdfBoxParser;
    private final GrobidMetadataClient grobidMetadataClient;

    public ParseResult load(InputStream inputStream, String fileName) {
        String fileType = detectFileType(fileName);
        if (!"PDF".equals(fileType)) {
            log.warn("[DocumentLoader] Unsupported file type={} fileName={}", fileType, fileName);
            return ParseResult.failure("Unsupported file type: " + fileType + ". Only PDF is supported.");
        }

        long start = System.currentTimeMillis();
        try {
            byte[] bytes = inputStream.readAllBytes();
            ParseResult result = pdfBoxParser.parse(new ByteArrayInputStream(bytes), fileName);
            attachMetadata(result, bytes, fileName);
            logResult("PDFBOX", result, fileName, start);
            return result;
        } catch (Exception e) {
            log.error("[DocumentLoader] Failed to read PDF fileName={} reason={}", fileName, e.getMessage(), e);
            return ParseResult.failure("PDF loading failed: " + e.getMessage());
        }
    }

    private void attachMetadata(ParseResult result, byte[] bytes, String fileName) {
        if (result == null || !result.isSuccess() || !grobidMetadataClient.isEnabled()) return;
        ParseResult.PaperMetadata metadata = grobidMetadataClient.extract(bytes, fileName);
        if (metadata == null) return;
        result.setPaperMetadata(metadata);
        if ((result.getTitle() == null || result.getTitle().isBlank())
                && metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            result.setTitle(metadata.getTitle());
        }
    }
    private void logResult(String parser, ParseResult result, String fileName, long start) {
        long elapsed = System.currentTimeMillis() - start;
        if (result.isSuccess()) {
            log.info("[DocumentLoader] parser={} fileName={} pages={} elapsed={}ms",
                    parser, fileName, result.getTotalPages(), elapsed);
        } else {
            log.warn("[DocumentLoader] parser={} failed fileName={} reason={}",
                    parser, fileName, result.getErrorMsg());
        }
    }

    private String detectFileType(String fileName) {
        if (fileName == null || fileName.isBlank()) return "UNKNOWN";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex < 0 || dotIndex == fileName.length() - 1
                ? "UNKNOWN" : fileName.substring(dotIndex + 1).toUpperCase();
    }
}