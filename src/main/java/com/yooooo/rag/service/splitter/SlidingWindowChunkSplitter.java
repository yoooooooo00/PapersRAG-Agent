package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 使用滑动窗口策略切分长文本，保证相邻块有一定重叠。
 */
@Component
@Slf4j
public class SlidingWindowChunkSplitter implements ChunkSplitter {
    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        List<ChunkResult> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (ParseResult.PageContent page : parseResult.getPages()) {
            String text = page.getText();
            if (text == null || text.isBlank()) continue;

            List<String> pageChunks = splitText(text, config.getChunkSize(), config.getChunkOverlap());

            for (String chunkText : pageChunks) {
                if (chunkText.isBlank()) continue;

                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunkIndex++)
                        .content(chunkText)
                        .pageNum(page.getPageNum())
                        .sectionTitle(page.getSectionTitle())
                        .sectionType(page.getSectionType())
                        .contentType(resolveContentType(chunkText, page.getContentType()))
                        .estimatedTokens(estimateTokens(chunkText))
                        .build());
            }
        }

        log.debug("[分块] 文档分块完成，共{}块，avgSize={}字符",
                chunks.size(),
                chunks.isEmpty() ? 0 : chunks.stream()
                        .mapToInt(c -> c.getContent().length()).average().orElse(0));

        return chunks;
    }

    private String resolveContentType(String text, String pageContentType) {
        if (text != null && text.contains("[TABLE]")) {
            return "TABLE";
        }
        if (pageContentType != null && !pageContentType.isBlank() && !"MIXED".equals(pageContentType)) {
            return pageContentType;
        }
        return "TEXT";
    }

    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            if (end < text.length()) {
                end = findGoodBreakPoint(text, end, overlap);
            }

            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }

            int nextStart = end - overlap;
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }

        return result;
    }

    private int findGoodBreakPoint(String text, int position, int overlap) {
        int searchRange = Math.min(100, position - overlap);

        String[] breakChars = {"\n\n", "\n", "。", "！", "？", "；", "，", " "};

        for (String breakChar : breakChars) {
            int idx = text.lastIndexOf(breakChar, position);
            if (idx > position - searchRange && idx > 0) {
                return idx + breakChar.length();
            }
        }

        return position;
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else if (!Character.isWhitespace(c)) {
                otherChars++;
            }
        }
        return (int) (chineseChars * 1.5 + otherChars * 0.3);
    }
}
