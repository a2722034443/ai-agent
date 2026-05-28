# GPT开发指导 - Local Life Agent重构

> 直接复制这些提示词给GPT，让它帮你重构代码

---

## 🎯 重构目标

将当前"能跑通但不是合格Agent"的系统，重构为"真正的Agent系统"：

1. 删除所有硬编码的假数据
2. 集成真实API（MiMo、高德、Tavily）
3. 所有输出必须是中文
4. 失败时返回中文阻断提示，不编造方案

---

## 📋 任务清单

### 任务1：删除SeedData，启动时从高德获取真实数据

**给GPT的提示词：**
```
请帮我重构 local-life-agent 项目，删除硬编码的种子数据。

文件路径：backend/src/main/java/com/localagent/service/SeedData.java

当前问题：
- 使用英文假数据（如 "Family Forest Lab", "Coast Science Theater"）
- 这些数据在正常环境中是不允许的

修改要求：
1. 删除所有硬编码的POI数据
2. 改为启动时从高德API获取真实数据
3. 使用 AmapPoiSearchTool 类
4. 如果高德API失败，抛出异常，不使用Mock
5. 确保所有数据都是中文

参考代码结构：
```java
@Component
public class SeedData implements CommandLineRunner {
    private final AmapPoiSearchTool poiSearchTool;
    private final PoiRepository poiRepository;
    
    @Override
    public void run(String... args) {
        if (poiRepository.count() > 0) {
            return; // 已有数据，跳过
        }
        
        // 从高德API获取真实数据
        Map<String, Object> intent = createDefaultIntent();
        List<Poi> pois = poiSearchTool.searchPois(UUID.randomUUID(), intent);
        
        if (pois.isEmpty()) {
            throw new RuntimeException("无法从高德获取POI数据，请检查API Key配置");
        }
        
        poiRepository.saveAll(pois);
    }
}
```

请提供完整的代码修改。
```

---

### 任务2：集成MiMo进行意图解析

**给GPT的提示词：**
```
请帮我重构 PlanningService.java 中的 analyzeIntent 方法，集成MiMo API。

文件路径：backend/src/main/java/com/localagent/service/PlanningService.java

当前实现：关键词匹配（第115-152行）
目标实现：调用MiMo API进行意图解析

修改要求：
1. 添加MiMo API调用
2. 使用System Prompt定义输出格式
3. 解析MiMo返回的JSON
4. 失败时降级到关键词匹配
5. 确保所有输出都是中文

MiMo API调用方式：
```java
// 使用HttpClient调用OpenAI兼容接口
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.mimo.com/v1/chat/completions"))
    .header("Authorization", "Bearer " + mimoApiKey)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .build();
```

System Prompt内容：
```
你是一个本地生活规划助手。请从用户输入中提取以下信息：

输出格式（JSON）：
{
  "scenario": "family|friends|couple|solo|unknown",
  "group": {
    "total": 人数,
    "composition": "组成描述",
    "childAge": 孩子年龄或null
  },
  "time_window": {
    "start": "开始时间",
    "end": "结束时间",
    "duration": "持续时长"
  },
  "location": {
    "city": "城市",
    "district": "区域或null",
    "radius": "nearby|city"
  },
  "hard_constraints": ["硬约束1", "硬约束2"],
  "soft_preferences": {
    "budget": "low|medium|high",
    "vibe": "氛围描述"
  }
}

解析规则：
- 孩子、老人、安全、过敏、必须去属于硬约束
- 减肥、少折腾、网红、预算属于偏好
- 时间默认今天下午14:00-20:00，窗口4-6小时
- 城市从输入中提取，没有则默认"大连"
```

请提供完整的代码修改，包括：
1. MiMo客户端类
2. System Prompt定义
3. 响应解析逻辑
4. 降级到关键词匹配
```

---

### 任务3：集成Tavily进行联网核验

**给GPT的提示词：**
```
请帮我创建 SearchVerifierAgent，集成Tavily API进行联网核验。

创建文件：backend/src/main/java/com/localagent/service/SearchVerifierAgent.java

功能：
1. 根据场景生成搜索关键词
2. 调用Tavily API搜索
3. 解析搜索结果
4. 返回核验信息

Tavily API调用方式：
```java
HttpClient client = HttpClient.newHttpClient();
String requestBody = """
{
  "api_key": "%s",
  "query": "%s",
  "search_depth": "advanced",
  "max_results": 5
}
""".formatted(tavilyApiKey, query);

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.tavily.com/search"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
    .build();
