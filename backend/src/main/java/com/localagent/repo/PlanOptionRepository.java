package com.localagent.repo;

import com.localagent.model.PlanOption;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanOptionRepository extends JpaRepository<PlanOption, UUID> {
    List<PlanOption> findByPlanSessionIdOrderByRankNo(UUID planSessionId);
}
