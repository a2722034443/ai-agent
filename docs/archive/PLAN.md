# local-life-agent 新工作区实施计划

## Summary
- 在 `C:\Users\59862\Desktop\Likeyou\local-life-agent` 新建独立工作区，不读取、不复用现有 `frontend/backend/docs` 代码。
- 技术栈采用 `Vue 3 + Vite + Spring Boot + MySQL + Redis`，先交付 Web 端，手机端预留为响应式/PWA 扩展。
- 运行时使用 Mock API 保证 Demo 稳定；开发写方案、文档和关键内容前先联网核验资料。
- 官方资料已先查：Vue 3 官方文档、Spring Boot 官方文档、高德 POI 文档、OpenAI Tool Calling/Structured Outputs、MySQL Spatial、Redis 数据结构。

## Key Changes
- 工程结构：
  - `backend/`：Spring Boot REST API、Agent 编排、Mock 工具、MySQL/Redis。
  - `frontend/`：Vue 3 Web 控制台，展示自然语言输入、Top-3 方案、工具调用链、地图/时间轴、确认执行结果。
  - `prompts/`：项目内标准 prompt 模块，运行时由后端加载和串联。
  - `docs/`：≤2 页设计文档、API 文档、Mock 工具说明、一键启动说明。
  - 本地 MySQL + Redis 由原生服务提供，项目不使用 Docker 启动。
- Prompt/Agent 串联：
  - `project-management.prompt.md`：按立项、启动、计划、周报、风险输出项目过程检查。
  - `requirement-analysis.prompt.md`：把一句自然语言解析为人群、时间、地理、预算、偏好、硬约束。
  - `product-planning.prompt.md`：生成可展示的多方案结构和确认话术。
  - `route-planner.prompt.md`：拆解活动/餐饮/额外活动，约束 4-6 小时、≥3 个 POI、餐饮+娱乐/文化覆盖。
  - `execution.prompt.md`：确认后调用 Mock 预订/排队/购票/配送工具。
  - `exception-recovery.prompt.md`：处理无座、无票、时间冲突三类故障并自动替换。
  - `share-feedback.prompt.md`：生成发给家人/朋友的分享消息和反馈闭环。
- 后端核心接口：
  - `POST /api/sessions` 创建匿名会话。
  - `POST /api/plans` 输入自然语言，返回 Top-3 可执行方案和 trace。
  - `POST /api/plans/{id}/confirm` 确认方案并执行 Mock 下单/预约。
  - `POST /api/plans/{id}/feedback` 支持自然语言调整。
  - `GET /api/plans/{id}` 查询方案状态。
- 数据模型：
  - `plan_session`、`plan_option`、`poi`、`tool_call_log`、`mock_order`、`feedback_event`。
  - POI 坐标使用经纬度字段；可选 MySQL `POINT SRID 4326` 和空间索引用于后续真实路线检索。
- 工具层：
  - `PoiSearchTool`、`RouteEstimateTool`、`RestaurantAvailabilityTool`、`TicketAvailabilityTool`、`BookingTool`、`DeliveryGiftTool`、`ShareMessageTool`。
  - 全部先做 Mock，响应时间目标 `<3s`；规划总耗时目标 `<10s`。
- 前端页面：
  - 左侧自然语言输入、场景快捷样例、约束摘要。
  - 中间 Top-3 方案卡片：时间线、POI、交通耗时、预算、适配理由、风险提示。
  - 右侧工具 trace：每次 Mock API 调用、入参摘要、结果、异常恢复。
  - 确认后一键执行，展示订单/预约/排队/分享消息。

## Test Plan
- 后端单元测试：
  - 意图解析：家庭场景、朋友场景、减肥/孩子/距离/时间窗口识别。
  - 规划质量：4-6 小时、不超过时间窗、≥3 POI、至少餐饮+娱乐/文化。
  - 异常恢复：无座换餐厅、无票换活动、时间冲突重排。
  - 工具调用：Mock API 精确调用、trace 完整记录。
- 后端集成测试：
  - `POST /api/plans` 端到端返回 Top-3。
  - `confirm` 后生成 Mock 订单和分享消息。
  - Redis 会话缓存、MySQL 持久化可用。
- 前端验证：
  - `npm run build` 通过。
  - 主要视口：桌面和手机宽度不重叠、不溢出。
  - 方案确认、异常提示、执行结果流程可跑通。
- 文档验收：
  - `docs/design.md` 控制在 2 页内，说明 Planning 策略、工具链路、异常处理。
  - `README.md` 提供一键本地启动步骤。

## Assumptions
- 新目录名固定为 `local-life-agent`。
- “skill/prompt”落地为项目内 `prompts/`，由 Spring Boot 运行时编排，不创建全局 Codex skill。
- Demo 运行默认 Mock-only；真实联网搜索仅用于开发前核验资料和文档依据。
- 优先使用 Spring Boot 3.x/Java 21 的稳定组合；若本机 Java 版本不匹配，实施时再按本机环境调整。
- 参考来源：Vue 3 官方文档 https://vuejs.org/guide/introduction.html ，Spring Boot 官方文档 https://docs.spring.io/spring-boot/reference/index.html ，高德 POI 搜索 https://lbs.amap.com/api/webservice/guide/api/search/ ，OpenAI Function Calling https://platform.openai.com/docs/guides/function-calling ，OpenAI Structured Outputs https://platform.openai.com/docs/guides/structured-outputs ，MySQL Spatial https://dev.mysql.com/doc/refman/8.4/en/spatial-type-overview.html ，Redis Data Types https://redis.io/docs/latest/develop/data-types/ 。
