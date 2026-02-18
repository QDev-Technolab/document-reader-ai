package com.example.dr.repository;

import com.example.dr.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    List<ChatMessage> findByConversationIdAndParentIdIsNullOrderByCreatedAtAsc(Long conversationId);

    List<ChatMessage> findByParentIdOrderByCreatedAtAsc(Long parentId);

    Optional<ChatMessage> findByIdAndConversationId(Long id, Long conversationId);

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
    List<ChatMessage> findSiblings(@Param("conversationId") Long conversationId,
                                   @Param("parentId") Long parentId,
                                   @Param("role") String role);
}
