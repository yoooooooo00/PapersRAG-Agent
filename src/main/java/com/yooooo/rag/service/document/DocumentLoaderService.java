package com.yooooo.rag.service.document;

import com.yooooo.rag.service.loader.DocumentParser;
import com.yooooo.rag.service.loader.ParseResult;
import java.io.InputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Loads literature documents. The project currently supports PDF only.
 */
@Service
@Slf4j
public class DocumentLoaderService {
    private final DocumentParser pdfParser;

    public DocumentLoaderService(List<DocumentParser> parserList) {
        this.pdfParser = parserList.stream()
                .filter(parser -> "PDF".equalsIgnoreCase(parser.supportedType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("PDF parser is not configured"));
        log.info("Loaded PDF document parser");
    }

    public ParseResult load(InputStream inputStream, String fileName) {
        String fileType = detectFileType(fileName);
        if (!"PDF".equals(fileType)) {
            log.warn("[DocumentLoader] Unsupported file type={} fileName={}", fileType, fileName);
            return ParseResult.failure("Unsupported file type: " + fileType + ". Only PDF is supported.");
        }

        log.info("[DocumentLoader] Start parsing PDF fileName={}", fileName);
        long start = System.currentTimeMillis();

        ParseResult result = pdfParser.parse(inputStream, fileName);

        long elapsed = System.currentTimeMillis() - start;
        if (result.isSuccess()) {
            log.info("[DocumentLoader] Parsed PDF fileName={} pages={} elapsed={}ms",
                    fileName, result.getTotalPages(), elapsed);
        } else {
            log.warn("[DocumentLoader] Failed to parse PDF fileName={} reason={}",
                    fileName, result.getErrorMsg());
        }

        return result;
    }

    private String detectFileType(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "UNKNOWN";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "UNKNOWN";
        }
        return fileName.substring(dotIndex + 1).toUpperCase();
    }
}