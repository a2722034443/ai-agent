# 立刻游产品设计文档

## 1. 产品目标与边界

立刻游是“多人协同本地短时出行 AI 助理”。目标不是让用户浏览攻略，而是把一句自然语言需求转成可执行的本地计划：补齐关键事实、查询真实地点、生成方案、分享协同、确认执行，并在突发变化时给出替换动作。首屏以聊天为主，方案以紧凑卡片和地图呈现；协同、执行、守护是同一计划的后续状态。

关键输入包括地点或坐标、明确开始时间、时长或结束时间、同行人、预算、核心需求和硬约束。缺少这些字段时，系统只做澄清，不调用真实 POI、路线、天气或搜索。澄清答案必须通过 `previousPlanId` 合并到上一轮 intent，不能把“3小时左右”当成新需求。

## 2. Planning 策略

后端规划由 `PlanningService` 驱动，状态机为 `INITIAL/CLARIFICATION/FEEDBACK -> NEEDS_CLARIFICATION|READY|BLOCKED -> COMPLETED`。每轮先创建或恢复 thread/session，记录用户消息，再构造 intent：LLM 或规则解析当前文本，读取上一轮 intent，合并嵌套字段，写入 `rawMessage` 和 `userFacts.answers`，最后应用 `planCount`、`stopCountPreference` 等请求偏好。

字段门禁优先于工具调用。`ClarificationService` 判断地点、时间、时长、同行人、预算和核心需求是否可执行；若不完整，返回精确字段、示例答案和“信息补齐前不会查询真实地点”的 warning。若完整，系统生成 POI 搜索策略：按家庭、朋友、情侣、单人等场景选择活动、餐饮和补充关键词，并加入低步行、儿童友好、清淡饮食、室内优先等管家约束。

READY 方案必须满足可执行标准：1-3 套选项，每套至少 3 个真实 POI，包含活动/娱乐/文化和餐饮，时间线有开始时间、地点顺序、路线距离/耗时、预算估算、适配理由、风险和执行清单。`PlanValidationService` 校验时间线结构、真实 POI 名称、餐饮和活动覆盖；失败即阻断，避免伪造地点。

## 3. 工具调用链路

链路顺序如下：`IntentParserAgent -> ClarificationService -> AmapPoiSearchTool -> AmapPoiDetailTool -> AmapRouteEstimateTool -> AmapWeatherTool/SearchVerifierAgent -> PlanValidationService -> MockTools/GuardService`。

`IntentParserAgent` 默认调用 MiMo，`MimoClient` 支持 `primary-fallback` 和 `parallel-race`，当前推荐主备 fallback：primary 超时、限流、5xx、空响应或 JSON 异常时，只切一次 secondary；两路失败后使用本地关键词规则，保证解析链路不因 LLM 失败而 500。每次解析写入 trace，包含 provider、lane、model、duration、fallbackReason。

高德 POI 和路线是关键工具。POI 搜索按活动、餐饮、补充关键词扩大半径查询，合并显式 POI；路线估算控制候选数和距离阈值，过滤过远或绕路组合。天气和网页核验是可选工具，并发触发但有短超时，失败只产生 warning。所有工具通过 `ToolTraceService` 记录 tool、status、durationMs、input、output、provider、mode、sourceUrl、externalStatus，前端和调试接口都可以看到证据链。

确认方案后，`MockTools` 生成订票、订座、配送、分享消息、执行步骤、异常和替代项；协同服务保存分享、投票和评论；守护服务基于已完成方案返回天气、路线、商家状态和替换建议。当前执行与守护是 mock，不对外宣称真实下单。

## 4. 异常处理机制

异常分为四类。第一类是信息缺失：返回 `NEEDS_CLARIFICATION`，问题必须最小化，例如只缺时长就只问时长，并确认已识别开始时间。第二类是关键 provider 阻断：缺少高德 key、POI 太少、路线不可达、方案校验失败时返回 `BLOCKED` 或用户可理解的阻断文案，并给出“扩大范围/放宽需求/补充坐标”等行动建议。第三类是可选 provider 失败：天气、搜索超时或不可用只进入 warnings，不影响 READY。第四类是执行/状态非法：未 READY 确认返回 `NOT_READY`，找不到计划返回 `NOT_FOUND`，非法 rank 返回可理解错误。

系统不展示原始 HTTP 500 给用户。运行时日志保留内部错误，响应和聊天消息使用中文解释；trace 保留 provider 状态用于排查。LLM 失败不阻断基础意图解析；天气和搜索有 3200ms 级别 fallback；路线工具在单候选失败时跳过，全部失败才阻断。这样可以保证“先给可用结果，再暴露可处理风险”的产品体验。

## 5. 质量指标

首个有效响应目标 10 秒内，单工具目标 3 秒内，路线生成目标 10 秒内，端到端规划不超过 2 分钟。核心验收用例包括：完整请求只追问缺失字段；澄清后不丢上下文；模糊“我附近想玩”不调用 provider；家庭/饮食/低步行约束能体现在排序和文案；LLM primary 失败后 secondary 或本地 fallback 不产生 500。
