package com.example.dr.repository;

import com.example.dr.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Spring Data JPA repository for {@link com.example.dr.entity.DocumentChunk} entities.
 * Provides pgvector-based semantic search and hybrid (semantic + keyword) search via native SQL queries.
 */
@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * Vector similarity search using pgvector's cosine distance operator Lower distance = higher similarity. 
     * Filters out low-relevance chunks.
     *
     * @param embedding   pgvector-formatted query embedding string, e.g. {@code [0.1,0.2,...]}
     * @param limit       maximum number of results to return
     * @param maxDistance cosine distance threshold; chunks with distance >= this value are excluded
     * @return list of chunks ordered by ascending cosine distance (most similar first)
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
            @Param("maxDistance") double maxDistance);

    /**
     * Hybrid search: semantic + keyword with combined ranking.
     * Normalizes both scores into 0-1 range and combines them with weighted sum.
     * Semantic weight: 0.7, Keyword weight: 0.3
     *
     * Hybrid search combining semantic similarity (weight 0.7) and full-text keyword ranking (weight 0.3).
     *
     * @param embedding     pgvector-formatted query embedding string
     * @param keywords      space-separated keyword string passed to {@code plainto_tsquery}
     * @param semanticLimit candidate pool size for the semantic sub-query
     * @param keywordLimit  candidate pool size for the keyword sub-query
     * @param limit         final result limit
     * @param maxDistance   cosine distance threshold for the semantic filter
     * @return list of chunks ordered by combined score descending
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
            @Param("maxDistance") double maxDistance);
}
