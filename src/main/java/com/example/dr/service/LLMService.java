package com.example.dr.service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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

    @Value("${llm.timeout.connect:5000}")
    private int connectTimeout;

    @Value("${llm.timeout.read:60000}")
    private int readTimeout;

    @Value("${llm.generation.num_ctx:4096}")
    private int numCtx;

    @Value("${llm.generation.num_batch:256}")
    private int numBatch;

    @Value("${llm.generation.num_thread:0}")
    private int configuredNumThread;

    @Value("${llm.generation.num_predict.short:180}")
    private int numPredictShort;

    @Value("${llm.generation.num_predict.normal:400}")
    private int numPredictNormal;

    @Value("${llm.generation.num_predict.detailed:800}")
    private int numPredictDetailed;

    @Value("${llm.generation.stop-sequences.enabled:false}")
    private boolean stopSequencesEnabled;

    @Value("${llm.prompt.max-context-chars:4500}")
    private int maxContextLength;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private HttpClient streamingHttpClient;

    public LLMService() {
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    void initClients() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setConnectionRequestTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        this.restTemplate = new RestTemplate(factory);
        this.streamingHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeout))
            .build();

        System.out.println("[LLM] Initialized timeouts connect=" + connectTimeout + "ms read=" + readTimeout + "ms");
    }
    
    public String generateAnswer(String question, List<String> relevantChunks) {
        return generateAnswer(question, relevantChunks, "normal", "factual");
    }

    public String generateAnswer(String question, List<String> relevantChunks, String responseStyle, String questionType) {
        long startTime = System.currentTimeMillis();
        System.out.println("\n[LLM] Starting answer generation for style: " + responseStyle);

        try {
            long contextStart = System.currentTimeMillis();
            String context = buildContext(relevantChunks);
            long contextEnd = System.currentTimeMillis();
            System.out.println("[LLM] Context built in " + (contextEnd - contextStart) + " ms (length: " + context.length() + " chars)");

            long promptStart = System.currentTimeMillis();
            String prompt = buildEnhancedPrompt(question, context, responseStyle, questionType);
            long promptEnd = System.currentTimeMillis();
            System.out.println("[LLM] Prompt built in " + (promptEnd - promptStart) + " ms (length: " + prompt.length() + " chars)");

            String answer = callLlamaAPI(prompt, responseStyle, questionType);

            long totalTime = System.currentTimeMillis() - startTime;
            System.out.println("[LLM] Total LLM service time: " + totalTime + " ms\n");

            return answer;

        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            System.err.println("[LLM] Error after " + totalTime + " ms: " + e.getMessage());
            e.printStackTrace();
            return "I apologize, but I'm having trouble processing your question right now. Please try again.";
        }
    }
    
    private String buildContext(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "No relevant context found.";
        }

        // Keep prompt bounded so output tokens still fit into num_ctx.
        StringBuilder context = new StringBuilder();
        int chunkCount = 0;

        for (String chunk : chunks) {
            if (chunk == null || chunk.trim().isEmpty()) continue;

            String trimmedChunk = chunk.trim();

            // Check if adding this chunk would exceed the limit
            if (context.length() + trimmedChunk.length() + 10 > maxContextLength) {
                System.out.println("[LLM] Context limit reached. Using " + chunkCount + " chunks (truncated from " + chunks.size() + ")");
                break;
            }

            if (context.length() > 0) {
                context.append("\n\n---\n\n");
            }
            context.append(trimmedChunk);
            chunkCount++;
        }

        System.out.println("[LLM] Built context with " + chunkCount + " chunks");
        return context.toString();
    }
    
    private String buildEnhancedPrompt(String question, String context, String responseStyle, String questionType) {
        String styleInstruction = getStyleInstruction(responseStyle);
        String reasoningInstruction = getReasoningInstruction(questionType);

        return String.format("""
            %s
            %s

            Context:
            %s

            Question: %s

            Answer:""", reasoningInstruction, styleInstruction, context, question);
    }

    private String getReasoningInstruction(String questionType) {
        return switch (questionType) {
            case "scenario" -> "Answer the hypothetical scenario using the context. Apply relevant rules step by step. Use exact details.";
            case "comparison" -> "Compare the items using details from the context. Highlight key differences and similarities.";
            case "multi_hop" -> "Combine multiple pieces of information from the context to answer. Show calculations if needed.";
            default -> "Answer using the context below. Use exact details. Format with markdown: **bold** for key terms, bullet points for lists.";
        };
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
    
    private String callLlamaAPI(String prompt, String responseStyle, String questionType) throws Exception {
        long apiStartTime = System.currentTimeMillis();
        System.out.println("[LLM] Calling Ollama API...");

        var requestBody = objectMapper.createObjectNode();
        requestBody.put("model", modelName);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

        // Speed-optimized parameters
        var options = objectMapper.createObjectNode();
        options.put("temperature", 0.2);
        options.put("top_p", 0.7);
        options.put("top_k", 20);
        options.put("repeat_penalty", 1.1);
        options.put("num_ctx", numCtx);
        options.put("num_thread", resolveNumThread());
        options.put("num_batch", numBatch);

        int numPredict = resolveNumPredict(responseStyle);
        options.put("num_predict", numPredict);
        System.out.println("[LLM] Token limit for '" + responseStyle + "' (" + questionType + "): " + numPredict);

        if (stopSequencesEnabled) {
            ArrayNode stopArray = objectMapper.createArrayNode();
            stopArray.add("Question:");
            stopArray.add("Context:");
            options.set("stop", stopArray);
        }

        requestBody.put("keep_alive", -1); // Keep model loaded in memory
        requestBody.set("options", options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(
            objectMapper.writeValueAsString(requestBody),
            headers
        );

        try {
            long httpStartTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(ollamaUrl, request, String.class);
            long httpEndTime = System.currentTimeMillis();
            System.out.println("[LLM] Ollama API responded in " + (httpEndTime - httpStartTime) + " ms");

            JsonNode jsonResponse = objectMapper.readTree(response.getBody());

            if (jsonResponse.has("response")) {
                String answer = cleanupResponse(jsonResponse.get("response").asText());
                String doneReason = jsonResponse.has("done_reason")
                    ? jsonResponse.get("done_reason").asText()
                    : "unknown";
                int evalCount = jsonResponse.has("eval_count")
                    ? jsonResponse.get("eval_count").asInt(-1)
                    : -1;
                long totalApiTime = System.currentTimeMillis() - apiStartTime;
                System.out.println("[LLM] Total API processing time: " + totalApiTime + " ms, done_reason=" + doneReason + ", eval_count=" + evalCount);
                return answer;
            } else {
                return "Unable to generate response. Please ensure Ollama is running with " + modelName + " model.";
            }
        } catch (Exception e) {
            long failTime = System.currentTimeMillis() - apiStartTime;
            System.err.println("[LLM] API call failed after " + failTime + " ms: " + e.getMessage());
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
    
    /**
     * Streaming version of generateAnswer — pushes tokens to the consumer as Ollama generates them.
     * The caller is responsible for running this on an appropriate thread (e.g. virtual thread).
     */
    public void generateAnswerStreaming(String question, List<String> relevantChunks,
                                        String responseStyle, String questionType,
                                        Consumer<String> tokenConsumer,
                                        Runnable onComplete,
                                        Consumer<Throwable> onError) {
        try {
            String context = buildContext(relevantChunks);
            String prompt = buildEnhancedPrompt(question, context, responseStyle, questionType);

            // Build request body — same as callLlamaAPI but with stream: true
            var requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", true);

            var options = objectMapper.createObjectNode();
            options.put("temperature", 0.2);
            options.put("top_p", 0.7);
            options.put("top_k", 20);
            options.put("repeat_penalty", 1.1);
            options.put("num_ctx", numCtx);
            options.put("num_thread", resolveNumThread());
            options.put("num_batch", numBatch);

            int numPredict = resolveNumPredict(responseStyle);
            options.put("num_predict", numPredict);

            if (stopSequencesEnabled) {
                ArrayNode stopArray = objectMapper.createArrayNode();
                stopArray.add("Question:");
                stopArray.add("Context:");
                options.set("stop", stopArray);
            }

            requestBody.put("keep_alive", -1);
            requestBody.set("options", options);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .timeout(Duration.ofMillis(readTimeout))
                .build();

            HttpResponse<InputStream> response = streamingHttpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            String doneReason = "missing_done";
            int evalCount = -1;
            boolean doneSeen = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;

                    JsonNode node = objectMapper.readTree(line);
                    String token = node.has("response") ? node.get("response").asText() : "";
                    if (!token.isEmpty()) {
                        tokenConsumer.accept(token);
                    }

                    if (node.has("done") && node.get("done").asBoolean()) {
                        doneSeen = true;
                        doneReason = node.has("done_reason")
                            ? node.get("done_reason").asText()
                            : "unknown";
                        evalCount = node.has("eval_count")
                            ? node.get("eval_count").asInt(-1)
                            : -1;
                        break;
                    }
                }
            }

            if (!doneSeen) {
                System.err.println("[LLM] Streaming ended without done=true marker");
            } else {
                System.out.println("[LLM] Stream finished with done_reason=" + doneReason + ", eval_count=" + evalCount);
            }
            onComplete.run();

        } catch (Exception e) {
            onError.accept(e);
        }
    }

    private int resolveNumPredict(String responseStyle) {
        return switch (responseStyle.toLowerCase()) {
            case "short", "brief", "concise" -> numPredictShort;
            case "detailed", "long", "comprehensive" -> numPredictDetailed;
            default -> numPredictNormal;
        };
    }

    private int resolveNumThread() {
        if (configuredNumThread > 0) {
            return configuredNumThread;
        }
        return Runtime.getRuntime().availableProcessors();
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
