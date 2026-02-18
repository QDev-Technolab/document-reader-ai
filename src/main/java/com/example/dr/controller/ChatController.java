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

import com.example.dr.entity.Conversation;
import com.example.dr.entity.Document;
import com.example.dr.service.ChatService;
import com.example.dr.service.DocumentService;
import com.example.dr.service.EmbeddingService;
import com.example.dr.service.LLMService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/chat")
@Tag(name = "Smart Chat Controller", description = "AI-powered document chat with Llama3 local processing")
public class ChatController {

    private final ChatService chatService;
    private final DocumentService documentService;
    private final EmbeddingService embeddingService;
    private final LLMService llmService;
    @Value("${llm.sse.timeout:180000}")
    private long sseTimeoutMs;

    public ChatController(ChatService chatService, DocumentService documentService,
                         EmbeddingService embeddingService, LLMService llmService) {
        this.chatService = chatService;
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.llmService = llmService;
    }

    @Operation(
        summary = "Upload and process document",
        description = "Upload a document file (PDF, DOCX, TXT) and process it for AI-powered chat"
    )
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @Parameter(description = "Document file to upload")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Text chunk size for processing (default: 500)")
            @RequestParam(defaultValue = "500") int chunkSize) throws Exception {

        Long documentId = chatService.loadDocument(file, chunkSize);
        return ResponseEntity.ok(Map.of(
            "documentId", documentId,
            "status", "Document processed successfully",
            "llamaReady", llmService.isOllamaRunning()
        ));
    }

    @Operation(
        summary = "Ask intelligent question about document",
        description = "Get human-like, contextual answers with automatic response length optimization"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Intelligent answer generated successfully",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = """
                    {
                      "question": "What is the leave policy?",
                      "answer": "Based on the employee handbook, the leave policy encompasses several key components. The organization has a structured approach to managing employee time off, including specific rules for sandwich leave - which refers to taking leave that connects weekends or holidays to create extended time off periods.\\n\\nThe leave rules are comprehensive and cover various scenarios for requesting and approving time off. These policies are managed exclusively by the Management team, ensuring consistent application across the organization. Regular employees do not have the authority to modify these policies, maintaining clear governance and fairness in the leave approval process.\\n\\nFor specific details about leave entitlements, types of leave available, or the approval workflow, employees should refer to the detailed sections of the handbook that outline the complete leave management framework."
                    }
                    """
                )
            )
        )
    })
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(
            @Parameter(
                description = "Question about the uploaded document",
                content = @Content(
                    examples = {
                        @ExampleObject(name = "Normal Question", value = "{\"question\": \"What is the leave policy?\"}"),
                        @ExampleObject(name = "Short Answer Request", value = "{\"question\": \"Give me a brief summary of leave policy\"}"),
                        @ExampleObject(name = "Detailed Request", value = "{\"question\": \"Explain the leave policy in detail\"}")
                    }
                )
            )
            @RequestBody Map<String, String> body,
            
            @Parameter(description = "Number of document chunks to use for context (default: 5)")
            @RequestParam(defaultValue = "5") int topK) throws Exception {
        
        List<String> answers = chatService.answer(body.get("question"), topK);
        return ResponseEntity.ok(Map.of(
            "question", body.get("question"), 
            "answer", answers.get(0),
            "llamaStatus", llmService.isOllamaRunning()
        ));
    }

    @Operation(
        summary = "Ask question with specific response style",
        description = "Get answers with explicitly controlled response length and style"
    )
    @PostMapping("/ask-styled")
    public ResponseEntity<Map<String, Object>> askStyled(
            @RequestBody Map<String, String> body,
            @Parameter(description = "Response style: 'short', 'normal', 'detailed'", example = "normal")
            @RequestParam(defaultValue = "normal") String style,
            @RequestParam(defaultValue = "5") int topK) throws Exception {
        
        List<String> answers = chatService.answerWithStyle(body.get("question"), style, topK);
        return ResponseEntity.ok(Map.of(
            "question", body.get("question"),
            "answer", answers.get(0),
            "style", style,
            "llamaStatus", llmService.isOllamaRunning()
        ));
    }

    @Operation(
        summary = "Get detailed response with transparency",
        description = "Returns AI answer along with source chunks and processing details"
    )
    @PostMapping("/ask-detailed")
    public ResponseEntity<ChatService.ChatResponse> askDetailed(
            @RequestBody Map<String, String> body,
            @RequestParam(defaultValue = "5") int topK) throws Exception {
        
        return ResponseEntity.ok(chatService.answerWithDetails(body.get("question"), topK));
    }

    @Operation(
        summary = "Ask question with streaming response (SSE)",
        description = "Returns Server-Sent Events with tokens as they are generated. " +
                      "Events: 'token' (each generated token), 'done' (generation complete), 'error' (on failure)."
    )
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @RequestBody AskStreamRequest body,
            @RequestParam(defaultValue = "5") int topK,
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
                    }
                );
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);

        return emitter;
    }

    @Operation(
        summary = "Check system status",
        description = "Verify if Llama3 and embedding models are ready"
    )
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "llamaReady", llmService.isOllamaRunning(),
            "embeddingModel", embeddingService.getCurrentModel(),
            "availableModels", embeddingService.getAvailableModels(),
            "llamaModels", llmService.getAvailableModels(),
            "status", "System operational"
        ));
    }

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models() {
        return ResponseEntity.ok(Map.of(
            "availableModels", embeddingService.getAvailableModels(),
            "currentModel", embeddingService.getCurrentModel(),
            "llamaModels", llmService.getAvailableModels()
        ));
    }

    @PostMapping("/set-model")
    public ResponseEntity<Map<String, String>> setModel(@RequestParam String model) {
        embeddingService.setCurrentModel(model);
        return ResponseEntity.ok(Map.of("currentModel", model));
    }

    @Operation(summary = "Get all uploaded documents", description = "List all documents with metadata")
    @GetMapping("/documents")
    public ResponseEntity<List<DocumentInfo>> getDocuments() {
        List<Document> documents = documentService.getAllDocuments();
        List<DocumentInfo> documentInfos = documents.stream()
            .map(doc -> new DocumentInfo(
                doc.getId(),
                doc.getFilename(),
                doc.getUploadTimestamp(),
                doc.getTotalChunks(),
                doc.getStatus()
            ))
            .toList();
        return ResponseEntity.ok(documentInfos);
    }

    @Operation(summary = "Delete a document", description = "Remove document and all its chunks")
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ResponseEntity.ok(Map.of("message", "Document deleted successfully", "documentId", id.toString()));
    }

    @Operation(summary = "Query specific document", description = "Ask questions about a specific document")
    @PostMapping("/documents/{id}/ask")
    public ResponseEntity<Map<String, Object>> askDocument(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestParam(defaultValue = "5") int topK) throws Exception {

        List<String> relevantChunks = documentService.findRelevantChunksInDocument(id, body.get("question"), topK);
        List<String> answers = chatService.answer(body.get("question"), topK);

        return ResponseEntity.ok(Map.of(
            "documentId", id,
            "question", body.get("question"),
            "answer", answers.get(0)
        ));
    }

    // ---- Conversation Endpoints ----

    @Operation(summary = "List all conversations")
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationInfo>> getConversations() {
        return ResponseEntity.ok(chatService.getAllConversations().stream()
            .map(c -> new ConversationInfo(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()))
            .toList());
    }

    @Operation(summary = "Get latest visible thread for a conversation")
    @GetMapping("/conversations/{id}/thread")
    public ResponseEntity<List<ThreadMessageInfo>> getThread(@PathVariable Long id) {
        return ResponseEntity.ok(chatService.getConversationThread(id).stream()
            .map(this::toThreadMessageInfo)
            .toList());
    }

    @Operation(summary = "Get thread from a specific message")
    @GetMapping("/conversations/{id}/thread-from/{msgId}")
    public ResponseEntity<List<ThreadMessageInfo>> getThreadFrom(@PathVariable Long id,
                                                                  @PathVariable Long msgId) {
        return ResponseEntity.ok(chatService.getThreadFrom(id, msgId).stream()
            .map(this::toThreadMessageInfo)
            .toList());
    }

    @Operation(summary = "Get sibling versions for a message")
    @GetMapping("/conversations/{id}/messages/{msgId}/siblings")
    public ResponseEntity<List<ThreadMessageInfo>> getMessageSiblings(@PathVariable Long id,
                                                                       @PathVariable Long msgId) {
        return ResponseEntity.ok(chatService.getMessageSiblings(id, msgId).stream()
            .map(this::toThreadMessageInfo)
            .toList());
    }

    @Operation(summary = "Delete a conversation")
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(@PathVariable Long id) {
        chatService.deleteConversation(id);
        return ResponseEntity.ok(Map.of("message", "Conversation deleted"));
    }

    private ThreadMessageInfo toThreadMessageInfo(ChatService.ThreadMessage message) {
        return new ThreadMessageInfo(
            message.id(),
            message.role(),
            message.content(),
            message.createdAt(),
            message.siblingCount(),
            message.siblingIndex(),
            message.parentId()
        );
    }

    record AskStreamRequest(String question, Long conversationId, Long parentId, Boolean isEdit) {}
    record ConversationInfo(Long id, String title, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    record ThreadMessageInfo(Long id, String role, String content, java.time.Instant createdAt,
                             Integer siblingCount, Integer siblingIndex, Long parentId) {}

    // DTO record for document information
    record DocumentInfo(
        Long id,
        String filename,
        java.time.Instant uploadTimestamp,
        Integer totalChunks,
        Document.DocumentStatus status
    ) {}
}
