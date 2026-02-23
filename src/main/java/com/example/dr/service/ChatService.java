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

/**
 * Service orchestrating the full RAG (Retrieval-Augmented Generation) pipeline:
 * document retrieval, conversation management, LLM call dispatch, and branched message history.
 * Conversations are persisted and support ChatGPT-style edit branching via parent message IDs.
 */
@Service
public class ChatService {

    private final DocumentService documentService;
    private final LLMService llmService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;

    private static final String OUT_OF_CONTEXT_MSG = "I’m unable to locate information related to your question in the uploaded document.";

    /** Matches questions requesting a brief or concise answer. */
    private static final Pattern SHORT_PATTERNS = Pattern.compile("(?i).*(brief|short|quick|summary|concise|simple|in short).*");

    /** Matches questions requesting a detailed or comprehensive answer. */
    private static final Pattern DETAILED_PATTERNS = Pattern.compile("(?i).*(detail|explain|elaborate|comprehensive|in depth|full|complete).*");

    /** Matches hypothetical or 'what-if' questions. */
    private static final Pattern SCENARIO_PATTERNS = Pattern.compile("(?i).*(what if|what happens|suppose|imagine|assuming|hypothetical|scenario|if .+ then|in case).*");

    /** Matches questions asking to compare or contrast. */
    private static final Pattern COMPARISON_PATTERNS = Pattern.compile("(?i).*(difference between|compare|versus|vs\\.?|how does .+ differ|which is better|contrast).*");

    /** Matches questions requiring multiple facts to be combined. */
    private static final Pattern MULTI_HOP_PATTERNS = Pattern.compile("(?i).*(and also|as well as|in addition|how many .+ if|calculate|total|combined).*");

    public ChatService(DocumentService documentService, LLMService llmService, ConversationRepository conversationRepository, ChatMessageRepository chatMessageRepository) {
        this.documentService = documentService;
        this.llmService = llmService;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * @param file      uploaded document file
     * @param chunkSize target chunk size in characters
     * @return ID of the persisted document
     * @throws Exception if processing fails
     */
    public Long loadDocument(MultipartFile file, int chunkSize) throws Exception {
        return documentService.processDocument(file, chunkSize);
    }

    /**
     * Synchronous (non-streaming) question answering.
     *
     * @param question the user question
     * @param topK     number of document chunks to retrieve
     * @return single-element list containing the generated answer
     * @throws Exception if chunk retrieval or LLM call fails
     */
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

        if (relevantChunks.isEmpty()) {
            System.out.println("No relevant chunks found — returning out-of-context message.");
            return List.of(OUT_OF_CONTEXT_MSG);
        }

        long llmStartTime = System.currentTimeMillis();
        String contextualAnswer = llmService.generateAnswer(question, relevantChunks, responseStyle, questionType);
        long llmEndTime = System.currentTimeMillis();
        System.out.println("Time to generate LLM answer (" + responseStyle + "): " + (llmEndTime - llmStartTime) + " ms");

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("=== Total answer generation time (" + responseStyle + "): " + totalTime + " ms ===\n");
        return List.of(contextualAnswer);
    }

    /**
     * Streaming question answering — saves user/assistant messages and emits tokens via callbacks.
     *
     * @param question            the user question
     * @param topK                number of document chunks to retrieve
     * @param conversationId      existing conversation to continue, or {@code null} to create a new one
     * @param parentId            parent message ID for edit branching, or {@code null} for auto-detect
     * @param isEdit              {@code true} if this is an edit of an existing branch
     * @param tokenConsumer       called with each generated token string
     * @param onUserSaved         called once the user message is persisted
     * @param onAssistantSaved    called once the assistant message is persisted
     * @param onError             called on any error during streaming
     * @param onConversationReady called once with the conversation ID
     */
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

            if (relevantChunks.isEmpty()) {
                System.out.println("No relevant chunks found — streaming out-of-context message.");
                tokenConsumer.accept(OUT_OF_CONTEXT_MSG);
                persistAssistantMessage(convId, userMessage, OUT_OF_CONTEXT_MSG, onAssistantSaved, onError);
                return;
            }

            StringBuilder fullResponse = new StringBuilder();

            llmService.generateAnswerStreaming(enrichedQuestion, relevantChunks, responseStyle, questionType, token -> {
                        fullResponse.append(token);
                        tokenConsumer.accept(token);}, () -> persistAssistantMessage(convId, userMessage, fullResponse.toString(), onAssistantSaved, onError),
                    onError);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * @param conversationId the conversation ID
     * @return ordered list of messages on the latest active thread
     * @throws RuntimeException if the conversation does not exist
     */
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

