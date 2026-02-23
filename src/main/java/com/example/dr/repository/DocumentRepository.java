package com.example.dr.repository;

import com.example.dr.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Spring Data JPA repository for {@link com.example.dr.entity.Document} entities.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * Returns all documents ordered by most recently uploaded first.
     *
     * @return list of documents, newest first
     */
    List<Document> findAllByOrderByUploadTimestampDesc();
}
