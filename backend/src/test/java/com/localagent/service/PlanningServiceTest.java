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
                        null,
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
            @SuppressWarnings("unchecked")
            Map<String, Object> route = (Map<String, Object>) option.get("route");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> segments = (List<Map<String, Object>>) route.get("segments");
            boolean includesOrigin = Boolean.TRUE.equals(route.get("includesOrigin"));
            assertThat(segments).hasSize(includesOrigin ? timeline.size() : timeline.size() - 1);
            for (int i = 0; i < segments.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stop = (Map<String, Object>) timeline.get(includesOrigin ? i : i + 1);
                assertThat(segments.get(i).get("to")).isEqualTo(stop.get("name"));
            }
            assertThat(option.get("name")).isNotIn("稳妥轻松方案", "体验丰富方案", "备用省心方案");
            assertThat(option.get("routeSummary")).asString()
                    .contains("按地图顺序")
                    .doesNotContain("适合本地短时出行", "预算和节奏更稳");
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
                        null,
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

    @Test
    void complexWalkingTimeWindowDoesNotAskSoloGroupAndKeepsHardEnd() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                "我现在在大连星海广场，今天上午只有3小时的空闲时间（10:00-13:00），想先逛一下大连世界博览广场，然后吃一顿本地的海鲜餐，之后再买杯咖啡，要顺路不要绕路，预算200以内，全程步行就可以，不想坐车。"
        );

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.clarification().toString()).doesNotContain("同行人");
        assertThat(response.intent().get("group").toString()).contains("单人", "total=1");
        assertThat(response.intent().get("hard_constraints").toString()).contains("全程步行");
        assertThat(response.intent().get("poiSearchStrategy").toString()).contains("大连世界博览广场", "海鲜", "咖啡");
        response.options().forEach(option ->
                assertThat((Integer) option.get("totalMinutes")).isLessThanOrEqualTo(180));
    }

    @Test
    void complexAdjustmentDoesNotTreatQueueAsTripDuration() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                "我想周末去上海人民广场附近玩，原本计划先去上海博物馆，然后去吃小杨生煎，之后去人民公园逛，但是到了之后发现上海博物馆今天临时闭馆了，小杨生煎现在排队要1小时，帮我调整一下方案。"
        );

        assertThat(response.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(response.clarification().get("missingFields").toString())
                .contains("timeWindow", "duration", "group", "budget");
        assertThat(response.intent().get("hard_constraints").toString())
                .contains("POI不可用需替换", "排队过长需替换");
        assertThat(response.intent().get("time_window").toString()).doesNotContain("durationMinutes=60");
    }

    @Test
    void collaborativeAndElderlyScenariosInferGroupAndAnchor() {
        PlanResponse collaborative = planningService.createPlan(
                "test-token",
                "我和两个朋友想周末在大连星海广场碰面一起玩，我在大连世界博览广场，朋友A在大连拿库古典车博览馆，朋友B在星海会展中心，我们想一起吃海鲜，然后一起喝杯咖啡，能不能帮我们规划一个顺路的路线，我们都不用绕太多路，最后一起汇合？"
        );
        assertThat(collaborative.clarification().get("missingFields").toString()).doesNotContain("group");
        assertThat(collaborative.intent().get("group").toString()).contains("total=3", "朋友同行");

        PlanResponse elderly = planningService.createPlan(
                "test-token",
                "我带爸妈去北京天安门附近玩，爸妈年纪大了，不想走太多路，最多步行10分钟就要休息一下，想上午逛故宫，然后吃一顿北京烤鸭，下午去景山公园，之后买个老北京酸奶，预算500以内，不要网红店，要本地人常去的，我们开车去的，还要有方便的停车场。"
        );
        assertThat(elderly.clarification().get("missingFields").toString()).doesNotContain("group", "budget");
        assertThat(elderly.intent().get("location").toString()).contains("北京", "天安门");
        assertThat(elderly.intent().get("hard_constraints").toString()).contains("老人友好", "停车便利");
    }

    @Test
    void soloWestLakeScenarioDoesNotAskGroup() {
        PlanResponse response = planningService.createPlan(
                "test-token",
                "我周末去杭州西湖玩，有5小时的时间，想逛岳王庙、曲院风荷、苏堤、断桥，然后吃一顿杭帮菜，之后喝杯龙井，一共6个点，能不能帮我串成顺路的路线，不要绕路，每个点的游玩时间要合理，逛景点的时间要够，吃饭的时间也要留够，不要太赶。"
        );

        assertThat(response.clarification().get("missingFields").toString()).doesNotContain("group", "duration");
        assertThat(response.intent().get("group").toString()).contains("total=1", "单人");
        assertThat(response.intent().get("time_window").toString()).contains("durationMinutes=300");
    }
}
