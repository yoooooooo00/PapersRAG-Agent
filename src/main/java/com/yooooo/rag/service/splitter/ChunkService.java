package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import com.yooooo.rag.service.splitter.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 负责把解析后的文档内容切分成可检索的文本块。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkService {
    private final SlidingWindowChunkSplitter slidingWindowSplitter;
    private final StructureAwareChunkSplitter structureAwareSplitter;

    @Value("${rag.chunk.size:512}")
    private int defaultChunkSize;

    @Value("${rag.chunk.overlap:64}")
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

    public List<ChunkResult> chunk(ParseResult parseResult, ChunkConfig config) {
        if (parseResult == null || !parseResult.isSuccess()) {
            return List.of();
        }

        boolean hasStructure = parseResult.getPages().stream()
                .anyMatch(p -> p.getSectionTitle() != null);

        ChunkSplitter splitter = (hasStructure && config.isStructureAware())
                ? structureAwareSplitter
                : slidingWindowSplitter;

        List<ChunkResult> chunks = splitter.split(parseResult, config);

        chunks = chunks.stream()
                .filter(c -> c.getContent().length() >= 20)
                .toList();

        log.info("[分块] 完成分块：策略={}，共{}块，总字符={}",
                splitter.getClass().getSimpleName(),
                chunks.size(),
                chunks.stream().mapToInt(c -> c.getContent().length()).sum());

        return chunks;
    }
}
