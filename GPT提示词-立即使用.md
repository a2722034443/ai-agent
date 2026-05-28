# 🚀 立即行动清单

> 给GPT的提示词，直接复制使用

---

## 1️⃣ 后端：集成LLM意图解析

### 给GPT的提示词：
```
请帮我优化 local-life-agent 项目的 PlanningService.java

文件路径：backend/src/main/java/com/localagent/service/PlanningService.java

任务：将 analyzeIntent 方法从关键词匹配改为LLM调用

要求：
1. 添加LLM API调用（OpenAI兼容接口）
2. 创建System Prompt，输出JSON格式的意图
3. 解析LLM返回结果
4. 失败时降级到关键词匹配

System Prompt内容：
你是一个本地生活规划助手。请从用户输入中提取以下信息：
- scenario: family/friends/couple/solo
- group: {total人数, composition组成, childAge孩子年龄}
- time_window: {start开始时间, end结束时间, duration持续时长}
- location: {city城市, radius范围}
- hard_constraints: 硬约束数组
- soft_preferences: {budget预算, vibe氛围}

示例输入："今天下午带老婆孩子出去玩，孩子5岁"
示例输出：
{
  "scenario": "family",
  "group": {"total": 3, "composition": "2大1小", "childAge": 5},
  "time_window": {"start": "14:00", "end": "20:00", "duration": "4-6小时"},
  "location": {"city": "大连", "radius": "nearby"},
  "hard_constraints": ["kid_friendly", "route_duration_4_to_6_hours"],
  "soft_preferences": {"budget": "medium", "vibe": "family_easy"}
}

请提供完整的代码修改。
```

---

## 2️⃣ 前端：UI全面升级

### 给GPT的提示词：
```
请将 local-life-agent 的前端从单组件重构为现代化应用

当前文件：frontend/src/App.vue

重构要求：

1. **组件拆分**（创建以下组件）：
   - src/components/InputSection.vue - 左侧输入区
   - src/components/PlanCard.vue - 方案卡片
   - src/components/Timeline.vue - 时间轴
   - src/components/MapView.vue - 地图（高德JS API）
   - src/components/TracePanel.vue - 工具调用trace
   - src/components/ExecutionResult.vue - 执行结果

2. **UI设计**（参考shadcn/ui风格）：
   - 颜色：主色#171717，背景#ffffff，灰色层次#f5f5f5/#e5e5e5/#737373
   - 卡片：圆角8px，阴影0 1px 3px rgba(0,0,0,0.1)，内边距16-24px
   - 动画：fadeInUp进入（stagger 0.1s），hover上移+阴影
   - 间距：8px网格系统

3. **布局**：
   - 桌面端：三栏布局（输入300px | 方案flex-1 | trace 350px）
   - 移动端：单栏堆叠
   - 使用CSS Grid或Flexbox

4. **功能**：
   - 集成高德JS API显示POI标记
   - 时间轴可视化（步骤+连线）
   - 骨架屏加载状态
   - 响应式适配

请使用 Vue 3 Composition API + <script setup> + Tailwind CSS
```

---

## 3️⃣ 产品：完整Demo流程设计

### 给GPT的提示词：
```
请为 local-life-agent 设计一个完整的Demo演示流程

背景：这是一个本地生活活动规划Agent
技术栈：Spring Boot + Vue 3 + MySQL + Redis

演示场景：
用户输入："今天下午带老婆孩子出去玩，孩子5岁，老婆在减肥，别离家太远"

请设计：

1. **前端展示效果**：
   - 输入框样式
   - 解析结果展示
   - 3套方案卡片
   - 方案对比
   - 确认按钮
   - 执行结果
   - 分享消息

2. **后端处理逻辑**：
   - 意图解析（LLM调用）
   - POI搜索（高德API）
   - 路线规划（时间计算）
   - 方案评分（多维度）
   - 异常处理（无座/无票/冲突）

3. **异常处理预案**：
   - LLM调用失败 → 降级到关键词匹配
   - 高德API失败 → 使用Mock数据
   - 无座位 → 自动替换餐厅
   - 无票 → 自动替换活动
   - 超时 → 缩短行程

4. **性能指标**：
   - 方案生成 ≤10秒
   - 工具响应 ≤3秒
   - 端到端 ≤2分钟

请提供完整的设计文档，包括：
- 流程图
- 时序图
- 界面原型
- 代码结构
```

---

## 4️⃣ 多Agent协作（技术亮点）

### 给GPT的提示词：
```
请将 local-life-agent 的单体PlanningService重构为多Agent架构

当前实现：PlanningService.java 串行处理所有逻辑

目标架构：
├── IntentParserAgent    → 意图解析（调用LLM）
├── POISearchAgent       → POI搜索（调用高德）
├── RouteOptimizerAgent  → 路线优化（时间/距离计算）
├── BookingAgent         → 预订执行（Mock订单）
└── FeedbackAgent        → 反馈处理（自然语言调整）

技术选型：
- 使用LangChain4j（Java版LangChain）
- 或者Spring AI（Spring官方AI框架）

要求：
1. 每个Agent有独立的职责和工具
2. Agent间通过消息传递协作
3. 支持并行执行（如同时搜索多个POI）
4. 有完整的trace记录
5. 失败时自动降级

请提供：
- 技术选型对比
- 架构设计图
- 核心代码实现
- 测试用例
```

---

## 📋 执行顺序

### 今天（立即开始）
1. ✅ 决定LLM API（推荐：OpenAI兼容接口）
2. ✅ 复制提示词1给GPT，生成后端代码
3. ✅ 复制提示词2给GPT，生成前端代码

### 本周完成
1. 集成LLM意图解析
2. 前端UI升级
3. 完整Demo流程

### 下周完成
1. 多Agent协作
2. 真实API集成
3. 性能优化

---

## 💡 关键提醒

1. **不要自己写代码**，全部交给GPT
2. **提示词要具体**，包含文件路径、技术要求、示例
3. **分步骤执行**，不要一次改太多
4. **保留Mock降级**，确保Demo稳定
5. **注重用户体验**，动画和响应速度很重要

---

*生成时间：2026-05-28*
*基于项目分析 + 市场调研*
