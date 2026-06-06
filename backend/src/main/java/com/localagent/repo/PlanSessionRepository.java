package com.localagent.repo;

import com.localagent.model.PlanSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanSessionRepository extends JpaRepository<PlanSession, UUID> {
    Optional<PlanSession> findByIdAndSessionToken(UUID id, String sessionToken);
}
