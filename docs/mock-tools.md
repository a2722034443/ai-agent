# Mock 与外部工具边界

正常环境的规划链路只允许使用真实数据：MiMo 解析与生成、高德 POI、高德步行路线、Tavily 联网核验。高德或 MiMo 缺 key、失败、超时或返回空结果时，接口返回中文阻断错误，并在 trace 中写入 `mode: blocked`。

| 工具 | 正常环境行为 |
| --- | --- |
| `IntentParserAgent` | 规则优先解析自然语言；MiMo 只做可选增强，失败不阻断 |
| `ClarificationService` | 必要条件不足时返回追问，不调用高德/Tavily 生成方案 |
| `AmapWeatherTool` | 调用高德天气；失败不编造天气，返回天气暂不可用并记录 trace |
| `SearchVerifierAgent` | 调用 Tavily 核验评论、排队和上下文；失败只记录 trace |
| `AmapPoiSearchTool` | 调用高德 POI 搜索；失败阻断，不使用种子地点 |
| `AmapRouteEstimateTool` | 调用高德步行路线；失败阻断 |
| `PlanGeneratorAgent` | 可选润色；方案数量和地点数量遵循用户约束，默认 3 套 |
| `PlanValidationService` | 拦截非高德来源地点；允许真实品牌名、地址包含英文、数字和符号 |
| `BookingTool` / `DeliveryGiftTool` / `ShareMessageTool` | 用户确认后生成中文 mock 执行动作 |

测试环境可以启用 `app.allow-mock-poi=true`，并由 `test` profile 注入中文测试 POI。该能力只用于自动化测试和确认执行阶段，不用于正常开发链路编造地点。
