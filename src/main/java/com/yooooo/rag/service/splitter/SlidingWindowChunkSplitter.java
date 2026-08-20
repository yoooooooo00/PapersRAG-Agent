package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 浣跨敤婊戝姩绐楀彛绛栫暐鍒囧垎闀挎枃鏈紝淇濊瘉鐩搁偦鍧楁湁涓€瀹氶噸鍙犮€? */
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

        log.debug("[鍒嗗潡] 鏂囨。鍒嗗潡瀹屾垚锛屽叡{}鍧楋紝avgSize={}瀛楃",
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
            start = alignStartToWordBoundary(text, start);
            int end = Math.min(start + chunkSize, text.length());

            if (end < text.length()) {
                end = findGoodBreakPoint(text, start, end, overlap);
            }
            if (end <= start) {
                end = Math.min(start + chunkSize, text.length());
            }

            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }

            if (end >= text.length()) {
                break;
            }

            int nextStart = Math.max(start + 1, end - overlap);
            nextStart = alignStartToWordBoundary(text, nextStart);
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }

        return result;
    }

    private int findGoodBreakPoint(String text, int start, int position, int overlap) {
        int searchRange = Math.max(120, overlap * 2);
        int minPosition = Math.max(start + Math.max(1, overlap), position - searchRange);

        String[] breakChars = {
                "\n\n", "\n",
                ". ", "? ", "! ", "; ", ": ",
                "。", "？", "！", "；", "：",
                ") ", "] ", " "
        };

        for (String breakChar : breakChars) {
            int idx = text.lastIndexOf(breakChar, position);
            if (idx >= minPosition) {
                return idx + breakChar.length();
            }
        }

        int wordBoundary = findPreviousWordBoundary(text, position, minPosition);
        return wordBoundary > start ? wordBoundary : position;
    }

    private int findPreviousWordBoundary(String text, int position, int minPosition) {
        for (int i = position; i >= minPosition; i--) {
            if (i <= 0 || i >= text.length()) {
                continue;
            }
            if (Character.isWhitespace(text.charAt(i - 1)) || Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int alignStartToWordBoundary(String text, int position) {
        if (position <= 0 || position >= text.length()) {
            return position;
        }
        if (!isAsciiLetterOrDigit(text.charAt(position - 1)) || !isAsciiLetterOrDigit(text.charAt(position))) {
            return position;
        }
        int forwardLimit = Math.min(text.length(), position + 40);
        for (int i = position; i < forwardLimit; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        int backwardLimit = Math.max(0, position - 40);
        for (int i = position; i > backwardLimit; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return position;
    }

    private boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
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
