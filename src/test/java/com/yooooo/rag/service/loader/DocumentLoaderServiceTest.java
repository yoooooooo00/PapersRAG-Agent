package com.yooooo.rag.service.loader;

import com.yooooo.rag.service.document.DocumentLoaderService;
import java.io.InputStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * 验证文档加载服务可以解析测试文档。
 */

@SpringBootTest
class DocumentLoaderServiceTest {
    @Autowired
    private DocumentLoaderService loaderService;

    private String extractText(ParseResult result) {
        return result.getPages().stream()
                .map(ParseResult.PageContent::getText)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void parseTxtFile() throws Exception {
        ClassPathResource resource = new ClassPathResource("test-docs/hr-handbook.txt");
        try (InputStream is = resource.getInputStream()) {
            ParseResult result = loaderService.load(is, "hr-handbook.txt");
            String text = extractText(result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(text).isNotBlank();
            assertThat(text).contains("v2.3");
        }
    }

    @Test
    void parsePdf() throws Exception {
        ClassPathResource resource = new ClassPathResource("test-docs/policy.pdf");
        try (InputStream is = resource.getInputStream()) {
            ParseResult result = loaderService.load(is, "policy.pdf");
            String text = extractText(result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(text).isNotBlank();
            assertThat(result.getTotalPages()).isGreaterThan(0);
        }
    }

    @Test
    void parseDocx() throws Exception {
        ClassPathResource resource = new ClassPathResource("test-docs/policy.docx");
        if (!resource.exists()) {
            return;
        }
        try (InputStream is = resource.getInputStream()) {
            ParseResult result = loaderService.load(is, "policy.docx");
            String text = extractText(result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(text).isNotBlank();
            assertThat(result.getPages()).isNotEmpty();
        }
    }

    @Test
    void parseMd() throws Exception {
        ClassPathResource resource = new ClassPathResource("test-docs/hr-handbook.md");
        try (InputStream is = resource.getInputStream()) {
            ParseResult result = loaderService.load(is, "hr-handbook.md");
            String text = extractText(result);

            assertThat(result.isSuccess()).isTrue();
            assertThat(text).isNotBlank();
            assertThat(result.getPages()).isNotEmpty();
        }
    }

    @Test
    void unsupportedTypeReturnsFailure() {
        InputStream emptyStream = InputStream.nullInputStream();
        ParseResult result = loaderService.load(emptyStream, "test.xyz");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isNotBlank();
    }
}
