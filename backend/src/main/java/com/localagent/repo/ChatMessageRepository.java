package com.localagent.repo;

import com.localagent.model.ChatMessage;
import com.localagent.model.ChatMessageKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findByThreadIdOrderBySequenceNoAsc(UUID threadId);
    Optional<ChatMessage> findTopByThreadIdOrderBySequenceNoDesc(UUID threadId);
    Optional<ChatMessage> findTopByThreadIdAndKindInOrderBySequenceNoDesc(UUID threadId, List<ChatMessageKind> kinds);
}
