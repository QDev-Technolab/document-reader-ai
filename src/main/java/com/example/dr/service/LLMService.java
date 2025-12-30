package com.example.dr.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

@Service
public class LLMService {
    
    @Value("${llm.api.url:http://localhost:11434/api/generate}")
    private String ollamaUrl;
    
    @Value("${llm.model:llama3:8b}")
    private String modelName;
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public LLMService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    public String generateAnswer(String question, List<String> relevantChunks) {
        return generateAnswer(question, relevantChunks, "normal");
    }
    
    public String generateAnswer(String question, List<String> relevantChunks, String responseStyle) {
        try {
            String context = buildContext(relevantChunks);
            String prompt = buildEnhancedPrompt(question, context, responseStyle);
            
            return callLlamaAPI(prompt);
            
        } catch (Exception e) {
            e.printStackTrace();
            return "I apologize, but I'm having trouble processing your question right now. Please try again.";
        }
    }
    
    private String buildContext(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "No relevant context found.";
        }
        
        return chunks.stream()
                    .filter(chunk -> chunk != null && !chunk.trim().isEmpty())
                    .map(chunk -> chunk.trim())
                    .collect(Collectors.joining("\n\n---\n\n"));
    }
    
    private String buildEnhancedPrompt(String question, String context, String responseStyle) {
        String styleInstruction = getStyleInstruction(responseStyle);
        
        return String.format("""
            You are a highly accurate document assistant. Your job is to provide precise, factual answers based on the document content.
            
            DOCUMENT CONTEXT (Multiple sections separated by ---):
            %s
            
            USER QUESTION: %s
            
            CRITICAL INSTRUCTIONS:
            1. Read ALL the context sections carefully before answering
            2. If the question asks for specific information (like times, dates, numbers, policies), search for EXACT details in the context
            3. %s
            4. If you find specific information (like "office hours are 9 AM to 6 PM"), include it exactly as stated
            5. If the context contains the information but it's not clear, say "The document mentions [what you found] but specific details are not clearly stated"
            6. If the context doesn't contain the specific information requested, clearly state "The document does not contain specific information about [what they asked]"
            7. Use natural language but prioritize accuracy over eloquence
            8. Don't make assumptions or fill in gaps with general knowledge
            
            ANSWER APPROACH:
            - First, scan for direct answers to the question
            - Then provide context and related information
            - Be specific when specific information is available
            - Be honest when information is missing or unclear
            
            ANSWER:
            """, context, question, styleInstruction);
    }
    
    private String getStyleInstruction(String responseStyle) {
        return switch (responseStyle.toLowerCase()) {
            case "short", "brief", "concise" -> 
                "Keep your response concise and to the point - focus on the most important information first, 1-2 paragraphs maximum";
            case "detailed", "long", "comprehensive" -> 
                "Provide a comprehensive, detailed response with full explanations and all relevant context - use multiple paragraphs to cover all aspects";
            case "normal", "default" -> 
                "Provide a balanced response - detailed enough to be helpful but not overly long. Include specific details when available";
            default -> 
                "Adjust response length based on the complexity of the question and available information";
        };
    }
    
    private String callLlamaAPI(String prompt) throws Exception {
        var requestBody = objectMapper.createObjectNode();
        requestBody.put("model", modelName);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);
        
        // Optimize for accuracy over creativity
        var options = objectMapper.createObjectNode();
        options.put("temperature", 0.05); // Very low for factual accuracy
        options.put("top_p", 0.85);
        options.put("top_k", 30);
        options.put("repeat_penalty", 1.15);
        options.put("num_predict", 600); // Allow longer responses
        ArrayNode stopArray = objectMapper.createArrayNode();
        stopArray.add("USER QUESTION:");
        stopArray.add("DOCUMENT CONTEXT:");
        stopArray.add("CRITICAL INSTRUCTIONS:");
        options.put("stop", stopArray); // Stop on these phrases
        requestBody.set("options", options);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<String> request = new HttpEntity<>(
            objectMapper.writeValueAsString(requestBody), 
            headers
        );
        
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(ollamaUrl, request, String.class);
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            
            if (jsonResponse.has("response")) {
                return cleanupResponse(jsonResponse.get("response").asText());
            } else {
                return "Unable to generate response. Please ensure Ollama is running with llama3:8b model.";
            }
        } catch (Exception e) {
            return "Connection failed. Please ensure Ollama is installed and running. Error: " + e.getMessage();
        }
    }
    
    private String cleanupResponse(String response) {
        if (response == null) return "";
        
        // Clean up common LLM artifacts
        return response.trim()
                      .replaceAll("(?i)^(answer:|response:|based on the document:)\\s*", "") // Remove prefixes
                      .replaceAll("\\n{3,}", "\n\n") // Reduce excessive newlines
                      .replaceAll("(?i)(the document (says|states|mentions|indicates))\\s*", "") // Remove redundant phrases
                      .trim();
    }
    
    // Method to check if Ollama is running
    public boolean isOllamaRunning() {
        try {
            String healthUrl = ollamaUrl.replace("/api/generate", "");
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
    
    // Method to get available models
    public List<String> getAvailableModels() {
        try {
            String modelsUrl = ollamaUrl.replace("/api/generate", "/api/tags");
            ResponseEntity<String> response = restTemplate.getForEntity(modelsUrl, String.class);
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            
            if (jsonResponse.has("models")) {
                return objectMapper.convertValue(
                    jsonResponse.get("models"), 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
            }
        } catch (Exception e) {
            // Fallback
        }
        return List.of(modelName);
    }
}