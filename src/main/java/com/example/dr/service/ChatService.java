package com.example.dr.service;

import com.example.dr.entity.ChatMessage;
import com.example.dr.entity.Conversation;
import com.example.dr.repository.ChatMessageRepository;
import com.example.dr.repository.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final EmbeddingService embeddingService;
    private final DocumentService documentService;
    private final LLMService llmService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;

    // Patterns to detect user intent for response length
    private static final Pattern SHORT_PATTERNS = Pattern.compile(
        "(?i).*(brief|short|quick|summary|concise|simple|in short).*"
    );
    private static final Pattern DETAILED_PATTERNS = Pattern.compile(
        "(?i).*(detail|explain|elaborate|comprehensive|in depth|full|complete).*"
    );

    // Patterns to detect question type for reasoning strategy
    private static final Pattern SCENARIO_PATTERNS = Pattern.compile(
        "(?i).*(what if|what happens|suppose|imagine|assuming|hypothetical|scenario|if .+ then|in case).*"
    );
    private static final Pattern COMPARISON_PATTERNS = Pattern.compile(
        "(?i).*(difference between|compare|versus|vs\\.?|how does .+ differ|which is better|contrast).*"
    );
    private static final Pattern MULTI_HOP_PATTERNS = Pattern.compile(
        "(?i).*(and also|as well as|in addition|how many .+ if|calculate|total|combined).*"
    );

    public ChatService(EmbeddingService embeddingService,
                       DocumentService documentService,
                       LLMService llmService,
                       ConversationRepository conversationRepository,
                       ChatMessageRepository chatMessageRepository) {
        this.embeddingService = embeddingService;
        this.documentService = documentService;
        this.llmService = llmService;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    public Long loadDocument(MultipartFile file, int chunkSize) throws Exception {
        return documentService.processDocument(file, chunkSize);
    }

    public List<String> answer(String question, int topK) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("=== Starting answer generation ===");

        String responseStyle = detectResponseStyle(question);
        String questionType = detectQuestionType(question);
        System.out.println("Response style: " + responseStyle + ", Question type: " + questionType);

        int effectiveTopK = effectiveTopK(questionType, topK);

        long chunkStartTime = System.currentTimeMillis();
        List<String> relevantChunks = documentService.findRelevantChunks(question, effectiveTopK);
        long chunkEndTime = System.currentTimeMillis();
        System.out.println("Time to find relevant chunks: " + (chunkEndTime - chunkStartTime) + " ms, count: " + relevantChunks.size());

        long llmStartTime = System.currentTimeMillis();
        String contextualAnswer = llmService.generateAnswer(question, relevantChunks, responseStyle, questionType);
        long llmEndTime = System.currentTimeMillis();
        System.out.println("Time to generate LLM answer (" + responseStyle + "): " + (llmEndTime - llmStartTime) + " ms");

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("=== Total answer generation time (" + responseStyle + "): " + totalTime + " ms ===\n");

        return List.of(contextualAnswer);
    }

    public List<String> answerWithStyle(String question, String style, int topK) throws Exception {
        long startTime = System.currentTimeMillis();
        String questionType = detectQuestionType(question);
        System.out.println("=== Starting answer generation with style: " + style + ", type: " + questionType + " ===");

        int effectiveTopK = effectiveTopK(questionType, topK);

        long chunkStartTime = System.currentTimeMillis();
        List<String> relevantChunks = documentService.findRelevantChunks(question, effectiveTopK);
        long chunkEndTime = System.currentTimeMillis();
        System.out.println("Time to find relevant chunks: " + (chunkEndTime - chunkStartTime) + " ms");

        long llmStartTime = System.currentTimeMillis();
        String contextualAnswer = llmService.generateAnswer(question, relevantChunks, style, questionType);
        long llmEndTime = System.currentTimeMillis();
        System.out.println("Time to generate LLM answer (" + style + "): " + (llmEndTime - llmStartTime) + " ms");

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("=== Total answer generation time (" + style + "): " + totalTime + " ms ===\n");

        return List.of(contextualAnswer);
    }

    public ChatResponse answerWithDetails(String question, int topK) throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("=== Starting detailed answer generation ===");

        String responseStyle = detectResponseStyle(question);
        String questionType = detectQuestionType(question);
        System.out.println("Response style: " + responseStyle + ", Question type: " + questionType);

        int effectiveTopK = effectiveTopK(questionType, topK);

        long chunkStartTime = System.currentTimeMillis();
        List<String> relevantChunks = documentService.findRelevantChunks(question, effectiveTopK);
        long chunkEndTime = System.currentTimeMillis();
        System.out.println("Time to find relevant chunks: " + (chunkEndTime - chunkStartTime) + " ms");

        long llmStartTime = System.currentTimeMillis();
        String llmAnswer = llmService.generateAnswer(question, relevantChunks, responseStyle, questionType);
        long llmEndTime = System.currentTimeMillis();
        System.out.println("Time to generate LLM answer (" + responseStyle + "): " + (llmEndTime - llmStartTime) + " ms");

        // Get top relevant chunks for reference
        List<String> topChunks = relevantChunks.stream()
            .limit(topK)
            .collect(Collectors.toList());

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("=== Total detailed answer generation time (" + responseStyle + "): " + totalTime + " ms ===\n");

        return new ChatResponse(
            question,
            llmAnswer,
            topChunks,
            relevantChunks.size(),
            responseStyle,
            llmService.isOllamaRunning()
        );
    }

    public void answerStreaming(String question, int topK,
                                Consumer<String> tokenConsumer,
                                Runnable onComplete,
                                Consumer<Throwable> onError) {
        answerStreaming(
            question,
            topK,
            null,
            null,
            false,
            tokenConsumer,
            null,
            ref -> onComplete.run(),
            onError,
            null
        );
    }

    public void answerStreaming(String question, int topK, Long conversationId,
                                Consumer<String> tokenConsumer,
                                Runnable onComplete,
                                Consumer<Throwable> onError,
                                Consumer<Long> onConversationReady) {
        answerStreaming(
            question,
            topK,
            conversationId,
            null,
            false,
            tokenConsumer,
            null,
            ref -> onComplete.run(),
            onError,
            onConversationReady
        );
    }

    public void answerStreaming(String question,
                                int topK,
                                Long conversationId,
                                Long parentId,
                                Boolean isEdit,
                                Consumer<String> tokenConsumer,
                                Consumer<SavedMessageRef> onUserSaved,
                                Consumer<SavedMessageRef> onAssistantSaved,
                                Consumer<Throwable> onError,
                                Consumer<Long> onConversationReady) {
        try {
            Conversation conversation = resolveOrCreateConversation(question, conversationId);
            Long convId = conversation.getId();

            if (onConversationReady != null) {
                onConversationReady.accept(convId);
            }

            Long effectiveParentId = resolveEffectiveParentId(convId, parentId, Boolean.TRUE.equals(isEdit));

            ChatMessage userMessage = chatMessageRepository.save(ChatMessage.builder()
                .conversation(conversation)
                .role("user")
                .content(question)
                .parentId(effectiveParentId)
                .build());

            if (onUserSaved != null) {
                onUserSaved.accept(buildSavedMessageRef(userMessage));
            }

            String enrichedQuestion = buildHistoryAwareQuestion(question, effectiveParentId);
            String responseStyle = detectResponseStyle(question);
            String questionType = detectQuestionType(question);
            int effectiveTopK = effectiveTopK(questionType, topK);
            List<String> relevantChunks = documentService.findRelevantChunks(question, effectiveTopK);
            StringBuilder fullResponse = new StringBuilder();

            llmService.generateAnswerStreaming(
                enrichedQuestion,
                relevantChunks,
                responseStyle,
                questionType,
                token -> {
                    fullResponse.append(token);
                    tokenConsumer.accept(token);
                },
                () -> {
                    try {
                        Conversation conv = conversationRepository.findById(convId)
                            .orElseThrow(() -> new RuntimeException("Conversation not found: " + convId));

                        ChatMessage assistantMessage = chatMessageRepository.save(ChatMessage.builder()
                            .conversation(conv)
                            .role("assistant")
                            .content(fullResponse.toString())
                            .parentId(userMessage.getId())
                            .build());

                        conv.setUpdatedAt(Instant.now());
                        conversationRepository.save(conv);

                        if (onAssistantSaved != null) {
                            onAssistantSaved.accept(buildSavedMessageRef(assistantMessage));
                        }
                    } catch (Exception completeEx) {
                        onError.accept(completeEx);
                    }
                },
                onError
            );
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    public List<ThreadMessage> getConversationThread(Long conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw new RuntimeException("Conversation not found: " + conversationId);
        }

        List<ChatMessage> roots = chatMessageRepository.findSiblings(conversationId, null, "user");
        if (roots.isEmpty()) {
            return List.of();
        }

        ChatMessage latestRoot = roots.get(roots.size() - 1);
        return buildForwardThread(conversationId, latestRoot);
    }

    public List<ThreadMessage> getThreadFrom(Long conversationId, Long messageId) {
        ChatMessage start = chatMessageRepository.findByIdAndConversationId(messageId, conversationId)
            .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));
        return buildForwardThread(conversationId, start);
    }

    public List<ThreadMessage> getMessageSiblings(Long conversationId, Long messageId) {
        ChatMessage target = chatMessageRepository.findByIdAndConversationId(messageId, conversationId)
            .orElseThrow(() -> new RuntimeException("Message not found: " + messageId));

        List<ChatMessage> siblings = chatMessageRepository.findSiblings(
            conversationId,
            target.getParentId(),
            target.getRole()
        );

        List<ThreadMessage> result = new ArrayList<>();
        int siblingCount = siblings.size();
        for (int i = 0; i < siblings.size(); i++) {
            ChatMessage msg = siblings.get(i);
            result.add(new ThreadMessage(
                msg.getId(),
                msg.getRole(),
                msg.getContent(),
                msg.getCreatedAt(),
                siblingCount,
                i + 1,
                msg.getParentId()
            ));
        }
        return result;
    }

    // ---- Conversation CRUD ----

    public List<Conversation> getAllConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc();
    }

    public List<ChatMessage> getConversationMessages(Long conversationId) {
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public void deleteConversation(Long conversationId) {
        conversationRepository.deleteById(conversationId);
    }

    @Transactional
    public Conversation renameConversation(Long conversationId, String title) {
        Conversation conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new RuntimeException("Conversation not found"));
        conv.setTitle(title);
        return conversationRepository.save(conv);
    }

    private Conversation resolveOrCreateConversation(String question, Long conversationId) {
        if (conversationId != null) {
            return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found: " + conversationId));
        }

        String title = question.length() > 80 ? question.substring(0, 80) + "..." : question;
        Conversation conversation = Conversation.builder().title(title).build();
        return conversationRepository.save(conversation);
    }

    private Long resolveEffectiveParentId(Long conversationId, Long parentId, boolean isEdit) {
        if (parentId != null) {
            chatMessageRepository.findByIdAndConversationId(parentId, conversationId)
                .orElseThrow(() -> new RuntimeException(
                    "Parent message " + parentId + " does not belong to conversation " + conversationId
                ));
            return parentId;
        }

        if (isEdit) {
            return null;
        }

        return findLatestThreadTailId(conversationId).orElse(null);
    }

    private Optional<Long> findLatestThreadTailId(Long conversationId) {
        List<ThreadMessage> thread = getConversationThread(conversationId);
        if (thread.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(thread.get(thread.size() - 1).id());
    }

    private String buildHistoryAwareQuestion(String question, Long parentId) {
        if (parentId == null) {
            return question;
        }

        List<ChatMessage> historyMessages = collectAncestorMessages(parentId, 4);
        if (historyMessages.isEmpty()) {
            return question;
        }

        StringBuilder history = new StringBuilder("Previous conversation:\n");
        for (ChatMessage msg : historyMessages) {
            history.append("user".equals(msg.getRole()) ? "User: " : "Assistant: ");
            String content = msg.getContent();
            if (content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            history.append(content).append("\n");
        }
        history.append("\nCurrent question: ");
        return history + question;
    }

    private List<ChatMessage> collectAncestorMessages(Long startMessageId, int maxMessages) {
        List<ChatMessage> reverseOrdered = new ArrayList<>();
        Long currentId = startMessageId;

        while (currentId != null && reverseOrdered.size() < maxMessages) {
            Optional<ChatMessage> currentOpt = chatMessageRepository.findById(currentId);
            if (currentOpt.isEmpty()) {
                break;
            }
            ChatMessage current = currentOpt.get();
            reverseOrdered.add(current);
            currentId = current.getParentId();
        }

        Collections.reverse(reverseOrdered);
        return reverseOrdered;
    }

    private List<ThreadMessage> buildForwardThread(Long conversationId, ChatMessage start) {
        List<ThreadMessage> thread = new ArrayList<>();
        Map<String, List<ChatMessage>> siblingCache = new HashMap<>();

        ChatMessage current = start;
        while (current != null) {
            thread.add(toThreadMessage(conversationId, current, siblingCache));

            String nextRole = "user".equals(current.getRole()) ? "assistant" : "user";
            List<ChatMessage> nextSiblings = getSiblingsCached(
                conversationId,
                current.getId(),
                nextRole,
                siblingCache
            );

            if (nextSiblings.isEmpty()) {
                break;
            }

            current = nextSiblings.get(nextSiblings.size() - 1);
        }

        return thread;
    }

    private ThreadMessage toThreadMessage(Long conversationId,
                                          ChatMessage message,
                                          Map<String, List<ChatMessage>> siblingCache) {
        List<ChatMessage> siblings = getSiblingsCached(
            conversationId,
            message.getParentId(),
            message.getRole(),
            siblingCache
        );

        int siblingIndex = 1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(message.getId())) {
                siblingIndex = i + 1;
                break;
            }
        }

        return new ThreadMessage(
            message.getId(),
            message.getRole(),
            message.getContent(),
            message.getCreatedAt(),
            siblings.size(),
            siblingIndex,
            message.getParentId()
        );
    }

    private List<ChatMessage> getSiblingsCached(Long conversationId,
                                                Long parentId,
                                                String role,
                                                Map<String, List<ChatMessage>> siblingCache) {
        String key = (parentId == null ? "root" : parentId) + "|" + role;
        return siblingCache.computeIfAbsent(
            key,
            ignored -> chatMessageRepository.findSiblings(conversationId, parentId, role)
        );
    }

    private SavedMessageRef buildSavedMessageRef(ChatMessage message) {
        List<ChatMessage> siblings = chatMessageRepository.findSiblings(
            message.getConversation().getId(),
            message.getParentId(),
            message.getRole()
        );

        int siblingIndex = 1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(message.getId())) {
                siblingIndex = i + 1;
                break;
            }
        }

        return new SavedMessageRef(message.getId(), message.getParentId(), siblings.size(), siblingIndex);
    }

    private int effectiveTopK(String questionType, int requestedTopK) {
        int normalizedTopK = Math.max(1, requestedTopK);
        if ("factual".equals(questionType)) {
            return normalizedTopK;
        }
        // Keep retrieval bounded for lower latency on complex prompts.
        return Math.min(normalizedTopK + 1, 6);
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

    private String detectQuestionType(String question) {
        if (SCENARIO_PATTERNS.matcher(question).matches()) return "scenario";
        if (COMPARISON_PATTERNS.matcher(question).matches()) return "comparison";
        if (MULTI_HOP_PATTERNS.matcher(question).matches()) return "multi_hop";
        return "factual";
    }

    public record ThreadMessage(Long id,
                                String role,
                                String content,
                                Instant createdAt,
                                Integer siblingCount,
                                Integer siblingIndex,
                                Long parentId) {
    }

    public record SavedMessageRef(Long id,
                                  Long parentId,
                                  Integer siblingCount,
                                  Integer siblingIndex) {
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
