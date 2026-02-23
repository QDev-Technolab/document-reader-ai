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

/**
 * Service that communicates with a locally running Ollama instance to generate AI answers.
 * Supports both blocking and streaming (SSE) response modes. All generation parameters (model name, token limits, timeouts) are configurable via {@code application.properties}.
 */
@Service
public class LLMService {

    /** Ollama API endpoint, configurable via {@code llm.api.url}. */
    @Value("${llm.api.url:http://localhost:11434/api/generate}")
    private String ollamaUrl;

    /** Ollama model name, configurable via {@code llm.model}. */
    @Value("${llm.model:llama3:8b}")
    private String modelName;

    /** HTTP connection timeout in milliseconds. */
    @Value("${llm.timeout.connect:5000}")
    private int connectTimeout;

    /** HTTP read timeout in milliseconds (covers full streaming response). */
    @Value("${llm.timeout.read:60000}")
    private int readTimeout;

    /** Model context window size in tokens. */
    @Value("${llm.generation.num_ctx:4096}")
    private int numCtx;

    /** Batch processing size for token generation. */
    @Value("${llm.generation.num_batch:256}")
    private int numBatch;

    /**
     * CPU thread count for inference; 0 means auto-detect from available processors.
     */
    @Value("${llm.generation.num_thread:0}")
    private int configuredNumThread;

    /** Token budget for short/concise responses. */
    @Value("${llm.generation.num_predict.short:180}")
    private int numPredictShort;

    /** Token budget for normal-length responses. */
    @Value("${llm.generation.num_predict.normal:400}")
    private int numPredictNormal;

    /** Token budget for detailed/comprehensive responses. */
    @Value("${llm.generation.num_predict.detailed:800}")
    private int numPredictDetailed;

    /** Whether to inject stop sequences to prevent prompt leakage. */
    @Value("${llm.generation.stop-sequences.enabled:false}")
    private boolean stopSequencesEnabled;

    /**
     * Maximum combined character count of all context chunks passed to the prompt.
     */
    @Value("${llm.prompt.max-context-chars:4500}")
    private int maxContextLength;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private HttpClient streamingHttpClient;

