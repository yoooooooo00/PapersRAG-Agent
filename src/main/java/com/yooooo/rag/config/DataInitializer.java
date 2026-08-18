package com.yooooo.rag.config;

import com.yooooo.rag.entity.KbDocument;
import com.yooooo.rag.repository.KbDocumentRepository;
import com.yooooo.rag.service.indexing.IndexService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 应用启动后检查并初始化示例知识库和测试文档数据。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {
    private final KbDocumentRepository documentRepository;
    private final IndexService indexService;

    @Value("${rag.demo-data.enabled:false}")
    private boolean demoDataEnabled;
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!demoDataEnabled) {
            log.info("[DataInit] Demo data initialization is disabled");
            return;
        }
        if (documentRepository.count() > 0) {
            log.info("[DataInit] 已有文档数据，跳过初始化");
            return;
        }

        log.info("[DataInit] 开始初始化测试文档...");

        initDocument(1L, "hr-handbook.txt", "employee-handbook.txt",
                "TXT", 1L, "test-docs/hr-handbook.txt");
        initDocument(2L, "tech-spec.txt", "tech-specification.txt",
                "TXT", 2L, "test-docs/tech-spec.txt");
        initDocument(3L, "product-faq.txt", "product-faq.txt",
                "TXT", 3L, "test-docs/product-faq.txt");

        log.info("[DataInit] 测试文档初始化完成，等待异步索引...");
    }

    private void initDocument(Long kbId, String minioPath, String fileName,
                              String fileType, Long uploadedBy,
                              String classpath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        byte[] content = resource.getInputStream().readAllBytes();

        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType(fileType);
        doc.setFileSize((long) content.length);
        doc.setMinioPath(minioPath);
        doc.setUploadedBy(uploadedBy);
        KbDocument saved = documentRepository.save(doc);

        String text = new String(content, StandardCharsets.UTF_8);
        indexService.submitIndexTask(saved.getId(), text);

        log.info("[DataInit] 文档已提交索引：id={}, fileName={}", saved.getId(), fileName);
    }
}
