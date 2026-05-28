# Mock 与外部工具边界

正常环境的规划链路只允许使用真实数据：MiMo 解析与生成、高德 POI、高德步行路线、Tavily 联网核验。高德或 MiMo 缺 key、失败、超时或返回空结果时，接口返回中文阻断错误，并在 trace 中写入 `mode: blocked`。

| 工具 | 正常环境行为 |
| --- | --- |
| `IntentParserAgent` | 调用 MiMo 解析中文需求；失败时只降级为简化关键词意图 |
| `SearchVerifierAgent` | 调用 Tavily 核验评论、排队和上下文；失败只记录 trace |
| `AmapPoiSearchTool` | 调用高德 POI 搜索；失败阻断，不使用种子地点 |
| `AmapRouteEstimateTool` | 调用高德步行路线；失败阻断 |
| `PlanGeneratorAgent` | 基于真实 POI、真实路线候选和 Tavily 证据生成中文 Top-3 |
| `PlanValidationService` | 拦截英文展示文案和非高德来源地点 |
| `BookingTool` / `DeliveryGiftTool` / `ShareMessageTool` | 用户确认后生成中文 mock 执行动作 |

测试环境可以启用 `app.allow-mock-poi=true`，并由 `test` profile 注入中文测试 POI。该能力只用于自动化测试和确认执行阶段，不用于正常开发链路编造地点。
