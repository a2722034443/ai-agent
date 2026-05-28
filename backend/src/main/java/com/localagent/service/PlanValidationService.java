package com.localagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localagent.model.Poi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PlanValidationService {
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]{3,}");
    private static final Set<String> ALLOWED_WORDS = Set.of(
            "API", "GPS", "WiFi", "ID", "POI", "URL",
            "rank", "score", "name", "tagline", "timeline", "time", "type", "subtype", "address",
            "durationMinutes", "avgPrice", "rating", "reason", "lng", "lat", "kidFriendly",
            "lowCalorie", "totalMinutes", "budgetEstimate", "route", "fitReasons", "riskNotes",
            "executionList", "firstStop", "diningName", "lastStop", "travelMinutes", "distanceKm",
            "provider", "mode", "sourceUrl", "externalStatus", "true", "false", "null"
    );

    private final ObjectMapper objectMapper;

    public PlanValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(UUID planId, List<Map<String, Object>> options, List<Poi> realPois) {
        Set<String> names = new HashSet<>(realPois.stream().map(Poi::getName).toList());
        for (Map<String, Object> option : options) {
            ensureChinese(planId, option.get("name"));
            ensureChinese(planId, option.get("tagline"));
            ensureChinese(planId, option.get("fitReasons"));
            ensureChinese(planId, option.get("riskNotes"));
            ensureChinese(planId, option.get("executionList"));
            Object timelineValue = option.get("timeline");
            if (!(timelineValue instanceof List<?> timeline)) {
                throw new PlanBlockedException(planId, "validation", BlockMessages.VALIDATION_FAILED, 422);
            }
            for (Object itemValue : timeline) {
                if (!(itemValue instanceof Map<?, ?> item)) {
                    throw new PlanBlockedException(planId, "validation", BlockMessages.VALIDATION_FAILED, 422);
                }
                Object placeValue = item.containsKey("name") ? item.get("name") : item.get("place");
                String place = String.valueOf(placeValue == null ? "" : placeValue);
                if (!names.contains(place)) {
                    throw new PlanBlockedException(planId, "validation",
                            "\u62b1\u6b49\uff0c\u65b9\u6848\u5305\u542b\u975e\u771f\u5b9e\u5730\u70b9\uff1a" + place, 422);
                }
                ensureChinese(planId, item.get("type"));
                ensureChinese(planId, item.get("name"));
                ensureChinese(planId, item.get("subtype"));
                ensureChinese(planId, item.get("address"));
                ensureChinese(planId, item.get("reason"));
            }
        }
    }

    private void ensureChinese(UUID planId, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            var matcher = ENGLISH_WORD.matcher(json);
            while (matcher.find()) {
                String word = matcher.group();
                if (!ALLOWED_WORDS.contains(word)) {
                    throw new PlanBlockedException(planId, "validation",
                            "\u62b1\u6b49\uff0c\u65b9\u6848\u5305\u542b\u975e\u4e2d\u6587\u5185\u5bb9\uff1a" + word, 422);
                }
            }
        } catch (PlanBlockedException e) {
            throw e;
        } catch (Exception e) {
            throw new PlanBlockedException(planId, "validation", BlockMessages.VALIDATION_FAILED, 422);
        }
    }
}
