package com.localagent.repo;

import com.localagent.model.PlanSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanSessionRepository extends JpaRepository<PlanSession, UUID> {
}
