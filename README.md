# local-life-agent

本地短时活动规划与执行 Agent Demo。输入一句自然语言目标，系统生成 3 套可执行路线，并在确认后完成 Mock 订座、购票、配送和分享消息。

## Tech Stack

- Backend: Spring Boot 3.3, Java 17, MySQL, Redis
- Frontend: Vue 3, Vite
- Runtime: 正常环境使用 MiMo + 高德 + Tavily；高德或 MiMo 失败时中文阻断，不编造地点

## Windows Quick Start

1. Start MySQL and Redis, or use Docker Desktop when available:

```powershell
docker compose up -d
```

2. Configure local external services. Do not commit real keys.

```powershell
Copy-Item config.example.py config.py
python scripts\generate-local-config.py
```

3. Start backend:

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml spring-boot:run
```

4. Start frontend in another PowerShell window:

```powershell
cd frontend
npm install
npm run dev
```

4. Open `http://localhost:5173`.

## Useful Commands

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml test
```

```powershell
cd frontend
npm run build
```

The project includes `backend/.mvn/settings.xml` and `frontend/.npmrc` so Maven/npm use project-level China mirrors without changing your global user settings.

## Demo Flow

1. Create a session.
2. Enter: `今天下午2点在大连星海广场附近，两个大人一个孩子，预算600元，想安排亲子活动和晚餐，时间4小时左右`.
3. Review Top-3 plans, trace, constraints and recovery notes.
4. Confirm one plan.
5. See Mock orders and share message.

## External Services

- MiMo API key is used by `IntentParserAgent` and `PlanGeneratorAgent`.
- AMap Web Service key is used by `AmapPoiSearchTool` and `AmapRouteEstimateTool`; it is the source of real POI and walking route data.
- Tavily API key is used by `SearchVerifierAgent` for live context verification. Tavily failure is recorded in trace and does not block planning.
- AMap JS API key and security code are reserved for future frontend map rendering and are not exposed by the current Web UI.
- In normal runtime, missing/invalid AMap or MiMo keys return a non-2xx Chinese error with trace. Mock POI data is allowed only in the `test` profile or explicit test configuration.

## References Checked Before Implementation

- Vue 3 Guide: https://vuejs.org/guide/introduction.html
- Spring Boot Reference: https://docs.spring.io/spring-boot/reference/index.html
- Spring Boot Docker Compose: https://docs.spring.io/spring-boot/how-to/docker-compose.html
- AMap Web Service POI Search: https://lbs.amap.com/api/webservice/guide/api/search/
- OpenAI Function Calling: https://platform.openai.com/docs/guides/function-calling
- OpenAI Structured Outputs: https://platform.openai.com/docs/guides/structured-outputs
- MySQL Spatial Types: https://dev.mysql.com/doc/refman/8.4/en/spatial-type-overview.html
- Redis Data Types: https://redis.io/docs/latest/develop/data-types/
