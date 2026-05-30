package com.localagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.localagent.dto.ApiDtos.PlanRequest;
import com.localagent.dto.ApiDtos.PlanResponse;
import java.util.Map;
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
    void asksForClarificationWhenFamilyMessageDoesNotIncludeExplicitGroupCount() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                "今天下午在大连星海广场附近带老婆孩子出去玩4小时，预算600元，孩子5岁，老婆在减肥，别离家太远"
        );

        assertThat(response.intent().get("scenario")).isEqualTo("family");
        assertThat(response.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(response.options()).isEmpty();
        assertThat(response.clarification().toString()).contains("同行人");
    }

    @Test
    void parsesCompleteFamilyIntentAndBuildsThreeExecutableOptions() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                new PlanRequest(
                        "今天下午在大连星海广场附近带老婆孩子出去玩4小时，预算600元，孩子5岁，老婆在减肥，别离家太远",
                        null,
                        null,
                        Map.of("group", "两个大人一个孩子", "timeWindow", "14:00"),
                        null
                )
        );

        assertThat(response.intent().get("scenario")).isEqualTo("family");
        assertThat(response.options()).hasSize(3);
        response.options().forEach(option -> {
            assertThat((Integer) option.get("totalMinutes")).isLessThanOrEqualTo(300);
            List<?> timeline = (List<?>) option.get("timeline");
            assertThat(timeline).hasSizeGreaterThanOrEqualTo(3);
            assertThat(timeline.toString()).contains("餐饮");
            assertThat(timeline.toString()).contains("活动");
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
        PlanResponse response = planningService.createPlan("test-token", "今天下午2点在大连中山区和4个朋友玩密室再吃饭4小时，预算800元");
        assertThat(response.trace()).anyMatch(t -> "ExceptionRecoveryTool".equals(t.get("tool")));

        PlanResponse confirmed = planningService.confirm(response.planId(), 1);
        assertThat(confirmed.status()).isEqualTo("COMPLETED");
        assertThat(confirmed.execution()).containsKey("orders");
        assertThat(confirmed.execution()).containsKey("shareMessage");
        assertThat(confirmed.execution().toString()).doesNotContain("confirmed", "booking", "delivery");
    }

    @Test
    void returnsAvailableRealisticOptionsWhenRequestedCountIsTooHigh() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                new PlanRequest(
                        "下午想安排文化展览和清淡晚餐，步行距离尽量短，不要太吵",
                        5,
                        "丰富",
                        Map.of(
                                "location", "大连金石滩大连民族大学附近",
                                "timeWindow", "11.00",
                                "group", "3个男人",
                                "duration", "4小时左右",
                                "budget", "300",
                                "preferences", "文化展览和清淡晚餐",
                                "excludedPois", List.of("家庭海鲜餐厅", "四人烧烤餐厅", "大连科学剧场",
                                        "沉浸式密室", "新影艺术馆", "甜品补给站")
                        ),
                        null
                )
        );

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.intent()).containsKey("userFacts");
        assertThat(response.intent()).containsKey("derived");
        assertThat(response.intent()).containsKey("poiSearchStrategy");
        assertThat(response.options()).isNotEmpty();
        assertThat(response.options().size()).isLessThan(5);
        assertThat(response.warnings().toString()).contains("真实地点和路线约束");
    }
}
