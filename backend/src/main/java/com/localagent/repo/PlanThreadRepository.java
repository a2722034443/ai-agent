package com.localagent.repo;

import com.localagent.model.PlanThread;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanThreadRepository extends JpaRepository<PlanThread, UUID> {
    List<PlanThread> findByOwnerClientIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String ownerClientId);
    Optional<PlanThread> findByIdAndOwnerClientIdAndDeletedAtIsNull(UUID id, String ownerClientId);
}