    /**
     * @param conversationId the conversation ID
     * @param messageId      ID of the starting message
     * @return ordered thread starting from the given message
     * @throws RuntimeException if the message is not found
     */
    public List<ThreadMessage> getThreadFrom(Long conversationId, Long messageId) {
        ChatMessage start = chatMessageRepository.findByIdAndConversationId(messageId, conversationId).orElseThrow(() -> new RuntimeException("Message not found: " + messageId));
        return buildForwardThread(conversationId, start);
    }

    /**
     * @param conversationId the conversation ID
     * @param messageId      ID of any sibling message
     * @return all sibling messages with their sibling index and total sibling count populated
     */
    public List<ThreadMessage> getMessageSiblings(Long conversationId, Long messageId) {
        ChatMessage target = chatMessageRepository.findByIdAndConversationId(messageId, conversationId).orElseThrow(() -> new RuntimeException("Message not found: " + messageId));
        List<ChatMessage> siblings = chatMessageRepository.findSiblings(conversationId, target.getParentId(), target.getRole());
        List<ThreadMessage> result = new ArrayList<>();
        int siblingCount = siblings.size();
        for (int i = 0; i < siblings.size(); i++) {
            ChatMessage msg = siblings.get(i);
            result.add(new ThreadMessage(msg.getId(), msg.getRole(), msg.getContent(), msg.getCreatedAt(), siblingCount, i + 1, msg.getParentId()));
        }
        return result;
    }

