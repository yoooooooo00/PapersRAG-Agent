package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import com.yooooo.rag.service.splitter.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Splits parsed document content into searchable text chunks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkService {
    private final SlidingWindowChunkSplitter slidingWindowSplitter;
    private final StructureAwareChunkSplitter structureAwareSplitter;

    @Value("${rag.chunk.size:1200}")
    private int defaultChunkSize;

    @Value("${rag.chunk.overlap:150}")
    private int defaultOverlap;

    @Value("${rag.chunk.structure-aware:true}")
    private boolean defaultStructureAware;

    public List<ChunkResult> chunk(ParseResult parseResult) {
        ChunkConfig config = ChunkConfig.builder()
                .chunkSize(defaultChunkSize)
                .chunkOverlap(defaultOverlap)
                .structureAware(defaultStructureAware)
                .build();

        return chunk(parseResult, config);
    }

    private void validateConfig(ChunkConfig config) {
        if (config == null) throw new IllegalArgumentException("Chunk config must not be null");
        if (config.getChunkSize() < 20) throw new IllegalArgumentException("Chunk size must be at least 20 characters");
        if (config.getChunkOverlap() < 0 || config.getChunkOverlap() >= config.getChunkSize()) {
            throw new IllegalArgumentException("Chunk overlap must be >= 0 and smaller than chunk size");
        }
    }

    public List<ChunkResult> chunk(ParseResult parseResult, ChunkConfig config) {
        if (parseResult == null || !parseResult.isSuccess()) {
            return List.of();
        }

        validateConfig(config);

        ChunkSplitter splitter = config.isStructureAware()
                ? structureAwareSplitter
                : slidingWindowSplitter;

        List<ChunkResult> chunks = splitter.split(parseResult, config);

        chunks = chunks.stream()
                .filter(c -> c.getContent().length() >= 20)
                .toList();

        log.info("[ChunkService] chunking completed strategy={} chunks={} totalChars={}",
                splitter.getClass().getSimpleName(),
                chunks.size(),
                chunks.stream().mapToInt(c -> c.getContent().length()).sum());

        return chunks;
    }
}
