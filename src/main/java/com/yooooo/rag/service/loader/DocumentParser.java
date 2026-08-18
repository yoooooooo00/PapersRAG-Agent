package com.yooooo.rag.service.loader;

import java.io.InputStream;

/**
 * 文档解析器接口，定义不同文件格式解析器的统一契约。
 */
public interface DocumentParser {
    String supportedType();
    ParseResult parse(InputStream inputStream, String fileName);
}