    public LLMService() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Initialises the blocking {@link RestTemplate} and the streaming {@link HttpClient} with configured timeouts.
     */
    @PostConstruct
    void initClients() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setConnectionRequestTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restTemplate = new RestTemplate(factory);
        this.streamingHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeout)).build();
        System.out.println("[LLM] Initialized timeouts connect=" + connectTimeout + "ms read=" + readTimeout + "ms");
    }

    /**
     * @param question       the user question
     * @param relevantChunks document chunks to use as context
     * @param responseStyle  one of {@code short}, {@code normal}, or {@code detailed}
     * @param questionType   one of {@code factual}, {@code scenario}, {@code comparison}, or {@code multi_hop}
     * @return generated answer string, or a fallback message on error
     */
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

            int numPredict = estimateRequiredTokens(question, questionType, responseStyle);
            String answer = callLlamaAPI(prompt, numPredict, questionType);

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

    /**
     * @param chunks list of text chunks
     * @return concatenated context string, bounded by {@code maxContextLength}
     */
    private String buildContext(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "No relevant context found.";
        }

        // Keep prompt bounded so output tokens still fit into num_ctx.
        StringBuilder context = new StringBuilder();
        int chunkCount = 0;

        for (String chunk : chunks) {
            if (chunk == null || chunk.trim().isEmpty())
                continue;

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

    /**
     * @param question      the user question
     * @param context       the context string built from relevant chunks
     * @param responseStyle controls verbosity instruction prepended to the prompt
     * @param questionType  controls the reasoning instruction prepended to the prompt
     * @return formatted prompt string ready for Ollama
     */
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

    /**
     * @param questionType one of {@code scenario}, {@code comparison}, {@code multi_hop}, or {@code factual}
     * @return reasoning instruction string to prepend to the prompt
     */
    private String getReasoningInstruction(String questionType) {
        return switch (questionType) {
            case "scenario" ->
                "Answer the hypothetical scenario using the context. Apply relevant rules step by step. Use exact details.";
            case "comparison" ->
                "Compare the items using details from the context. Highlight key differences and similarities.";
            case "multi_hop" ->
                "Combine multiple pieces of information from the context to answer. Show calculations if needed.";
            default ->
                "Answer using the context below. Use exact details. Format with markdown: **bold** for key terms, bullet points for lists.";
        };
    }

    /**
     * @param responseStyle one of {@code short}, {@code brief}, {@code concise}, {@code detailed}, {@code long}, {@code comprehensive}, {@code normal}
     * @return verbosity instruction string to prepend to the prompt
     */
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

    /**
     * @param prompt       the full prompt string
     * @param numPredict   maximum tokens to generate
     * @param questionType used only for logging the token budget
     * @return generated answer, or an error message string if the call fails
     * @throws Exception on serialisation errors
     */
    private String callLlamaAPI(String prompt, int numPredict, String questionType) throws Exception {
        long apiStartTime = System.currentTimeMillis();
        System.out.println("[LLM] Calling Ollama API...");

        var requestBody = objectMapper.createObjectNode();
        requestBody.put("model", modelName);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

        var options = objectMapper.createObjectNode();
        options.put("temperature", 0.2);
        options.put("top_p", 0.7);
        options.put("top_k", 20);
        options.put("repeat_penalty", 1.1);
        options.put("num_ctx", numCtx);
        options.put("num_thread", resolveNumThread());
        options.put("num_batch", numBatch);

        options.put("num_predict", numPredict);
        System.out.println("[LLM] Dynamic token budget for (" + questionType + "): " + numPredict);

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
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

        try {
            long httpStartTime = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.postForEntity(ollamaUrl, request, String.class);
            long httpEndTime = System.currentTimeMillis();
            System.out.println("[LLM] Ollama API responded in " + (httpEndTime - httpStartTime) + " ms");
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());

            if (jsonResponse.has("response")) {
                String answer = cleanupResponse(jsonResponse.get("response").asText());
                String doneReason = jsonResponse.has("done_reason") ? jsonResponse.get("done_reason").asText() : "unknown";
                int evalCount = jsonResponse.has("eval_count") ? jsonResponse.get("eval_count").asInt(-1) : -1;
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

    /**
     * @param response raw LLM response
     * @return cleaned response with common artifacts removed
     */
    private String cleanupResponse(String response) {
        if (response == null)
            return "";

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

            int numPredict = estimateRequiredTokens(question, questionType, responseStyle);
            options.put("num_predict", numPredict);
            System.out.println("[LLM] Dynamic token budget for (" + questionType + "): " + numPredict);

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

            HttpResponse<InputStream> response = streamingHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String doneReason = "missing_done";
            int evalCount = -1;
            boolean doneSeen = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank())
                        continue;
                    JsonNode node = objectMapper.readTree(line);
                    String token = node.has("response") ? node.get("response").asText() : "";
                    if (!token.isEmpty()) {
                        tokenConsumer.accept(token);
                    }
                    if (node.has("done") && node.get("done").asBoolean()) {
                        doneSeen = true;
                        doneReason = node.has("done_reason") ? node.get("done_reason").asText() : "unknown";
                        evalCount = node.has("eval_count") ? node.get("eval_count").asInt(-1) : -1;
                        break;
                    }
                }
            }

            if (!doneSeen) {
                System.err.println("[LLM] Streaming ended without done=true marker");
            } else {
                System.out.println("[LLM] Stream finished with done_reason=" + doneReason + ", eval_count=" + evalCount);
                if ("length".equals(doneReason)) {
                    System.err.println("[LLM] WARNING: Response truncated at token limit (" + numPredict + "). Consider rephrasing as a more specific question.");
                    tokenConsumer.accept("\n\n*(Response reached the token limit. For a more complete answer, try asking a more focused question or break it into smaller parts.)*");
                }
            }
            onComplete.run();
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * Dynamically estimates the number of tokens needed for a complete response.
     * Considers response style (user preference), question type (structural complexity), and question text (keyword signals for verbosity).
     */
    private int estimateRequiredTokens(String question, String questionType, String responseStyle) {
        // Base by response style
        int base = switch (responseStyle.toLowerCase()) {
            case "short", "brief", "concise" -> numPredictShort;
            case "detailed", "long", "comprehensive" -> numPredictDetailed;
            default -> numPredictNormal;
        };

        // Multiplier for inherently verbose question types
        double typeMultiplier = switch (questionType) {
            case "scenario" -> 2.0; // needs rule extraction + application
            case "comparison" -> 1.8; // needs two sides + analysis
            case "multi_hop" -> 1.6; // needs chaining multiple facts
            default -> 1.0;
        };

        // Keyword signals that indicate a longer answer is expected
        String q = question.toLowerCase();
        int keywordBoost = 0;
        if (q.matches(".*\\b(list|all|every|each|enumerate|summarize)\\b.*"))
            keywordBoost += 250;
        if (q.matches(".*\\b(explain|describe|elaborate|discuss|detail)\\b.*"))
            keywordBoost += 200;
        if (q.matches(".*\\b(compare|difference|versus|vs\\.?|contrast)\\b.*"))
            keywordBoost += 200;
        if (q.matches(".*\\b(step|steps|procedure|process|how to|how do)\\b.*"))
            keywordBoost += 150;
        if (q.matches(".*\\b(what if|scenario|hypothetical|suppose|imagine)\\b.*"))
            keywordBoost += 200;
        if (q.matches(".*\\b(advantages|disadvantages|pros|cons|benefits)\\b.*"))
            keywordBoost += 150;

        int estimated = (int) (base * typeMultiplier) + keywordBoost;

        // Hard cap: use at most 70% of the context window so the prompt always fits
        int maxAllowed = (int) (numCtx * 0.70);
        int result = Math.min(estimated, maxAllowed);

        System.out.println("[LLM] Token estimation: base=" + base + " typeMultiplier=" + typeMultiplier + " keywordBoost=" + keywordBoost + " → " + estimated + " (capped=" + result + ")");
        return result;
    }

    /**
     * @return configured thread count, or the number of available processors if not configured
     */
    private int resolveNumThread() {
        if (configuredNumThread > 0) {
            return configuredNumThread;
        }
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * @return {@code true} if Ollama responds with a 2xx status on its root endpoint
     */
    public boolean isOllamaRunning() {
        try {
            String healthUrl = ollamaUrl.replace("/api/generate", "");
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

}
