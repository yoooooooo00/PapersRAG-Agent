package com.yooooo.rag.service.splitter;

import com.yooooo.rag.service.loader.ParseResult;
import java.util.List;

/**
 * 文本切分器接口，定义不同切分策略的统一方法。
 */
public interface ChunkSplitter {
    List<ChunkResult> split(ParseResult parseResult, ChunkConfig config);
}