```

搜索关键词生成逻辑：
```java
private List<String> generateSearchKeywords(Map<String, Object> intent) {
    List<String> keywords = new ArrayList<>();
    String scenario = (String) intent.get("scenario");
    
    // 场景相关
    if ("family".equals(scenario)) {
        keywords.add("大连亲子活动");
        keywords.add("大连儿童乐园");
        keywords.add("大连周末遛娃");
    } else if ("friends".equals(scenario)) {
        keywords.add("大连聚餐推荐");
        keywords.add("大连KTV");
        keywords.add("大连密室逃脱");
    }
    
    // 天气
    keywords.add("大连今日天气");
    keywords.add("大连近期活动");
    
    return keywords;
}
```

输出示例：
```json
{
  "results": [
    {
      "title": "大连森林动物园周末亲子活动",
      "url": "https://example.com",
      "content": "本周末有海豚表演...",
      "score": 0.95
    }
  ],
  "weather": {
    "condition": "晴",
    "temperature": "22°C",
    "suggestion": "适合户外活动"
  }
}
```

请提供完整的代码实现。
```

---

### 任务4：集成高德路线API计算真实耗时

**给GPT的提示词：**
```
请帮我修改 AmapRouteEstimateTool.java，使用高德路线API计算真实耗时。

文件路径：backend/src/main/java/com/localagent/service/AmapRouteEstimateTool.java

当前实现：使用Mock计算（MockTools.route()）
目标实现：调用高德路线API

高德步行路线API：
```
GET https://restapi.amap.com/v3/direction/walking
参数：
- key: 高德Key
- origin: 起点经纬度（经度,纬度）
- destination: 终点经纬度（经度,纬度）
```

返回示例：
```json
{
  "status": "1",
  "route": {
    "origin": "121.588,38.883",
    "destination": "121.592,38.890",
    "paths": [
      {
        "distance": "1200",  // 米
        "duration": "900",   // 秒
        "steps": [...]
      }
    ]
  }
}
```

修改要求：
1. 调用高德步行路线API
2. 计算所有POI间的真实距离和耗时
3. 返回中文描述
4. 失败时返回中文阻断提示

请提供完整的代码修改。
```

---

### 任务5：修改所有输出为中文

**给GPT的提示词：**
```
请帮我修改 MockTools.java 中的所有输出，确保都是中文。

文件路径：backend/src/main/java/com/localagent/service/MockTools.java

当前问题：
- share() 方法生成英文消息（第103-110行）
- 订单描述是英文

修改要求：
1. share() 方法改为中文消息
2. 所有订单描述改为中文
3. 所有错误提示改为中文
4. 确保所有返回给前端的内容都是中文

修改示例：

当前代码（第103-110行）：
```java
public String share(UUID planId, Map<String, Object> option) {
    String message = "Done. Leave at 14:00, visit " + option.get("firstStop") + ", dine at "
            + option.get("diningName") + ", then finish with " + option.get("lastStop")
            + ". Required bookings are handled in mock mode.";
    return message;
}
```

修改后：
```java
public String share(UUID planId, Map<String, Object> option) {
    String message = "搞定啦！下午14:00出发，先去" + option.get("firstStop") 
            + "，然后去" + option.get("diningName") + "吃饭，最后在" 
            + option.get("lastStop") + "散步。所有预订都已安排好！";
    return message;
}
```

请提供完整的代码修改。
```

---

### 任务6：失败时返回中文阻断提示

**给GPT的提示词：**
```
请帮我修改所有Tool类，实现失败时返回中文阻断提示。

修改文件：
- AmapPoiSearchTool.java
- AmapRouteEstimateTool.java
- WebSearchTool.java
- MockTools.java

修改要求：
1. 失败时不再静默降级到Mock
2. 返回中文阻断提示
3. 记录详细的trace信息
4. 前端能正确显示错误信息

阻断提示模板：
```java
public class BlockMessages {
    public static final String AMAP_FAILED = "抱歉，暂时无法获取地点信息，请稍后重试";
    public static final String NO_POI_FOUND = "抱歉，未找到符合条件的地点，请尝试调整需求";
    public static final String LLM_FAILED = "抱歉，方案生成失败，请重试";
    public static final String VALIDATION_FAILED = "抱歉，方案校验失败，请重试";
    public static final String ALL_BLOCKED = "抱歉，当前无法生成方案，请稍后重试";
}
```

修改示例（AmapPoiSearchTool.java）：

当前代码（第41-44行）：
```java
if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
    traceFallback(planId, intent, "missing_key", null);
    return mockTools.searchPois(planId, intent);  // 静默降级到Mock
}
```

修改后：
```java
if (!amap.isEnabled() || isBlank(amap.getWebServiceKey())) {
    traceFallback(planId, intent, "missing_key", null);
    throw new RuntimeException(BlockMessages.AMAP_FAILED);  // 抛出异常
}
```

请提供完整的代码修改。
```

---

### 任务7：前端UI全面升级

