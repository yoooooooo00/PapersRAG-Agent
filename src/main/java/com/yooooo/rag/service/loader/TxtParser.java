package com.yooooo.rag.service.loader;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析纯文本文件，把文本内容包装为统一的解析结果。
 */
@Component
@Slf4j
public class TxtParser implements DocumentParser {
    private static final double UTF8_DECODE_FAIL_THRESHOLD = 0.01;
    private static final double BINARY_THRESHOLD = 0.05;

    @Override
    public String supportedType() {
        return "TXT";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            Charset charset = StandardCharsets.UTF_8;
            String text = new String(bytes, charset);
            long replacementCount = text.chars().filter(c -> c == 0xFFFD).count();
            if (replacementCount > text.length() * UTF8_DECODE_FAIL_THRESHOLD) {
                charset = Charset.forName("GBK");
                text = new String(bytes, charset);
                log.info("[TXT解析] 文件={} 非 UTF-8，降级到 GBK 解码", fileName);
            }
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");

            if (text.isBlank()) {
                return ParseResult.failure("文本文件内容为空");
            }
            if (isLikelyBinary(text)) {
                return ParseResult.failure("文件包含大量非文本字符，疑似二进制文件");
            }
            log.info("[TXT解析] 文件={}，编码={}，字符数={}", fileName, charset.name(), text.length());

            return ParseResult.builder()
                    .success(true)
                    .pages(List.of(ParseResult.PageContent.builder()
                            .pageNum(1)
                            .text(text.strip())
                            .build()))
                    .totalPages(1)
                    .build();

        } catch (Exception e) {
            log.error("[TXT解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("TXT 解析失败：" + e.getMessage());
        }
    }

    private boolean isLikelyBinary(String text) {
        long nonPrintable = text.chars()
                .filter(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r')
                .count();
        return nonPrintable > text.length() * BINARY_THRESHOLD;
    }
}
