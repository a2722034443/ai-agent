package com.localagent.service;

import com.localagent.model.Poi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlanValidationService {
    public void validate(UUID planId, List<Map<String, Object>> options, List<Poi> realPois) {
        Set<String> names = new HashSet<>(realPois.stream().map(Poi::getName).toList());
        for (Map<String, Object> option : options) {
            Object timelineValue = option.get("timeline");
            if (!(timelineValue instanceof List<?> timeline)) {
                throw new PlanBlockedException(planId, "validation", BlockMessages.VALIDATION_FAILED, 422);
            }
            if (timeline.size() < 3) {
                throw new PlanBlockedException(planId, "validation",
                        "抱歉，方案至少需要包含3个真实地点", 422);
            }
            boolean hasDining = false;
            boolean hasActivity = false;
            for (Object itemValue : timeline) {
                if (!(itemValue instanceof Map<?, ?> item)) {
                    throw new PlanBlockedException(planId, "validation", BlockMessages.VALIDATION_FAILED, 422);
                }
                Object typeValue = item.get("type");
                String type = String.valueOf(typeValue == null ? "" : typeValue);
                if ("餐饮".equals(type) || "dining".equalsIgnoreCase(type)) {
                    hasDining = true;
                }
                if ("活动".equals(type) || "activity".equalsIgnoreCase(type)
                        || "娱乐".equals(type) || "文化".equals(type)) {
                    hasActivity = true;
                }
                Object placeValue = item.containsKey("name") ? item.get("name") : item.get("place");
                String place = String.valueOf(placeValue == null ? "" : placeValue);
                if (!names.contains(place)) {
                    throw new PlanBlockedException(planId, "validation",
                            "\u62b1\u6b49\uff0c\u65b9\u6848\u5305\u542b\u975e\u771f\u5b9e\u5730\u70b9\uff1a" + place, 422);
                }
            }
            if (!hasDining || !hasActivity) {
                throw new PlanBlockedException(planId, "validation",
                        "抱歉，方案必须同时包含餐饮和娱乐/文化地点", 422);
            }
        }
    }
}
