package com.example.dr.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.dr.service.ChatService;
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
    private final EmbeddingService embeddingService;
    private final LLMService llmService;

    public ChatController(ChatService chatService, EmbeddingService embeddingService, LLMService llmService) {
        this.chatService = chatService;
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
        
        int cnt = chatService.loadDocument(file, chunkSize);
        return ResponseEntity.ok(Map.of(
            "processedChunks", cnt,
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
}