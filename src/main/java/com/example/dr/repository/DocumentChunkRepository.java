package com.example.dr.repository;

import com.example.dr.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    long countByDocumentId(Long documentId);

    /**
     * Vector similarity search using pgvector's cosine distance operator
     * Lower distance = higher similarity. Filters out low-relevance chunks.
     */
    @Query(value = """
        SELECT * FROM document_chunks
        WHERE embedding <=> CAST(:embedding AS vector) < :maxDistance
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findMostSimilarChunks(
        @Param("embedding") String embedding,
        @Param("limit") int limit,
        @Param("maxDistance") double maxDistance
    );

    /**
     * Vector similarity search within specific documents
     */
    @Query(value = """
        SELECT * FROM document_chunks
        WHERE document_id IN (:documentIds)
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findMostSimilarChunksInDocuments(
        @Param("embedding") String embedding,
        @Param("documentIds") List<Long> documentIds,
        @Param("limit") int limit
    );

    /**
     * Keyword-based full-text search
     */
    @Query(value = """
        SELECT * FROM document_chunks
        WHERE to_tsvector('english', chunk_text) @@ plainto_tsquery('english', :keywords)
        ORDER BY ts_rank(to_tsvector('english', chunk_text), plainto_tsquery('english', :keywords)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findByKeywords(
        @Param("keywords") String keywords,
        @Param("limit") int limit
    );

    /**
     * Hybrid search: semantic + keyword with combined ranking.
     * Normalizes both scores into 0-1 range and combines them with weighted sum.
     * Semantic weight: 0.7, Keyword weight: 0.3
     */
    @Query(value = """
        WITH semantic_matches AS (
            SELECT id, embedding <=> CAST(:embedding AS vector) as distance
            FROM document_chunks
            WHERE embedding <=> CAST(:embedding AS vector) < :maxDistance
            ORDER BY distance
            LIMIT :semanticLimit
        ),
        keyword_matches AS (
            SELECT id, ts_rank(to_tsvector('english', chunk_text), plainto_tsquery('english', :keywords)) as rank
            FROM document_chunks
            WHERE to_tsvector('english', chunk_text) @@ plainto_tsquery('english', :keywords)
            ORDER BY rank DESC
            LIMIT :keywordLimit
        ),
        combined AS (
            SELECT
                dc.id,
                COALESCE(1.0 - sm.distance, 0) * 0.7 AS semantic_score,
                COALESCE(km.rank, 0) * 0.3 AS keyword_score
            FROM document_chunks dc
            LEFT JOIN semantic_matches sm ON dc.id = sm.id
            LEFT JOIN keyword_matches km ON dc.id = km.id
            WHERE sm.id IS NOT NULL OR km.id IS NOT NULL
        )
        SELECT dc.*
        FROM document_chunks dc
        JOIN combined c ON dc.id = c.id
        ORDER BY (c.semantic_score + c.keyword_score) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findByHybridSearch(
        @Param("embedding") String embedding,
        @Param("keywords") String keywords,
        @Param("semanticLimit") int semanticLimit,
        @Param("keywordLimit") int keywordLimit,
        @Param("limit") int limit,
        @Param("maxDistance") double maxDistance
    );
}