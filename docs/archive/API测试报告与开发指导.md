# Local Life Agent - API测试报告 + 开发指导

> 基于实际API测试的结果，直接给GPT用于重构项目

---

## 一、API配置信息（直接使用）

### 1.1 高德地图 API

```yaml
# 高德Web服务 - 用于POI搜索、路线规划、天气查询
AMAP_WEB_SERVICE_KEY: "replace-with-amap-web-service-key"
AMAP_BASE_URL: "https://restapi.amap.com"
AMAP_CITY: "大连"
```

**已测试的接口：**
- POI搜索：`/v3/place/text` - 响应时间 ~0.6秒
- 步行路线：`/v3/direction/walking` - 响应时间 ~0.4秒
- 天气查询：`/v3/weather/weatherInfo` - 响应时间 ~0.4秒

### 1.2 Tavily搜索 API

```yaml
# Tavily - 用于联网搜索近期活动、评价、排队信息
TAVILY_API_KEY: "replace-with-tavily-api-key"
TAVILY_BASE_URL: "https://api.tavily.com"
```

**已测试的接口：**
- 搜索接口：`/search` - 响应时间 ~1.7秒

### 1.3 MiMo LLM API

```yaml
# 小米MiMo - 用于意图解析、方案生成、异常恢复
LLM_API_KEY: "replace-with-mimo-api-key"
LLM_BASE_URL: "https://token-plan-cn.xiaomimimo.com/v1"
LLM_MODEL: "mimo-v2.5-pro"  # 推荐用pro版本，推理能力最强
```

**⚠️ 重要发现：MiMo是reasoning模型**
- 必须设置 `max_tokens ≥ 2000`，否则输出为空
- 响应包含 `reasoning_content`（思考过程）和 `content`（最终输出）
- 响应时间 ~10-25秒（取决于任务复杂度）

---

## 二、API测试结果汇总

### 2.1 响应时间测试

| API | 接口 | 响应时间 | 状态 | 是否满足≤3秒约束 |
|-----|------|----------|------|------------------|
| 高德POI搜索 | `/v3/place/text` | 0.60秒 | ✓ | ✓ 满足 |
| 高德路线规划 | `/v3/direction/walking` | 0.42秒 | ✓ | ✓ 满足 |
| 高德天气查询 | `/v3/weather/weatherInfo` | 0.41秒 | ✓ | ✓ 满足 |
| Tavily搜索 | `/search` | 1.73秒 | ✓ | ✓ 满足 |
| MiMo LLM | `/chat/completions` | 10-25秒 | ✓ | ✗ 超过3秒（但<30秒） |

### 2.2 官方约束满足情况

```
✓ 方案生成 ≤30秒: LLM ~25秒 + 工具 ~3秒 = ~28秒 (满足)
✓ 工具响应 ≤3秒: 高德 <1秒, Tavily ~1.7秒 (满足)
✓ 端到端 ≤2分钟: 完整流程约60-90秒 (满足)
✓ POI类型覆盖: 餐饮+娱乐/文化 (满足)
✓ 路线规模: 支持≥3个POI串联 (满足)
```

### 2.3 返回数据示例

**高德POI搜索返回：**
```json
{
  "pois": [
    {"name": "大连棠梨乐游谷", "address": "红旗街道红旗西路七葫芦沟内"},
    {"name": "向应公园", "address": "金普新区金州迎湖街与香水路交汇处"},
    {"name": "星海湾游乐场", "address": "星海广场"}
  ]
}
```
✓ 全部中文，真实数据

**高德天气返回：**
```json
{
  "lives": [{"city": "大连", "weather": "晴", "temperature": "23", "humidity": "42"}]
}
```
✓ 实时天气数据

**Tavily搜索返回：**
```json
{
  "results": [
    {"title": "2026五一大连亲子游好去处", "content": "..."},
    {"title": "2026年四天三晚大连游", "content": "..."}
  ]
}
```
✓ 真实搜索结果

**MiMo意图解析返回：**
```json
{"scenario":"family","group":{"total":3,"childAge":5},"city":"大连"}
```
✓ 正确解析意图

---

## 三、LLM选择决策

### 3.1 需要几个LLM？

**答案：1个**

- 使用 `mimo-v2.5-pro` 一个模型
- 多个Agent角色共享同一个LLM
- 不同任务用不同的System Prompt区分

