package com.yooooo.rag.service.loader;

import com.yooooo.rag.service.splitter.ChunkResult;
import com.yooooo.rag.service.splitter.ChunkService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * 验证文档切分服务的基本切分效果。
 */

@SpringBootTest
class ChunkServiceTest {
    @Autowired
    private ChunkService chunkService;

    @Value("${rag.chunk.size}")
    private int configuredChunkSize;

    @Test
    void chunksNotTooLargeOrTooSmall() {
        String longText = "这是一段测试文本。".repeat(200);
        ParseResult result = ParseResult.builder()
                .success(true)
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(longText)
                        .build()))
                .totalPages(1)
                .build();

        List<ChunkResult> chunks = chunkService.chunk(result);

        assertThat(chunks).isNotEmpty();
        for (ChunkResult chunk : chunks) {
            assertThat(chunk.getContent().length()).isLessThanOrEqualTo(configuredChunkSize);

            assertThat(chunk.getContent().length()).isGreaterThanOrEqualTo(20);
        }

        if (chunks.size() >= 2) {
            String end0 = chunks.get(0).getContent();
            String start1 = chunks.get(1).getContent();

            String overlapPart = end0.substring(Math.max(0, end0.length() - Math.min(150, end0.length())));
            assertThat(start1).contains(overlapPart.substring(0, Math.min(30, overlapPart.length())));
        }
    }
}