    /**
     * @return all conversations ordered by most recently updated first
     */
    public List<Conversation> getAllConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc();
    }

    /**
     * @param conversationId the conversation to delete (cascades to all messages)
     */
    @Transactional
    public void deleteConversation(Long conversationId) {
        conversationRepository.deleteById(conversationId);
    }

    /**
     * Saves the assistant response to the DB and notifies the caller via callback.
     * Used by both the normal streaming path and the out-of-context short-circuit path.
     */
    private void persistAssistantMessage(Long convId, ChatMessage userMessage, String content,
                                         Consumer<SavedMessageRef> onAssistantSaved,
                                         Consumer<Throwable> onError) {
        try {
            Conversation conv = conversationRepository.findById(convId)
                    .orElseThrow(() -> new RuntimeException("Conversation not found: " + convId));
            ChatMessage assistantMessage = chatMessageRepository.save(ChatMessage.builder()
                    .conversation(conv)
                    .role("assistant")
                    .content(content)
                    .parentId(userMessage.getId())
                    .build());
            conv.setUpdatedAt(Instant.now());
            conversationRepository.save(conv);
            if (onAssistantSaved != null) {
                onAssistantSaved.accept(buildSavedMessageRef(assistantMessage));
            }
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * @param question       used as the title if a new conversation is created
     * @param conversationId existing conversation ID, or {@code null} to create new
     * @return resolved or newly created {@link Conversation}
     */
    private Conversation resolveOrCreateConversation(String question, Long conversationId) {
        if (conversationId != null) {
            return conversationRepository.findById(conversationId).orElseThrow(() -> new RuntimeException("Conversation not found: " + conversationId));
        }
        String title = question.length() > 80 ? question.substring(0, 80) + "..." : question;
        Conversation conversation = Conversation.builder().title(title).build();
        return conversationRepository.save(conversation);
    }

    /**
     * @param conversationId the conversation ID
     * @param parentId       explicitly supplied parent, or {@code null}
     * @param isEdit         {@code true} skips auto-tail detection
     * @return the effective parent message ID to use
     */
    private Long resolveEffectiveParentId(Long conversationId, Long parentId, boolean isEdit) {
        if (parentId != null) {
            chatMessageRepository.findByIdAndConversationId(parentId, conversationId).orElseThrow(() -> new RuntimeException("Parent message " + parentId + " does not belong to conversation " + conversationId));
            return parentId;
        }
        if (isEdit) {
            return null;
        }
        return findLatestThreadTailId(conversationId).orElse(null);
    }

    /**
     * @param conversationId the conversation ID
     * @return ID of the last message on the current thread, or empty
     */
    private Optional<Long> findLatestThreadTailId(Long conversationId) {
        List<ThreadMessage> thread = getConversationThread(conversationId);
        if (thread.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(thread.get(thread.size() - 1).id());
    }

    /**
     * Prepends up to 4 ancestor messages as context to the question.
     *
     * @param question the raw user question
     * @param parentId ID of the parent message, or {@code null} for no history
     * @return enriched question string including prior turn summaries
     */
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

    /**
     * @param startMessageId ID to start walking up from
     * @param maxMessages    maximum number of ancestor messages to collect
     * @return ancestor messages in chronological order
     */
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

    /**
     * @param conversationId the conversation ID
     * @param start          the root message to start from
     * @return ordered thread following the latest sibling at each turn
     */
    private List<ThreadMessage> buildForwardThread(Long conversationId, ChatMessage start) {
        List<ThreadMessage> thread = new ArrayList<>();
        Map<String, List<ChatMessage>> siblingCache = new HashMap<>();

        ChatMessage current = start;
        while (current != null) {
            thread.add(toThreadMessage(conversationId, current, siblingCache));

            String nextRole = "user".equals(current.getRole()) ? "assistant" : "user";
            List<ChatMessage> nextSiblings = getSiblingsCached(conversationId, current.getId(), nextRole, siblingCache);
            if (nextSiblings.isEmpty()) {
                break;
            }
            current = nextSiblings.get(nextSiblings.size() - 1);
        }
        return thread;
    }

    /**
     * @param conversationId the conversation ID
     * @param message        the message to convert
     * @param siblingCache   memoisation cache for sibling queries
     * @return populated {@link ChatService.ThreadMessage}
     */
    private ThreadMessage toThreadMessage(Long conversationId, ChatMessage message, Map<String, List<ChatMessage>> siblingCache) {
        List<ChatMessage> siblings = getSiblingsCached(conversationId, message.getParentId(), message.getRole(), siblingCache);
        int siblingIndex = 1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(message.getId())) {
                siblingIndex = i + 1;
                break;
            }
        }
        return new ThreadMessage(message.getId(), message.getRole(), message.getContent(), message.getCreatedAt(), siblings.size(), siblingIndex, message.getParentId());
    }

    /**
     * @param conversationId the conversation ID
     * @param parentId       parent message ID, or {@code null} for roots
     * @param role           message role
     * @param siblingCache   memoisation cache
     * @return cached or freshly queried sibling list
     */
    private List<ChatMessage> getSiblingsCached(Long conversationId, Long parentId, String role, Map<String, List<ChatMessage>> siblingCache) {
        String key = (parentId == null ? "root" : parentId) + "|" + role;
        return siblingCache.computeIfAbsent(key, ignored -> chatMessageRepository.findSiblings(conversationId, parentId, role));
    }

    /**
     * @param message the just-persisted message
     * @return {@link SavedMessageRef} with id, parentId, siblingCount, and siblingIndex populated
     */
    private SavedMessageRef buildSavedMessageRef(ChatMessage message) {
        List<ChatMessage> siblings = chatMessageRepository.findSiblings(message.getConversation().getId(), message.getParentId(), message.getRole());
        int siblingIndex = 1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(message.getId())) {
                siblingIndex = i + 1;
                break;
            }
        }
        return new SavedMessageRef(message.getId(), message.getParentId(), siblings.size(), siblingIndex);
    }

    /**
     * @param questionType  question classification string
     * @param requestedTopK the client-requested topK value
     * @return adjusted topK value capped at 6 for complex question types
     */
    private int effectiveTopK(String questionType, int requestedTopK) {
        int normalizedTopK = Math.max(1, requestedTopK);
        if ("factual".equals(questionType)) {
            return normalizedTopK;
        }
        // Keep retrieval bounded for lower latency on complex prompts.
        return Math.min(normalizedTopK + 1, 6);
    }

    /**
     * @param question the user question
     * @return {@code "short"}, {@code "detailed"}, or {@code "normal"}
     */
    private String detectResponseStyle(String question) {
        if (SHORT_PATTERNS.matcher(question).matches()) {
            return "short";
        } else if (DETAILED_PATTERNS.matcher(question).matches()) {
            return "detailed";
        } else {
            return "normal";
        }
    }

    /**
     * @param question the user question
     * @return {@code "scenario"}, {@code "comparison"}, {@code "multi_hop"}, or {@code "factual"}
     */
    private String detectQuestionType(String question) {
        if (SCENARIO_PATTERNS.matcher(question).matches())
            return "scenario";
        if (COMPARISON_PATTERNS.matcher(question).matches())
            return "comparison";
        if (MULTI_HOP_PATTERNS.matcher(question).matches())
            return "multi_hop";
        return "factual";
    }

    /**
     * Immutable projection of a {@link com.example.dr.entity.ChatMessage} enriched with sibling navigation metadata.
     */
    public record ThreadMessage(Long id, String role, String content, Instant createdAt, Integer siblingCount, Integer siblingIndex, Long parentId) {}

    /**
     * Lightweight reference returned after persisting a message, used to notify the SSE stream of IDs and sibling position.
     */
    public record SavedMessageRef(Long id, Long parentId, Integer siblingCount, Integer siblingIndex) {}
}
