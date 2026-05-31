# 开发上手

## 环境要求

- Windows PowerShell
- JDK 17
- Node.js 20+
- MySQL 8.x
- Redis 兼容服务，默认 `localhost:6379`

项目不使用 Docker。团队统一使用仓库已有 `.env`、`config.py` 和 `backend/src/main/resources/application-local.yml` 配置，不在本轮文档整理中修改这些文件。

## 首次准备

如果是新机器：

```powershell
cd C:\Users\59862\Desktop\local-life-agent
Copy-Item .env.example .env
Copy-Item .\config.example.py .\config.py
```

然后按团队共享配置填充本地密钥。已有配置的机器不要覆盖。

如需从 `config.py` 生成 Spring local 配置：

```powershell
python .\scripts\generate-local-config.py
```

## MySQL 初始化

```sql
CREATE DATABASE IF NOT EXISTS local_life_agent
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'agent'@'localhost' IDENTIFIED BY 'agentpass';
GRANT ALL PRIVILEGES ON local_life_agent.* TO 'agent'@'localhost';
FLUSH PRIVILEGES;
```

如果团队使用不同账号密码，按 `.env` 中的 `DB_URL`、`DB_USER`、`DB_PASSWORD` 为准。

## 启动后端

```powershell
cd C:\Users\59862\Desktop\local-life-agent
.\scripts\start-backend-native.ps1
```

脚本会检查：

- Java 是否可用。
- MySQL 端口是否可连接。
- Redis 是否真实返回 `PONG`。
- `.env` 是否可加载。

正常地址：`http://localhost:8080`

## 启动前端

另开一个 PowerShell：

```powershell
cd C:\Users\59862\Desktop\local-life-agent
.\scripts\start-frontend-native.ps1
```

脚本会在 `frontend/node_modules` 不存在时自动执行 `npm install`。

正常地址：`http://localhost:5173`

## 常用命令

后端测试：

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml test
```

前端构建：

```powershell
cd frontend
npm run build
```

Redis 协议检查：

```powershell
.\scripts\check-redis-native.ps1
```

## 最小 HTTP 烟测

创建 session：

```powershell
$session = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/sessions" `
  -ContentType "application/json" `
  -Body '{"nickname":"dev"}'

$headers = @{ "X-Session-Token" = $session.token }
```

提交需求：

```powershell
$body = @{
  message = "今天晚上7点在上海静安寺附近，4个朋友，预算800元，想先玩再吃饭"
  planCount = 3
  stopCountPreference = "标准"
} | ConvertTo-Json -Depth 8

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/plans" `
  -Headers $headers `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

如果返回 `NEEDS_CLARIFICATION`，用返回的 `planId` 作为 `previousPlanId` 补齐字段。

## 前端开发说明

主文件是 `frontend/src/App.vue`：

- `currentStep` 控制聊天流程中的需求、澄清、方案地图。
- `shownPlans` 保存已生成方案，不能在步骤切换时清空。
- `TripMap.vue` 只负责地图，不承担业务状态。
- 新增 UI 时优先保持现有圆角、白底、蓝色主按钮和毛玻璃背景。

API 封装在 `frontend/src/api.js`：

- 自动带上 `X-Session-Token`。
- 401 时会清理旧 token 并重新创建 session。

## 后端开发说明

入口是 `ApiController`，核心服务是 `PlanningService`。

常见改动位置：

- 澄清字段规则：`ClarificationService`
- 需求解析和本地 fallback：`IntentParserAgent`
- LLM 路由：`MimoClient`
- POI 查询：`AmapPoiSearchTool`
- 路线估算：`AmapRouteEstimateTool`
- 方案校验：`PlanValidationService`
- 协同 mock：`CollaborationMockService`

后端改规划链路时，至少补或跑 `ApiControllerTest` 和 `PlanningServiceTest`。

## 常见问题

### 前端提示会话过期

一般是后端重启后旧 token 失效。当前 `api.js` 会自动恢复一次；仍失败时清理浏览器 localStorage 中的 `lla_token`。

### 一直问地点

“附近/当前位置”需要浏览器定位或 `AMAP_DEFAULT_ORIGIN`。如果浏览器拒绝定位，后端会使用本地默认 origin。

### 生成方案很慢

优先看 response 的 `trace`：

- `IntentParserAgent` 是否 LLM 超时。
- `AmapPoiSearchTool` 是否高德慢或返回太少。
- `AmapRouteEstimateTool` 是否路线候选多次失败。

### 高德地图不显示

检查前端环境变量：

- `VITE_AMAP_JS_KEY`
- `VITE_AMAP_JS_SECURITY_CODE`
- `VITE_AMAP_JS_VERSION`

不要把真实 key 提交到仓库。
