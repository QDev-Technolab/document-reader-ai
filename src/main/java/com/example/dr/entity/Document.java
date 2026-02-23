package com.example.dr.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity representing an uploaded document stored in the {@code documents} table.
 * A document is split into {@link DocumentChunk}s after upload. 
 * The full extracted text and metadata (filename, size, embedding model) are persisted here.
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "file_extension", length = 10, nullable = false)
    private String fileExtension;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "upload_timestamp", nullable = false)
    private Instant uploadTimestamp;

    @Column(name = "chunk_size", nullable = false)
    private Integer chunkSize;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    @Column(name = "full_text", columnDefinition = "TEXT")
    private String fullText;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private DocumentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("chunkIndex ASC")
    @Builder.Default
    private List<DocumentChunk> chunks = new ArrayList<>();

    public void addChunk(DocumentChunk chunk) {
        chunks.add(chunk);
        chunk.setDocument(this);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (uploadTimestamp == null) {
            uploadTimestamp = Instant.now();
        }
        if (status == null) {
            status = DocumentStatus.PROCESSING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum DocumentStatus {
        PROCESSING,
        PROCESSED,
        FAILED
    }
}
