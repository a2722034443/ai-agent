# 立刻游 local-life-agent

立刻游是一个“多人协同本地短时出行 AI 助理”。用户用一句自然语言描述周末/当天的本地出行需求，系统先判断是否缺少地点、时间、时长、同行人、预算等关键信息；信息完整后，调用 MiMo LLM、高德 POI/路线、天气和可选网页核验，生成可执行的本地短时行程方案，并支持分享协同、确认执行和出行守护 mock 流程。

## 先读什么

新成员建议按这个顺序阅读：

1. [产品介绍](docs/product.md)：理解产品定位、目标用户、核心流程和验收标准。
2. [技术架构](docs/architecture.md)：理解前后端结构、后端状态机、外部工具链和数据流。
3. [开发上手](docs/development.md)：本地环境、配置、启动、测试、常见问题。
4. [API 文档](docs/api.md)：前后端对接接口、请求响应和关键状态。
5. [协作规范](docs/contributing.md)：分支、提交、代码边界、测试要求。

## 当前能力

- 聊天式主流程：输入需求、澄清缺失字段、生成方案、确认执行。
- Web 方案工作台：左侧方案列表，右侧高德地图可视化，方案切换联动地图。
- 地图能力：高德 JS API 懒加载、标注/路线/距离标签、跳转高德地图。
- 后端规划链路：LLM 意图解析、上下文合并、澄清状态机、POI 搜索、路线估算、方案校验。
- 双 LLM 路由：主 MiMo 失败时 fallback 到 secondary，再失败走本地规则，避免 500。
- 协同 mock：分享、投票、评论。
- 记忆/守护 mock：偏好标签、天气/路况/商家状态示例。

## 技术栈

- Frontend: Vue 3, Vite, 高德 JS API Loader
- Backend: Java 17, Spring Boot 3.3, Spring Web, Spring Data JPA, Redis session, MySQL
- External providers: MiMo-compatible LLM, Amap Web Service, Amap JS API, Tavily optional search
- Test: JUnit/Spring Boot Test, Vite production build

## 快速启动

项目不使用 Docker。请在本机安装并启动：

- JDK 17
- Node.js 20+
- MySQL 8.x
- Redis 兼容服务，监听 `localhost:6379`

配置沿用仓库根目录 `.env`、`config.py` 和 `backend/src/main/resources/application-local.yml`。不要提交真实密钥文件。

启动后端：

```powershell
cd C:\Users\59862\Desktop\local-life-agent
.\scripts\start-backend-native.ps1
```

启动前端：

```powershell
cd C:\Users\59862\Desktop\local-life-agent
.\scripts\start-frontend-native.ps1
```

默认地址：

- Backend: `http://localhost:8080`
- Frontend: `http://localhost:5173`

## 验证命令

```powershell
cd backend
.\mvnw.cmd -s .\.mvn\settings.xml test
```

```powershell
cd frontend
npm run build
```

## 关键目录

```text
backend/                 Spring Boot 后端
frontend/                Vue3 前端
docs/                    产品、架构、开发、API、协作文档
scripts/                 本机启动和配置生成脚本
prompts/                 Agent/工具提示词草稿
agents/                  Agent pipeline 配置
```

## 开发原则

- 不在信息缺失时调用高德、路线、天气或搜索。
- 澄清答案必须合并上一轮上下文，不能把“3小时左右”当成新需求。
- READY 方案必须至少包含 3 个 POI，且覆盖活动/文化和餐饮。
- 外部 API 失败必须返回中文可理解的阻断或 warning，不能展示原始 HTTP 500。
- 正常运行不允许用 mock POI 冒充真实地点；测试 profile 可以使用 mock。

更详细的工程规则见 [协作规范](docs/contributing.md)。
