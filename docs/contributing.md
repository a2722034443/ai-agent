# 协作规范

## 分支和提交

- 每个任务使用独立分支，建议命名：`feature/<short-name>`、`fix/<short-name>`、`docs/<short-name>`。
- 一次提交只做一类事情：功能、修复、文档、测试不要混在一个大提交里。
- 提交信息建议：

```text
feat(frontend): add plan map workspace
fix(backend): preserve previous intent on clarification
docs: add onboarding guide
```

## 代码边界

### 前端

- 主流程状态集中在 `frontend/src/App.vue`。
- 地图相关逻辑放在 `frontend/src/components/TripMap.vue`。
- API 请求统一从 `frontend/src/api.js` 走，不在组件里散写 fetch。
- 不要在最终方案页清空 `shownPlans`，否则步骤栏无法切回“方案地图”。
- 样式保持当前设计语言：白底卡片、8-12px 圆角、蓝色主按钮、浅灰辅助信息、毛玻璃背景。

### 后端

- 入口只放在 `ApiController`。
- 规划主流程在 `PlanningService`。
- 澄清字段和缺失判断在 `ClarificationService`。
- LLM 解析在 `IntentParserAgent`，双路由在 `MimoClient`。
- 高德 POI、路线、天气分别在对应 tool 中，不要把 HTTP 调用散落到业务服务。
- 修改状态机时必须考虑 `previousPlanId` 的上下文合并。

## 必跑验证

后端改动：

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml test
```

前端改动：

```powershell
cd frontend
npm run build
```

全链路改动建议再做一次 HTTP 或浏览器烟测：

- 创建 session。
- 提交自然语言需求。
- 如需澄清，提交 `clarificationAnswers + previousPlanId`。
- 确认返回 `READY` 且 `options.length > 0`。
- 前端切换方案，地图同步更新。

## 规划链路红线

- 不能在缺少必填字段时调用高德 POI/路线。
- 不能把澄清答案当作新需求单独解析。
- 不能在正常运行中用 mock POI 冒充真实地点。
- 不能向用户展示原始 HTTP 500、堆栈、英文 provider 错误。
- 不能吞掉 trace；provider 问题必须可定位。

## 文档维护

如果改动了下面内容，请同步更新文档：

- 新增/删除接口：更新 `docs/api.md`。
- 改动状态机或工具链：更新 `docs/architecture.md`。
- 改动启动方式或配置项：更新 `docs/development.md`。
- 改动产品定位、核心流程或验收标准：更新 `docs/product.md`。

## 文件清理规则

不要提交这些本地文件：

- `.env`
- `config.py`
- `backend/src/main/resources/application-local.yml`
- `*.log`
- `backend/target/`
- `frontend/dist/`
- `frontend/node_modules/`
- IDE 配置目录

如果需要保留示例配置，只提交 `.env.example`、`config.example.py` 或 `application-local.example.yml`。
