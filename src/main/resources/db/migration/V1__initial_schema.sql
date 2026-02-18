-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Documents table - stores document metadata
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    filename VARCHAR(500) NOT NULL,
    file_extension VARCHAR(10) NOT NULL,
    file_size_bytes BIGINT,
    upload_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    chunk_size INTEGER NOT NULL,
    total_chunks INTEGER NOT NULL,
    full_text TEXT,
    embedding_model VARCHAR(100) DEFAULT 'all-MiniLM-L6-v2',
    status VARCHAR(50) DEFAULT 'PROCESSED',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Document chunks table - stores text chunks and embeddings
CREATE TABLE document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding vector(384),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_document_chunk UNIQUE (document_id, chunk_index)
);

-- Indexes for performance
CREATE INDEX idx_documents_filename ON documents(filename);
CREATE INDEX idx_documents_upload_timestamp ON documents(upload_timestamp DESC);
CREATE INDEX idx_chunks_document_id ON document_chunks(document_id);

-- HNSW index for fast vector similarity search (cosine distance)
CREATE INDEX idx_chunks_embedding_hnsw ON document_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

-- Full-text search index for keyword matching
CREATE INDEX idx_chunks_text_gin ON document_chunks
USING gin(to_tsvector('english', chunk_text));

-- Conversations table
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Chat messages table (tree model for versioned branches)
CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    parent_id BIGINT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_messages_parent
        FOREIGN KEY (parent_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_messages_not_self_parent
        CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX idx_messages_conversation_id ON chat_messages(conversation_id);
CREATE INDEX idx_messages_parent_id ON chat_messages(parent_id);
CREATE INDEX idx_messages_conv_parent_role_created
    ON chat_messages(conversation_id, parent_id, role, created_at, id);
CREATE INDEX idx_conversations_updated_at ON conversations(updated_at DESC);