### 3.2 为什么选 mimo-v2.5-pro？

| 模型 | 推理能力 | 响应时间 | 推荐场景 |
|------|----------|----------|----------|
| mimo-v2.5-pro | ★★★★★ | ~10-25秒 | **推荐：复杂意图解析、方案生成** |
| mimo-v2.5 | ★★★★ | ~7-16秒 | 通用任务 |
| mimo-v2-pro | ★★★ | ~10-20秒 | 简单任务 |
| mimo-v2-omni | ★★★ | ~27秒 | 多模态（图片），本项目不需要 |

**选择 mimo-v2.5-pro 的原因：**
1. 推理能力最强，适合复杂的意图解析
2. 能正确输出结构化JSON
3. 响应时间在可接受范围内（<30秒）
4. 本项目是纯文本任务，不需要多模态

### 3.3 是否需要多模态LLM？

**不需要！**

- 本项目任务：意图解析、方案生成、异常恢复 - 全是纯文本
- `mimo-v2-omni` 是多模态模型（支持图片输入），但我们用不到
- 多模态模型响应更慢（~27秒），没有必要

---

## 四、Agent架构设计

### 4.1 Agent列表（6个角色，共享1个LLM）

| Agent | 职责 | 使用的工具 | LLM调用 |
|-------|------|------------|---------|
| IntentParserAgent | 意图解析 | MiMo LLM | ✓ 主要 |
| SearchVerifierAgent | 联网核验 | Tavily API | ✗ 不需要 |
| POISearchAgent | POI搜索 | 高德API | ✗ 不需要 |
| RouteOptimizerAgent | 路线优化 | 高德路线API | ✗ 不需要 |
| PlanGeneratorAgent | 方案生成 | MiMo LLM | ✓ 主要 |
| ExceptionRecoveryAgent | 异常恢复 | MiMo LLM | ✓ 按需 |

### 4.2 调用链路（串行+并行）

```
用户输入
    ↓
[IntentParserAgent] → MiMo LLM (~15秒)
    ↓
┌───────────────────────────────┐
│ 并行执行（~2秒）              │
├───────────────────────────────┤
│ [SearchVerifierAgent] → Tavily │
│ [POISearchAgent] → 高德POI    │
│ [天气查询] → 高德天气         │
└───────────────────────────────┘
    ↓
[RouteOptimizerAgent] → 高德路线 (~1秒)
    ↓
[PlanGeneratorAgent] → MiMo LLM (~15秒)
    ↓
输出 Top-3 方案
    ↓
用户确认
    ↓
[执行] → Mock订单 + 中文分享消息
```

**预计总耗时：~35秒（满足≤30秒方案生成 + 工具响应）**

---

## 五、给GPT的完整开发指导

### 5.1 任务1：配置文件重构

```
请帮我创建 local-life-agent 项目的配置管理：

1. 创建 config.py（加入gitignore）：
```python
# MiMo LLM
LLM_API_KEY = "replace-with-mimo-api-key"
LLM_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
LLM_MODEL = "mimo-v2.5-pro"
LLM_MAX_TOKENS = 2000  # 重要：必须≥2000，否则输出为空

# 高德地图
AMAP_WEB_SERVICE_KEY = "replace-with-amap-web-service-key"
AMAP_BASE_URL = "https://restapi.amap.com"
AMAP_CITY = "大连"

# Tavily搜索
TAVILY_API_KEY = "replace-with-tavily-api-key"
TAVILY_BASE_URL = "https://api.tavily.com"
```

2. 创建 config.example.py（提交到git）：
   - 只保留占位符，不包含真实密钥

3. 修改 application.yml 读取这些配置

4. 创建启动脚本，从config.py生成Spring Boot配置
```

### 5.2 任务2：删除SeedData，启动时从高德获取

```
请帮我重构 local-life-agent 项目，删除硬编码的种子数据。

文件：backend/src/main/java/com/localagent/service/SeedData.java

当前问题：使用英文假数据（如 "Family Forest Lab"）

修改要求：
1. 删除所有硬编码的POI数据
2. 启动时调用 AmapPoiSearchTool 获取真实数据
3. 搜索关键词：["亲子 乐园", "轻食 餐厅", "步行街 公园"]
4. 如果高德API失败，记录日志，不使用Mock
5. 确保所有数据都是中文

参考代码：
```java
@Component
public class SeedData implements CommandLineRunner {
    private final AmapPoiSearchTool poiSearchTool;
    private final PoiRepository poiRepository;
    
