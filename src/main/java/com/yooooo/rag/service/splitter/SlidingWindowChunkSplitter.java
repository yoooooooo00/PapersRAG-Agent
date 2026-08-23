package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sentence-first splitter with word-boundary fallback for long sentences.
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
            if (text == null || text.isBlank()) {
                continue;
            }

            List<String> pageChunks = splitText(text, config.getChunkSize(), config.getChunkOverlap());
            for (String chunkText : pageChunks) {
                if (chunkText.isBlank()) {
                    continue;
                }
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

        log.debug("[Chunk] split completed chunks={} avgChars={}",
                chunks.size(),
                chunks.isEmpty() ? 0 : chunks.stream().mapToInt(c -> c.getContent().length()).average().orElse(0));

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
        String normalized = normalizeText(text);
        List<String> sentences = splitIntoSentences(normalized);
        if (sentences.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentChars = 0;

        for (String sentence : sentences) {
            String cleanSentence = sentence.strip();
            if (cleanSentence.isBlank()) {
                continue;
            }

            if (cleanSentence.length() > chunkSize) {
                if (!current.isEmpty()) {
                    chunks.add(joinSentences(current));
                    current = new ArrayList<>();
                    currentChars = 0;
                }
                chunks.addAll(splitLongSentence(cleanSentence, chunkSize, overlap));
                continue;
            }

            if (current.isEmpty()) {
                current.add(cleanSentence);
                currentChars = cleanSentence.length();
                continue;
            }

            if (currentChars + 1 + cleanSentence.length() <= chunkSize) {
                current.add(cleanSentence);
                currentChars += 1 + cleanSentence.length();
                continue;
            }

            chunks.add(joinSentences(current));
            List<String> overlapSeed = buildOverlapSeed(current, overlap);
            current = trimOverlapToFit(overlapSeed, cleanSentence, chunkSize);
            currentChars = joinedLength(current);

            if (current.isEmpty()) {
                current.add(cleanSentence);
                currentChars = cleanSentence.length();
                continue;
            }

            if (currentChars + 1 + cleanSentence.length() <= chunkSize) {
                current.add(cleanSentence);
                currentChars += 1 + cleanSentence.length();
            } else {
                chunks.addAll(splitLongSentence(cleanSentence, chunkSize, overlap));
                current = new ArrayList<>();
                currentChars = 0;
            }
        }

        if (!current.isEmpty()) {
            chunks.add(joinSentences(current));
        }

        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);
        int start = iterator.first();
        while (true) {
            int end = iterator.next();
            if (end == BreakIterator.DONE) {
                break;
            }
            String sentence = text.substring(start, end).strip();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
            start = end;
        }

        if (sentences.isEmpty()) {
            String single = text.strip();
            if (!single.isBlank()) {
                sentences.add(single);
            }
        }
        return sentences;
    }

    private List<String> splitLongSentence(String text, int chunkSize, int overlap) {
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

    private List<String> buildOverlapSeed(List<String> sentences, int overlap) {
        if (overlap <= 0 || sentences == null || sentences.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> seed = new ArrayList<>();
        int total = 0;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i).strip();
            if (sentence.isBlank()) {
                continue;
            }
            int sentenceLen = sentence.length();
            int added = seed.isEmpty() ? sentenceLen : sentenceLen + 1;
            if (!seed.isEmpty() && total + added > overlap) {
                break;
            }
            seed.add(0, sentence);
            total += added;
            if (total >= overlap) {
                break;
            }
        }

        if (seed.isEmpty() && !sentences.isEmpty()) {
            String last = sentences.get(sentences.size() - 1).strip();
            if (overlap > 0 && last.length() > overlap) {
                last = last.substring(last.length() - overlap).strip();
            }
            if (!last.isBlank()) {
                seed.add(last);
            }
        }
        return seed;
    }

    private List<String> trimOverlapToFit(List<String> overlapSeed, String nextSentence, int chunkSize) {
        List<String> seed = new ArrayList<>(overlapSeed);
        while (!seed.isEmpty() && joinedLength(seed) + 1 + nextSentence.length() > chunkSize) {
            seed.remove(0);
        }
        return seed;
    }

    private String joinSentences(List<String> sentences) {
        return String.join(" ", sentences).strip();
    }

    private int joinedLength(List<String> sentences) {
        if (sentences == null || sentences.isEmpty()) {
            return 0;
        }
        int length = 0;
        for (String sentence : sentences) {
            if (sentence != null && !sentence.isBlank()) {
                length += sentence.strip().length();
            }
        }
        return length + Math.max(0, sentences.size() - 1);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\t ]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
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
        if (text == null) {
            return 0;
        }
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
