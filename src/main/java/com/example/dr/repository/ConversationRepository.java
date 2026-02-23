package com.example.dr.repository;

import com.example.dr.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link com.example.dr.entity.Conversation} entities.
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * Returns all conversations ordered by most recently updated first.
     *
     * @return list of conversations, newest first
     */
    List<Conversation> findAllByOrderByUpdatedAtDesc();
}
