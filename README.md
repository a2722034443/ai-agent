# local-life-agent

全国级本地生活管家 Agent。用户用自然语言描述需求后，系统先判断缺少哪些关键信息，返回澄清卡片；信息补齐后，再调用真实 LLM、高德、路线、天气和搜索能力生成可解释、可验证的本地生活方案。

## 技术栈

- Backend: Spring Boot 3.3, Java 17, MySQL, Redis
- Frontend: Vue 3, Vite
- Runtime: 正常运行使用 MiMo、Amap、Redis、MySQL；缺少关键外部能力时返回明确阻断，不编造地点或路线。

## 严格启动约束

本项目不使用 Docker，也不允许通过 Docker 启动前端、后端、MySQL 或 Redis。

Redis 是正式运行必需组件。后端启动脚本会先对 Redis 做协议级 `PING`，失败则不启动后端。测试环境可以通过 `app.mock-session-store=true` 使用内存 session，但正常运行必须使用真实 Redis。

## 本机依赖

请先在本机安装并启动：

- JDK 17
- Node.js 20+
- MySQL 8.x
- Redis 兼容服务，监听 `localhost:6379`

Windows 上可以使用本机 Redis 兼容服务或 WSL 内 Redis，但项目启动命令本身不依赖 Docker。

MySQL 初始化示例：

```sql
CREATE DATABASE IF NOT EXISTS local_life_agent
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'agent'@'localhost' IDENTIFIED BY 'agentpass';
GRANT ALL PRIVILEGES ON local_life_agent.* TO 'agent'@'localhost';
FLUSH PRIVILEGES;
```

## 配置 .env

真实密钥和本机连接信息写在仓库根目录的 `.env`。该文件已被 `.gitignore` 忽略，不要提交。

```powershell
Copy-Item .env.example .env
```

然后编辑 `.env`，填入 `AMAP_WEB_SERVICE_KEY`、`MIMO_API_KEY` 和可选的 `TAVILY_API_KEY`。当前本机已经生成了 `.env`，后端和前端启动脚本会自动加载。

默认本地连接：

- MySQL: `localhost:3306`, database `local_life_agent`, user `agent`, password `agentpass`
- Redis: `localhost:6379`
- Backend: `http://localhost:8080`
- Frontend: `http://localhost:5173`

如需改地址：

```dotenv
DB_URL=jdbc:mysql://localhost:3306/local_life_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_USER=agent
DB_PASSWORD=agentpass
REDIS_HOST=localhost
REDIS_PORT=6379
```

## 启动后端

在一个 PowerShell 窗口执行：

```powershell
cd C:\Users\59862\Desktop\local-life-agent
.\scripts\start-backend-native.ps1
```

脚本会先检查：

- Java 是否存在
- MySQL 端口是否可连接
- Redis 是否真实响应 `PONG`
- 仓库根目录 `.env` 是否可加载

全部通过后才会执行：

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml spring-boot:run
```

## 启动前端

另开一个 PowerShell 窗口执行：

```powershell
cd C:\Users\59862\Desktop\local-life-agent
.\scripts\start-frontend-native.ps1
```

首次运行时脚本会在 `frontend/node_modules` 缺失时执行 `npm install`，然后启动 Vite。

## Redis 验证

不依赖 `redis-cli` 的验证方式：

```powershell
.\scripts\check-redis-native.ps1
```

启动后端后，也可以验证 session 已写入 Redis：

```powershell
$session = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/sessions" `
  -ContentType "application/json" `
  -Body '{"nickname":"test"}'

$session.token
```

如果 Redis 没启动，`POST /api/sessions` 会失败，不会回退到本机内存。

## 测试与构建

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml test
```

```powershell
cd frontend
npm run build
```

## 外部服务说明

- MiMo API key 用于需求理解、缺口判断和方案文案生成。
- 高德 Web Service key 用于真实 POI、地理编码和路线估算，是核心真实数据源。
- Tavily API key 用于可选网页核验，失败只记录 warning/trace，不阻断核心规划。
- Amap JS API key 和 security code 预留给未来前端地图渲染。
- 正常运行缺少 Amap 或 MiMo 时应返回中文阻断原因，不允许使用假 POI 冒充真实结果。
