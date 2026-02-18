package com.example.dr.repository;

import com.example.dr.entity.Document;
import com.example.dr.entity.Document.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByFilename(String filename);

    List<Document> findByStatus(DocumentStatus status);

    List<Document> findAllByOrderByUploadTimestampDesc();

    boolean existsByFilename(String filename);

    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.chunks WHERE d.id = :id")
    Optional<Document> findByIdWithChunks(@Param("id") Long id);
}