package com.example.dr.service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChatService {
    
    private final EmbeddingService embeddingService;
    private final DocumentService documentService;
    private final LLMService llmService;
    
    // Patterns to detect user intent for response length
    private static final Pattern SHORT_PATTERNS = Pattern.compile(
        "(?i).*(brief|short|quick|summary|concise|simple|in short).*"
    );
    private static final Pattern DETAILED_PATTERNS = Pattern.compile(
        "(?i).*(detail|explain|elaborate|comprehensive|in depth|full|complete).*"
    );
    
    public ChatService(EmbeddingService embeddingService, 
                      DocumentService documentService,
                      LLMService llmService) {
        this.embeddingService = embeddingService;
        this.documentService = documentService;
        this.llmService = llmService;
    }
    
    public int loadDocument(MultipartFile file, int chunkSize) throws Exception {
        return documentService.processDocument(file, chunkSize);
    }
    
    public List<String> answer(String question, int topK) throws Exception {
        // Auto-detect response style from question
        String responseStyle = detectResponseStyle(question);
        
        // Get more chunks for better context (topK * 2 for LLM to choose from)
        List<String> relevantChunks = documentService.findRelevantChunks(question, Math.max(topK * 2, 8));
        
        // Generate intelligent answer using LLM
        String contextualAnswer = llmService.generateAnswer(question, relevantChunks, responseStyle);
        
        return List.of(contextualAnswer);
    }
    
    public List<String> answerWithStyle(String question, String style, int topK) throws Exception {
        // Get relevant chunks
        List<String> relevantChunks = documentService.findRelevantChunks(question, Math.max(topK * 2, 8));
        
        // Generate answer with specified style
        String contextualAnswer = llmService.generateAnswer(question, relevantChunks, style);
        
        return List.of(contextualAnswer);
    }
    
    public ChatResponse answerWithDetails(String question, int topK) throws Exception {
        String responseStyle = detectResponseStyle(question);
        
        // Get relevant chunks
        List<String> relevantChunks = documentService.findRelevantChunks(question, Math.max(topK * 2, 8));
        
        // Generate contextual answer using LLM
        String llmAnswer = llmService.generateAnswer(question, relevantChunks, responseStyle);
        
        // Get top relevant chunks for reference
        List<String> topChunks = relevantChunks.stream()
                                              .limit(topK)
                                              .collect(Collectors.toList());
        
        return new ChatResponse(
            question, 
            llmAnswer, 
            topChunks, 
            relevantChunks.size(),
            responseStyle,
            llmService.isOllamaRunning()
        );
    }
    
    private String detectResponseStyle(String question) {
        if (SHORT_PATTERNS.matcher(question).matches()) {
            return "short";
        } else if (DETAILED_PATTERNS.matcher(question).matches()) {
            return "detailed";
        } else {
            return "normal";
        }
    }
    
    // Enhanced response class with more information
    public static class ChatResponse {
        private String question;
        private String answer;
        private List<String> sourceChunks;
        private int totalRelevantChunks;
        private String responseStyle;
        private boolean llamaStatus;
        
        public ChatResponse(String question, String answer, List<String> sourceChunks, 
                           int totalRelevantChunks, String responseStyle, boolean llamaStatus) {
            this.question = question;
            this.answer = answer;
            this.sourceChunks = sourceChunks;
            this.totalRelevantChunks = totalRelevantChunks;
            this.responseStyle = responseStyle;
            this.llamaStatus = llamaStatus;
        }
        
        // Getters
        public String getQuestion() { return question; }
        public String getAnswer() { return answer; }
        public List<String> getSourceChunks() { return sourceChunks; }
        public int getTotalRelevantChunks() { return totalRelevantChunks; }
        public String getResponseStyle() { return responseStyle; }
        public boolean isLlamaStatus() { return llamaStatus; }
        
        // Setters
        public void setQuestion(String question) { this.question = question; }
        public void setAnswer(String answer) { this.answer = answer; }
        public void setSourceChunks(List<String> sourceChunks) { this.sourceChunks = sourceChunks; }
        public void setTotalRelevantChunks(int totalRelevantChunks) { this.totalRelevantChunks = totalRelevantChunks; }
        public void setResponseStyle(String responseStyle) { this.responseStyle = responseStyle; }
        public void setLlamaStatus(boolean llamaStatus) { this.llamaStatus = llamaStatus; }
    }
}