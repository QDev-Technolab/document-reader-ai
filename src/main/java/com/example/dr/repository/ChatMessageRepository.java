package com.example.dr.repository;

import com.example.dr.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link com.example.dr.entity.ChatMessage} entities.
 * Provides queries for navigating the branched message tree used for edit history.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

  /**
   * @param id             the message ID
   * @param conversationId the conversation the message must belong to
   * @return the matching message, or empty if not found or the message belongs to a different conversation
   */
  Optional<ChatMessage> findByIdAndConversationId(Long id, Long conversationId);

  /**
   * @param conversationId the conversation to search within
   * @param parentId       parent message ID; pass {@code null} to find root-level messages
   * @param role           message role filter — {@code "user"} or {@code "assistant"}
   * @return all sibling messages ordered by {@code createdAt} ascending, then {@code id} ascending
   */
  @Query("""
      select m from ChatMessage m
      where m.conversation.id = :conversationId
        and m.role = :role
        and (
          (:parentId is null and m.parentId is null) or
          (:parentId is not null and m.parentId = :parentId)
        )
      order by m.createdAt asc, m.id asc
      """)
  List<ChatMessage> findSiblings(@Param("conversationId") Long conversationId, @Param("parentId") Long parentId, @Param("role") String role);
}