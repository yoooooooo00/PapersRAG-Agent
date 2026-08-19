package com.yooooo.rag.service.loader;

import com.yooooo.rag.service.document.DocumentLoaderService;
import java.io.InputStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

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
    void unsupportedTypeReturnsFailure() {
        InputStream emptyStream = InputStream.nullInputStream();
        ParseResult result = loaderService.load(emptyStream, "test.txt");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Only PDF is supported");
    }
}