# 后端设计：真实 Agent 本地生活规划

## 规划策略

后端采用 Spring Boot 编排的多 Agent 链路。v1 只使用一个 LLM：MiMo `mimo-v2.5-pro`，不同 Agent 通过不同 system prompt 区分职责。正常环境不使用 seed 地点，不静默 fallback 到 mock POI。

一次 `POST /api/plans` 的顺序是：

1. `IntentParserAgent` 解析中文需求为结构化意图。
2. `ClarificationService` 检查地点、时间、同行人、预算、期望时长和核心需求；缺失时返回 `NEEDS_CLARIFICATION`，不调用高德/Tavily。
3. `AmapWeatherTool` 获取天气；失败只返回“天气暂不可用”，不编造天气。
4. `SearchVerifierAgent` 用 Tavily 做联网核验，失败只记录 trace。
5. `AmapPoiSearchTool` 获取真实 POI。
6. `AmapRouteEstimateTool` 对候选行程计算真实路线。
7. 规则优先生成用户要求数量的候选方案，默认 3 套，可在 1-5 套内调整。
8. `PlanValidationService` 校验地点来源，真实 POI 名称、品牌名和地址允许保留英文、数字和符号。

## 失败策略

高德 POI、高德路线和 MiMo 生成属于关键依赖。缺 key、请求失败、配额异常、返回空结果或校验失败时，接口返回非 2xx JSON：`error`、`planId`、`trace`、`provider`、`status="ERROR"`。trace 里的 `mode` 使用 `real`、`fallback`、`mock`、`blocked` 表达调用状态。

Tavily 是辅助核验，不阻断方案生成。它的失败会写入 trace，后续仍以高德和 MiMo 为准。

用户未提供必要条件时不是失败，而是正常返回 `200 + NEEDS_CLARIFICATION`，前端展示补充表单。信息补齐前不查真实地点、不生成方案。

## Mock 边界

测试 profile 可以使用中文 mock POI，保证自动化测试不访问外网。用户确认执行后，订座、购票、配送和分享消息仍为 mock，但所有 action、status、shareMessage 必须是中文。

## 接口

核心端点保持不变：`POST /api/sessions`、`POST /api/plans`、`GET /api/plans/{id}`、`POST /api/plans/{id}/confirm`、`POST /api/plans/{id}/feedback`。MySQL 存储会话、方案、执行记录、反馈和 trace；Redis 存储 session token。
