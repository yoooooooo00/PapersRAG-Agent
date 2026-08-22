package com.yooooo.rag.repository;

import com.yooooo.rag.entity.DocChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档分块的数据访问接口，包含向量检索和全文检索查询。
 */
public interface DocChunkRepository extends JpaRepository<DocChunk, Long> {
    @Modifying
    @Transactional
    @Query("DELETE FROM DocChunk c WHERE c.docId = :docId AND c.docVersion < :version")
    void deleteByDocIdAndDocVersionLessThan(@Param("docId") Long docId,
                                            @Param("version") Integer version);

    @Query(value = """
            SELECT *
            FROM kb_doc_chunk
            WHERE kb_id = :kbId
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<DocChunk> findByVectorSimilarity(
            @Param("kbId") Long kbId,
            @Param("embedding") String embedding,
            @Param("topK") int topK);

    @Query(value = """
            SELECT id AS id,
                   1 - (embedding <=> CAST(:embedding AS vector)) AS score
            FROM kb_doc_chunk
            WHERE kb_id = :kbId
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkScoreView> findVectorSimilarityScores(
            @Param("kbId") Long kbId,
            @Param("embedding") String embedding,
            @Param("topK") int topK);

    @Query(value = """
            SELECT *
            FROM kb_doc_chunk
            WHERE kb_id = :kbId
              AND content_tsv @@ to_tsquery('simple', :tsQuery)
            ORDER BY ts_rank(content_tsv, to_tsquery('simple', :tsQuery)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<DocChunk> findByFullTextSearch(
            @Param("kbId") Long kbId,
            @Param("tsQuery") String tsQuery,
            @Param("topK") int topK);

    @Query(value = """
            SELECT id AS id,
                   ts_rank(content_tsv, to_tsquery('simple', :tsQuery)) AS score
            FROM kb_doc_chunk
            WHERE kb_id = :kbId
              AND content_tsv @@ to_tsquery('simple', :tsQuery)
            ORDER BY ts_rank(content_tsv, to_tsquery('simple', :tsQuery)) DESC
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkScoreView> findFullTextSearchScores(
            @Param("kbId") Long kbId,
            @Param("tsQuery") String tsQuery,
            @Param("topK") int topK);

    List<DocChunk> findByDocId(Long docId);

    @Modifying
    @Transactional
    @Query("DELETE FROM DocChunk c WHERE c.docId = :docId")
    void deleteByDocId(@Param("docId") Long docId);

    @Query("SELECT c FROM DocChunk c WHERE c.id IN :ids")
    List<DocChunk> findByIds(@Param("ids") List<Long> ids);

    @Query("SELECT COUNT(c) FROM DocChunk c WHERE c.docId = :docId AND c.docVersion = :version")
    long countByDocIdAndDocVersion(@Param("docId") Long docId, @Param("version") Integer version);

    @Query("SELECT COUNT(c) FROM DocChunk c WHERE c.kbId = :kbId")
    long countByKbId(@Param("kbId") Long kbId);

    List<DocChunk> findByKbId(@Param("kbId") Long kbId);

    interface ChunkScoreView {
        Long getId();
        Double getScore();
    }
}