package com.yooooo.rag.service.splitter;

import lombok.Builder;
import lombok.Data;

/**
 * 文档切分配置，定义块大小和重叠长度。
 */
@Data
@Builder
public class ChunkConfig {
    @Builder.Default
    private int chunkSize = 1200;

    @Builder.Default
    private int chunkOverlap = 150;

    @Builder.Default
    private boolean structureAware = true;

    public static ChunkConfig defaultConfig() {
        return ChunkConfig.builder().build();
    }
}