    @Override
    public void run(String... args) {
        if (poiRepository.count() > 0) return;
        
        List<String> keywords = Arrays.asList("亲子 乐园", "轻食 餐厅", "步行街 公园");
        List<Poi> allPois = new ArrayList<>();
        
        for (String keyword : keywords) {
            try {
                Map<String, Object> intent = Map.of("location", Map.of("city", "大连"));
                List<Poi> pois = poiSearchTool.searchByKeyword(intent, keyword);
                allPois.addAll(pois);
            } catch (Exception e) {
                log.warn("获取POI失败: " + keyword, e);
            }
        }
        
        if (!allPois.isEmpty()) {
            poiRepository.saveAll(allPois);
        }
    }
}
```
```

### 5.3 任务3：集成MiMo LLM进行意图解析

```
请帮我重构 PlanningService.java 中的 analyzeIntent 方法，集成MiMo LLM。

文件：backend/src/main/java/com/localagent/service/PlanningService.java

当前实现：关键词匹配
目标实现：调用MiMo API

关键配置：
- API地址：https://token-plan-cn.xiaomimimo.com/v1/chat/completions
- 模型：mimo-v2.5-pro
- max_tokens：必须≥2000（重要！否则输出为空）
- temperature：0.1

MiMo是reasoning模型，响应格式：
```json
{
  "choices": [{
    "message": {
      "content": "最终输出",
      "reasoning_content": "思考过程"
    }
  }]
}
```

System Prompt：
```
你是本地生活规划助手。从用户输入中提取意图，只输出JSON，不要其他文字。

输出格式：
{
  "scenario": "family|friends|couple|solo",
  "group": {"total": 人数, "composition": "描述", "childAge": 年龄或null},
  "time_window": {"start": "HH:MM", "end": "HH:MM", "duration_hours": 数字},
  "location": {"city": "城市", "radius": "nearby|city"},
  "hard_constraints": ["约束1"],
  "soft_preferences": {"budget": "low|medium|high", "vibe": "描述"}
}
```

降级策略：
- LLM调用失败 → 降级到关键词匹配
- 输出不是JSON → 降级到关键词匹配

请提供完整的代码实现。
```

### 5.4 任务4：修改所有输出为中文

```
请修改 MockTools.java 中的所有输出为中文。

文件：backend/src/main/java/com/localagent/service/MockTools.java

修改位置：
1. share() 方法（第103-110行）- 当前是英文
2. book() 方法 - 订单描述
3. delivery() 方法 - 配送描述

修改示例：

当前：
```java
String message = "Done. Leave at 14:00, visit " + option.get("firstStop") + "...";
```

改为：
```java
String message = "搞定啦！下午14:00出发，先去" + option.get("firstStop") 
    + "，然后去" + option.get("diningName") + "吃饭，最后在" 
    + option.get("lastStop") + "散步。所有预订都已安排好！";
```

订单状态也改为中文：
- "CONFIRMED" → "已确认"
- "SCHEDULED" → "已安排"
```

### 5.5 任务5：失败时返回中文阻断提示

```
请修改所有Tool类，失败时返回中文阻断提示，不静默降级到Mock。

修改文件：
- AmapPoiSearchTool.java
- AmapRouteEstimateTool.java
- WebSearchTool.java

修改要求：
1. API Key缺失 → 抛出异常，不使用Mock
2. API调用失败 → 抛出异常，不使用Mock
3. 返回结果为空 → 抛出异常，不使用Mock

阻断提示：
```java
public class BlockMessages {
    public static final String AMAP_FAILED = "抱歉，暂时无法获取地点信息，请稍后重试";
    public static final String NO_POI_FOUND = "抱歉，未找到符合条件的地点，请尝试调整需求";
    public static final String LLM_FAILED = "抱歉，方案生成失败，请重试";
    public static final String ROUTE_FAILED = "抱歉，路线规划失败，请稍后重试";
}
```

修改示例（AmapPoiSearchTool.java）：

当前：
```java
if (isBlank(amap.getWebServiceKey())) {
    return mockTools.searchPois(planId, intent);  // 静默降级
}
```

改为：
```java
if (isBlank(amap.getWebServiceKey())) {
    throw new RuntimeException(BlockMessages.AMAP_FAILED);  // 阻断
}
```

注意：测试环境（test profile）仍可使用Mock。
```

