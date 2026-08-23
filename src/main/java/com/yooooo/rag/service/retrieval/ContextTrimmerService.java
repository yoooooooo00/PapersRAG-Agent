package com.yooooo.rag.service.retrieval;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.yooooo.rag.entity.DocChunk;
import com.yooooo.rag.service.metrics.TokenMetrics;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 根据 token 预算裁剪上下文，防止提示词过长。
 */
@Service
@Slf4j
public class ContextTrimmerService {
    @Value("${rag.context.max-tokens:3000}")
    private int maxContextTokens;
    private final TokenMetrics tokenMetrics;
    private Encoding tokenizer;
    public ContextTrimmerService(TokenMetrics tokenMetrics) {
        this.tokenMetrics = tokenMetrics;
    }

    @PostConstruct
    public void init() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.tokenizer = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public List<HybridRetrieverService.ScoredChunk> trim(
            List<HybridRetrieverService.ScoredChunk> candidates) {
        List<HybridRetrieverService.ScoredChunk> selected = new ArrayList<>();
        int usedTokens = 0;
        for (HybridRetrieverService.ScoredChunk sc : candidates) {
            String contextContent = contentForContext(sc);
            int chunkTokens = countTokens(contextContent);
            if (usedTokens + chunkTokens <= maxContextTokens) {
                selected.add(sc);
                usedTokens += chunkTokens;
            } else if (selected.isEmpty()) {
                String truncated = truncateToTokens(contextContent,
                        maxContextTokens - usedTokens);
                if (!truncated.isBlank()) {
                    DocChunk truncatedChunk = new DocChunk();
                    truncatedChunk.setId(sc.chunk().getId());
                    truncatedChunk.setDocId(sc.chunk().getDocId());
                    truncatedChunk.setKbId(sc.chunk().getKbId());
                    truncatedChunk.setChunkIndex(sc.chunk().getChunkIndex());
                    truncatedChunk.setContent(truncated);
                    truncatedChunk.setPageNum(sc.chunk().getPageNum());
                    truncatedChunk.setSectionTitle(sc.chunk().getSectionTitle());
                    truncatedChunk.setTokenCount(countTokens(truncated));
                    truncatedChunk.setDocVersion(sc.chunk().getDocVersion());
                    selected.add(new HybridRetrieverService.ScoredChunk(
                            truncatedChunk, sc.score()));
                    usedTokens += countTokens(truncated);
                }
                break;
            } else {
                int remainingTokens = maxContextTokens - usedTokens;
                if (remainingTokens > 0) {
                    String truncated = truncateToTokens(contextContent, remainingTokens);
                    if (!truncated.isBlank()) {
                        DocChunk truncatedChunk = new DocChunk();
                        truncatedChunk.setId(sc.chunk().getId());
                        truncatedChunk.setDocId(sc.chunk().getDocId());
                        truncatedChunk.setKbId(sc.chunk().getKbId());
                        truncatedChunk.setChunkIndex(sc.chunk().getChunkIndex());
                        truncatedChunk.setContent(truncated);
                        truncatedChunk.setPageNum(sc.chunk().getPageNum());
                        truncatedChunk.setSectionTitle(sc.chunk().getSectionTitle());
                        truncatedChunk.setContentType(sc.chunk().getContentType());
                        truncatedChunk.setTableCaption(sc.chunk().getTableCaption());
                        truncatedChunk.setTokenCount(countTokens(truncated));
                        truncatedChunk.setDocVersion(sc.chunk().getDocVersion());
                        selected.add(new HybridRetrieverService.ScoredChunk(truncatedChunk, sc.score()));
                    }
                }
                break;
            }
        }
        log.info("[ContextTrimmer] 候选={}，选取={}，usedTokens={}/{}",
                candidates.size(), selected.size(), usedTokens, maxContextTokens);
        tokenMetrics.recordContextTokens(usedTokens);
        return selected;
    }

    public String contentForContext(HybridRetrieverService.ScoredChunk sc) {
        if (sc == null || sc.chunk() == null) return "";
        DocChunk chunk = sc.chunk();
        if ("TABLE".equalsIgnoreCase(chunk.getContentType())
                && chunk.getRawContent() != null && !chunk.getRawContent().isBlank()) {
            return chunk.getRawContent().strip();
        }
        return chunk.getContent() == null ? "" : chunk.getContent().strip();
    }

    public int countTokens(String text) {
        if (text == null || text.isBlank()) return 0;

        return tokenizer.encode(text).size();
    }

    private String truncateToTokens(String text, int maxTokens) {
        if (maxTokens <= 0) return "";

        if (countTokens(text) <= maxTokens) return text;

        String[] sentences = text.split("(?<=[。！？\\n])");
        StringBuilder result = new StringBuilder();
        int tokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = countTokens(sentence);

            if (tokens + sentenceTokens <= maxTokens) {
                result.append(sentence);
                tokens += sentenceTokens;
            } else {
                break;
            }
        }

        return result.toString();
    }
}
