# 外部开源项目研究摘要

本轮仅将开源项目下载到本地 `external-repos/` 供研究使用，该目录已加入 `.gitignore`，不提交第三方源码。

## andrej-karpathy-skills

- 仓库：`https://github.com/multica-ai/andrej-karpathy-skills`
- 定位：面向编码 Agent 的行为规范，强调先思考、简单优先、精准修改、目标驱动验证。
- 对本项目的用途：作为后续后端和前端迭代的协作规范参考，不进入运行时链路。

## codegraph

- 仓库：`https://github.com/colbymchenry/codegraph`
- 定位：本地代码知识图谱/语义索引工具，帮助 Agent 更快理解代码结构。
- 对本项目的用途：适合后续在本地索引当前仓库，减少人工搜索和重复阅读；暂不作为产品依赖。

## Understand-Anything

- 仓库：`https://github.com/Lum1104/Understand-Anything`
- 定位：把代码库、文档或知识库转成可交互知识图谱的多 Agent 工具。
- 对本项目的用途：适合作为“多 Agent 可视化”和“业务流程图谱”参考；暂不接入本项目后端业务链路。

## 当前结论

- 三者更适合提升开发与代码理解效率，不适合直接放入用户规划请求链路。
- 后端提速优先通过本项目内的规则生成器完成，避免引入额外 Agent 工具导致运行时更慢。
- 前端 UI 只借鉴“低装饰、结构清晰、图谱/链路可观察”的设计方向，不复制第三方源码。

## 前端 UI 调研

- `vbenjs/vue-vben-admin`：Vue 3 + Vite 的高星后台模板，适合参考左侧工作区、紧凑列表和清晰状态栏。
- `ant-design/ant-design-pro`：企业级后台应用模板，适合参考信息层级、表格化内容和响应式布局。
- `satnaing/shadcn-admin`：Vite + Shadcn 的后台界面，适合参考低装饰、留白克制和状态反馈。

本项目前端已按这些方向重做为极简控制台：左侧工作区、需求输入、方案列表、执行链路和中文状态映射。没有复制第三方源码，也没有引入额外重组件库。
