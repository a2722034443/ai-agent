package com.localagent.repo;

import com.localagent.model.FeedbackEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackEventRepository extends JpaRepository<FeedbackEvent, UUID> {
}
