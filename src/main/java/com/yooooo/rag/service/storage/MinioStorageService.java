package com.yooooo.rag.service.storage;

import io.minio.*;
import java.io.InputStream;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 封装 MinIO 文件上传、下载和删除操作。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {
    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public String upload(Long kbId, MultipartFile file) {
        String objectPath = String.format("kb/%d/%s-%s",
                kbId, UUID.randomUUID().toString().substring(0, 8), file.getOriginalFilename());
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            log.info("[MinIO] 上传成功：path={}", objectPath);
            return objectPath;
        } catch (Exception e) {
            log.error("[MinIO] 上传失败：path={}，error={}", objectPath, e.getMessage(), e);
            throw new RuntimeException("文件上传失败：" + e.getMessage(), e);
        }
    }

    public byte[] download(String objectPath) {
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            return stream.readAllBytes();
        } catch (Exception e) {
            log.error("[MinIO] 下载失败：path={}，error={}", objectPath, e.getMessage(), e);
            throw new RuntimeException("文件下载失败：" + e.getMessage(), e);
        }
    }

    public void delete(String objectPath) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            log.info("[MinIO] 删除成功：path={}", objectPath);
        } catch (Exception e) {
            log.warn("[MinIO] 删除失败（可能已不存在）：path={}，error={}", objectPath, e.getMessage());
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[MinIO] Bucket 已创建：{}", bucket);
        }
    }
}
