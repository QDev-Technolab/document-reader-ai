package com.example.dr.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.dr.entity.Document;
import com.example.dr.service.ChatService;
import com.example.dr.service.DocumentService;

import io.swagger.v3.oas.annotations.Parameter;

/**
 * REST controller for all chat, document, and conversation operations.
 *
 * <p>Exposes endpoints under {@code /chat} for:
 * <ul>
 *   <li>Uploading and managing documents</li>
 *   <li>Streaming question-answering over uploaded documents via SSE</li>
 *   <li>Listing, loading, and deleting conversation threads</li>
 * </ul>
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final DocumentService documentService;

    /**
     * SSE connection timeout in milliseconds, configurable via {@code llm.sse.timeout}.
     */
    @Value("${llm.sse.timeout:180000}")
    private long sseTimeoutMs;

    /**
     * @param chatService     handles conversation and Q&amp;A logic
     * @param documentService handles document storage and retrieval
     */
    public ChatController(ChatService chatService, DocumentService documentService) {
        this.chatService = chatService;
        this.documentService = documentService;
    }

    /**
     * Accepts a multipart document upload, processes it into chunks, and stores both the document record and its embeddings.
     *
     * @param file      the document file (PDF, DOCX, TXT, XLSX)
     * @param chunkSize number of characters per text chunk
     * @return the assigned document ID and a confirmation status message
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @Parameter(description = "Document file to upload") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Text chunk size for processing (default: 500)") @RequestParam(defaultValue = "500") int chunkSize)
            throws Exception {

        Long documentId = chatService.loadDocument(file, chunkSize);
        return ResponseEntity.ok(Map.of(
                "documentId", documentId,
                "status", "Document processed successfully"));
    }

    /**
     * Streams an AI-generated answer to the client as Server-Sent Events (SSE).
     *
     * <p>Event sequence:
     * <ol>
     *   <li>{@code conversationId} — emitted once if a new conversation was created</li>
     *   <li>{@code userMessage} — metadata for the persisted user message</li>
     *   <li>{@code token} — one event per generated token, repeated until complete</li>
     *   <li>{@code done} — metadata for the persisted assistant message; ends the stream</li>
     *   <li>{@code error} — emitted instead of {@code done} if generation fails</li>
     * </ol>
     *
     * @param body     request containing the question, optional conversation/parent IDs, and an edit flag
     * @param topK     number of document chunks to retrieve as context (default 5)
     * @param response raw HTTP response, used to disable proxy buffering
     * @return an {@link SseEmitter} that pushes tokens to the client in real time
     */
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AskStreamRequest body, @RequestParam(defaultValue = "7") int topK,
            HttpServletResponse response) {

        // Disable all buffering for real-time token delivery
        response.setBufferSize(0);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");

        String question = body.question();
        Long conversationId = body.conversationId();
        Long parentId = body.parentId();
        boolean isEdit = Boolean.TRUE.equals(body.isEdit());
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        Thread.ofVirtual().name("sse-stream").start(() -> {
            try {
                chatService.answerStreaming(
                        question,
                        topK,
                        conversationId,
                        parentId,
                        isEdit,
                        token -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("token")
                                        .data(Map.of("t", token), MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        userRef -> {
                            try {
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("id", userRef.id());
                                payload.put("parentId", userRef.parentId());
                                payload.put("siblingCount", userRef.siblingCount());
                                payload.put("siblingIndex", userRef.siblingIndex());
                                emitter.send(SseEmitter.event()
                                        .name("userMessage")
                                        .data(payload, MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        assistantRef -> {
                            try {
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("assistantMessageId", assistantRef.id());
                                payload.put("assistantParentId", assistantRef.parentId());
                                payload.put("assistantSiblingCount", assistantRef.siblingCount());
                                payload.put("assistantSiblingIndex", assistantRef.siblingIndex());
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(payload, MediaType.APPLICATION_JSON));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(error.getMessage(), MediaType.TEXT_PLAIN));
                                emitter.completeWithError(error);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        convId -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("conversationId")
                                        .data(String.valueOf(convId), MediaType.TEXT_PLAIN));
                            } catch (IOException e) {
                                // non-fatal - streaming can continue without the ID
                            }
                        });
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);

        return emitter;
    }

    /**
     * Returns metadata for all uploaded documents.
     *
     * @return list of {@link DocumentInfo} projections
     */
    @GetMapping("/documents")
    public ResponseEntity<List<DocumentInfo>> getDocuments() {
        List<Document> documents = documentService.getAllDocuments();
        List<DocumentInfo> documentInfos = documents.stream()
                .map(doc -> new DocumentInfo(
                        doc.getId(),
                        doc.getFilename(),
                        doc.getUploadTimestamp(),
                        doc.getTotalChunks(),
                        doc.getStatus()))
                .toList();
        return ResponseEntity.ok(documentInfos);
    }

    /**
     * Deletes a document and all of its associated chunks and embeddings.
     *
     * @param id the document ID
     * @return a confirmation message
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ResponseEntity.ok(Map.of("message", "Document deleted successfully", "documentId", id.toString()));
    }

    /**
     * Returns a summary list of all conversations, ordered by most recently updated.
     *
     * @return list of {@link ConversationInfo} projections
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationInfo>> getConversations() {
        return ResponseEntity.ok(chatService.getAllConversations().stream()
                .map(c -> new ConversationInfo(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()))
                .toList());
    }

    /**
     * Returns the active message thread for a conversation — the linear path from
     * the root message to the latest leaf following the most-recent branch at each fork.
     *
     * @param id the conversation ID
     * @return ordered list of {@link ThreadMessageInfo} records
     */
    @GetMapping("/conversations/{id}/thread")
    public ResponseEntity<List<ThreadMessageInfo>> getThread(@PathVariable Long id) {
        return ResponseEntity.ok(chatService.getConversationThread(id).stream()
                .map(this::toThreadMessageInfo)
                .toList());
    }

    /**
     * Returns the thread starting from a specific message, used when the client switches to a different edit branch.
     *
     * @param id    the conversation ID
     * @param msgId the message ID to start the thread from
     * @return ordered list of {@link ThreadMessageInfo} records from {@code msgId} onward
     */
    @GetMapping("/conversations/{id}/thread-from/{msgId}")
    public ResponseEntity<List<ThreadMessageInfo>> getThreadFrom(@PathVariable Long id, @PathVariable Long msgId) {
        return ResponseEntity.ok(chatService.getThreadFrom(id, msgId).stream()
                .map(this::toThreadMessageInfo)
                .toList());
    }

    /**
     * Returns all sibling messages for a given message — i.e., all edited versions
     * of the same user turn. Used by the client to render version navigation controls.
     *
     * @param id    the conversation ID
     * @param msgId the message whose siblings to retrieve
     * @return list of {@link ThreadMessageInfo} records sharing the same parent
     */
    @GetMapping("/conversations/{id}/messages/{msgId}/siblings")
    public ResponseEntity<List<ThreadMessageInfo>> getMessageSiblings(@PathVariable Long id, @PathVariable Long msgId) {
        return ResponseEntity.ok(chatService.getMessageSiblings(id, msgId).stream()
                .map(this::toThreadMessageInfo)
                .toList());
    }

    /**
     * Deletes a conversation and all of its messages.
     *
     * @param id the conversation ID
     * @return a confirmation message
     */
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(@PathVariable Long id) {
        chatService.deleteConversation(id);
        return ResponseEntity.ok(Map.of("message", "Conversation deleted"));
    }

    /**
     * Maps a {@link ChatService.ThreadMessage} to its API response record.
     *
     * @param message the internal thread message
     * @return a populated {@link ThreadMessageInfo}
     */
    private ThreadMessageInfo toThreadMessageInfo(ChatService.ThreadMessage message) {
        return new ThreadMessageInfo(
                message.id(),
                message.role(),
                message.content(),
                message.createdAt(),
                message.siblingCount(),
                message.siblingIndex(),
                message.parentId());
    }

    /** Request body for the streaming chat endpoint. */
    record AskStreamRequest(String question, Long conversationId, Long parentId, Boolean isEdit) {
    }

    /** API response projection for a {@link com.example.dr.entity.Conversation}. */
    record ConversationInfo(Long id, String title, java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    /**
     * API response projection for a single message in a thread, including sibling
     * navigation metadata.
     */
    record ThreadMessageInfo(Long id, String role, String content, java.time.Instant createdAt,
            Integer siblingCount, Integer siblingIndex, Long parentId) {
    }

    /** API response projection for a {@link com.example.dr.entity.Document}. */
    record DocumentInfo(
            Long id,
            String filename,
            java.time.Instant uploadTimestamp,
            Integer totalChunks,
            Document.DocumentStatus status) {
    }
}
