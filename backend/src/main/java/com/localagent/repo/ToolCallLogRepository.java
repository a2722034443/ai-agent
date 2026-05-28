package com.localagent.repo;

import com.localagent.model.ToolCallLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, UUID> {
    List<ToolCallLog> findByPlanSessionIdOrderByCreatedAt(UUID planSessionId);
}