**给GPT的提示词：**
```
请将 local-life-agent 的前端从单组件重构为现代化应用。

当前文件：frontend/src/App.vue

重构要求：

1. 组件拆分：
   - src/components/InputSection.vue - 左侧输入区
   - src/components/PlanCard.vue - 方案卡片
   - src/components/Timeline.vue - 时间轴
   - src/components/MapView.vue - 地图（高德JS API）
   - src/components/TracePanel.vue - 工具调用trace
   - src/components/ExecutionResult.vue - 执行结果

2. UI设计（参考shadcn/ui风格）：
   - 颜色：主色#171717，背景#ffffff，灰色层次#f5f5f5/#e5e5e5/#737373
   - 卡片：圆角8px，阴影0 1px 3px rgba(0,0,0,0.1)，内边距16-24px
   - 动画：fadeInUp进入（stagger 0.1s），hover上移+阴影
   - 间距：8px网格系统

3. 布局：
   - 桌面端：三栏布局（输入300px | 方案flex-1 | trace 350px）
   - 移动端：单栏堆叠
   - 使用CSS Grid或Flexbox

4. 功能：
   - 集成高德JS API显示POI标记
   - 时间轴可视化（步骤+连线）
   - 骨架屏加载状态
   - 响应式适配

5. 错误处理：
   - 显示中文错误提示
   - 不显示英文错误信息

请使用 Vue 3 Composition API + <script setup> + Tailwind CSS

请提供完整的代码实现。
```

---

### 任务8：添加中文校验器和防编造校验器

**给GPT的提示词：**
```
请帮我创建中文校验器和防编造校验器。

创建文件：backend/src/main/java/com/localagent/service/ValidationService.java

功能：
1. 中文校验：检查所有输出是否包含英文
2. 防编造校验：检查所有地点是否来自真实API

中文校验器：
```java
public class ChineseValidator {
    public ValidationResult validate(String text) {
        // 检查是否包含英文字母（排除常见英文缩写）
        String pattern = "[a-zA-Z]{3,}";  // 至少3个连续英文字母
        List<String> exceptions = Arrays.asList("API", "WiFi", "GPS", "QR", "ID");
        
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(text);
        
        while (matcher.find()) {
            String match = matcher.group();
            if (!exceptions.contains(match)) {
                return new ValidationResult(false, "包含英文: " + match);
            }
        }
        
        return new ValidationResult(true, null);
    }
}
```

防编造校验器：
```java
public class FabricationValidator {
    public ValidationResult validate(Map<String, Object> plan, List<Poi> realPois) {
        Set<String> realPoiNames = realPois.stream()
            .map(Poi::getName)
            .collect(Collectors.toSet());
        
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) plan.get("timeline");
        for (Map<String, Object> item : timeline) {
            String place = (String) item.get("place");
            if (!realPoiNames.contains(place)) {
                return new ValidationResult(false, "地点不在真实POI中: " + place);
            }
        }
        
        return new ValidationResult(true, null);
    }
}
```

校验失败处理：
```java
public Map<String, Object> validatePlan(Map<String, Object> plan, List<Poi> realPois) {
    // 中文校验
    String planJson = objectMapper.writeValueAsString(plan);
    ValidationResult chineseResult = chineseValidator.validate(planJson);
    if (!chineseResult.isValid()) {
        return Map.of(
            "error", "抱歉，方案包含非中文内容: " + chineseResult.getError(),
            "status", "blocked"
        );
    }
    
    // 防编造校验
    ValidationResult fabricationResult = fabricationValidator.validate(plan, realPois);
    if (!fabricationResult.isValid()) {
        return Map.of(
            "error", "抱歉，方案包含非真实地点: " + fabricationResult.getError(),
            "status", "blocked"
        );
    }
    
    return Map.of("status", "valid");
}
```

请提供完整的代码实现。
```

---

## 🚀 执行顺序

### 第一步（立即）
1. 复制任务1的提示词给GPT → 删除SeedData
2. 复制任务2的提示词给GPT → 集成MiMo
3. 复制任务5的提示词给GPT → 中文输出

### 第二步（今天）
1. 复制任务3的提示词给GPT → 集成Tavily
2. 复制任务4的提示词给GPT → 集成高德路线
3. 复制任务6的提示词给GPT → 阻断提示

### 第三步（明天）
1. 复制任务7的提示词给GPT → 前端UI
2. 复制任务8的提示词给GPT → 校验器

---

## 💡 关键提醒

1. **不要自己写代码**，全部交给GPT
2. **提示词要具体**，包含文件路径、当前问题、修改要求
3. **分步骤执行**，不要一次改太多
4. **保留降级机制**，MiMo失败时降级到关键词匹配
5. **确保中文输出**，所有返回给前端的内容必须是中文
6. **失败时阻断**，不编造方案，返回中文错误提示

---

## 📊 验收标准

修改完成后，检查以下内容：

- [ ] SeedData.java中没有硬编码的英文数据
- [ ] PlanningService.java使用MiMo进行意图解析
- [ ] MockTools.java中share()方法返回中文
- [ ] 所有Tool类失败时返回中文阻断提示
- [ ] 前端显示中文错误信息
- [ ] trace能看到MiMo、Tavily、高德的调用状态

---

*生成时间：2026-05-28*
*基于项目代码深度分析*
