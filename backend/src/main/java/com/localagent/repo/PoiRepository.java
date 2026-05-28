package com.localagent.repo;

import com.localagent.model.Poi;
import com.localagent.model.PoiType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoiRepository extends JpaRepository<Poi, UUID> {
    List<Poi> findByTypeIn(List<PoiType> types);
}
