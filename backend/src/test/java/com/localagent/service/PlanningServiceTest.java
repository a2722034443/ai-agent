package com.localagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.localagent.dto.ApiDtos.PlanResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PlanningServiceTest {
    @Autowired
    private PlanningService planningService;

    @Test
    void parsesFamilyIntentAndBuildsThreeExecutableOptions() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                "\u4eca\u5929\u4e0b\u5348\u5e26\u8001\u5a46\u5b69\u5b50\u51fa\u53bb\u73a9\uff0c\u5b69\u5b505\u5c81\uff0c\u8001\u5a46\u5728\u51cf\u80a5\uff0c\u522b\u79bb\u5bb6\u592a\u8fdc"
        );

        assertThat(response.intent().get("scenario")).isEqualTo("family");
        assertThat(response.options()).hasSize(3);
        response.options().forEach(option -> {
            assertThat((Integer) option.get("totalMinutes")).isBetween(240, 360);
            assertThat((List<?>) option.get("timeline")).hasSizeGreaterThanOrEqualTo(3);
        });
        assertThat(response.trace()).extracting(t -> t.get("tool")).contains("PoiSearchTool", "RouteEstimateTool");
        assertThat(response.options().toString()).doesNotContain(
                "Family Forest Lab",
                "Green Garden Bistro",
                "Xinghai Bay",
                "Reliable nearby plan"
        );
    }

    @Test
    void recoversNoSeatNoTicketAndCanConfirm() {
        PlanResponse response = planningService.createPlan("test-token", "\u4eca\u5929\u4e0b\u5348\u548c4\u4e2a\u670b\u53cb\u73a9\u5bc6\u5ba4\u518d\u5403\u996d");
        assertThat(response.trace()).anyMatch(t -> "ExceptionRecoveryTool".equals(t.get("tool")));

        PlanResponse confirmed = planningService.confirm(response.planId(), 1);
        assertThat(confirmed.status()).isEqualTo("COMPLETED");
        assertThat(confirmed.execution()).containsKey("orders");
        assertThat(confirmed.execution()).containsKey("shareMessage");
        assertThat(confirmed.execution().toString()).doesNotContain("confirmed", "booking", "delivery");
    }
}
