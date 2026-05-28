package com.localagent.repo;

import com.localagent.model.MockOrder;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MockOrderRepository extends JpaRepository<MockOrder, UUID> {
    List<MockOrder> findByPlanSessionId(UUID planSessionId);
}