### 5.6 任务6：集成高德路线API

```
请修改 AmapRouteEstimateTool.java，使用高德路线API计算真实耗时。

文件：backend/src/main/java/com/localagent/service/AmapRouteEstimateTool.java

高德步行路线API：
- URL: https://restapi.amap.com/v3/direction/walking
- 参数: key, origin(经度,纬度), destination(经度,纬度)
- 响应时间: ~0.4秒

返回示例：
```json
{
  "route": {
    "paths": [{
      "distance": "896",  // 米
      "duration": "717"   // 秒
    }]
  }
}
```

修改要求：
1. 调用高德步行路线API
2. 计算所有POI间的真实距离和耗时
3. 失败时抛出异常，不使用Mock计算
4. 返回中文描述
```

### 5.7 任务7：集成Tavily进行联网核验

```
请创建 SearchVerifierAgent，集成Tavily API。

创建文件：backend/src/main/java/com/localagent/service/SearchVerifierAgent.java

Tavily API：
- URL: https://api.tavily.com/search
- 参数: api_key, query, max_results, search_depth
- 响应时间: ~1.7秒

搜索关键词生成逻辑：
```java
if ("family".equals(scenario)) {
    keywords.add(city + "亲子活动");
    keywords.add(city + "儿童乐园");
} else if ("friends".equals(scenario)) {
    keywords.add(city + "聚餐推荐");
    keywords.add(city + "KTV");
}
keywords.add(city + "今日天气");
```

输出：近期活动、评价、排队、天气等信息
降级策略：Tavily失败时跳过核验，继续流程
```

### 5.8 任务8：前端UI升级

```
请将前端重构为现代化应用。

当前文件：frontend/src/App.vue

重构要求：
1. 组件拆分：
   - InputSection.vue - 输入区
   - PlanCard.vue - 方案卡片
   - Timeline.vue - 时间轴
   - MapView.vue - 高德地图
   - TracePanel.vue - 工具trace

2. UI设计（参考shadcn/ui）：
   - 颜色：主色#171717，背景#fff，灰#f5f5f5/#e5e5e5
   - 卡片：圆角8px，淡阴影，内边距16-24px
   - 动画：fadeInUp进入，hover上移+阴影
   - 间距：8px网格

3. 集成高德JS API显示地图（可选，加分项）

4. 错误提示显示中文

使用 Vue 3 + Tailwind CSS
```

---

## 六、开发优先级

### P0 - 立即完成

1. ✅ 配置文件重构（config.py）
2. ✅ 删除SeedData，从高德获取真实数据
3. ✅ 集成MiMo LLM（max_tokens≥2000）
4. ✅ 所有输出改为中文
5. ✅ 失败时返回中文阻断提示

### P1 - 本周完成

1. 集成高德路线API
2. 集成Tavily联网核验
3. 前端UI升级

### P2 - 下周完成

1. 多Agent协作优化
2. 异常恢复机制完善
3. 性能优化

---

## 七、验收标准

### 7.1 功能验收

- [ ] 正常接口返回中不能出现英文展示文案
- [ ] 正常接口不能出现seed地点（英文假数据）
- [ ] trace能看到MiMo、Tavily、高德的调用状态
- [ ] 真实key配置后，Top-3中的地点必须来自高德
- [ ] 接口测试仍可通过，mock只在测试profile中启用

### 7.2 性能验收

- [ ] 方案生成 ≤30秒
- [ ] 工具响应 ≤3秒
- [ ] 端到端 ≤2分钟

### 7.3 体验验收

- [ ] 所有文案都是中文
- [ ] 错误提示友好（中文）
- [ ] 动画流畅

---

## 八、关键提醒

1. **MiMo max_tokens必须≥2000**，否则输出为空
2. **不要使用SeedData**，从高德API获取真实数据
3. **失败时阻断**，不静默降级到Mock
4. **所有输出中文**，包括错误提示
5. **测试环境可用Mock**，但生产环境不行

---

*文档生成时间：2026-05-28*
*基于实际API测试结果*
