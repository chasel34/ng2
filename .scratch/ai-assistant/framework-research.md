Status: 比较探索完成；用户已决定优先使用 Koog 完整能力，未开始实现

# Android 本机 Agent 框架研究

调研日期：2026-09-13。约束来自 [需求](spec.md)：Android 原生应用、DeepSeek 首发、BYOK、未来多模型、DuckDuckGo 免费免 Key 搜索。手机负责 agent 循环、上下文、工具、来源与会话；模型推理通过用户 Key 调用服务商。

后续进展：用户已要求基于 Koog 探索最佳实践，并明确 DeepSeek V4.1 Flash 支持图片。以下保留本轮框架比较的依据；当前方案继续见 [Koog 最佳实践](koog-best-practices.md)。

## 选型结论

建议将 JetBrains Koog 作为第一候选。它有 Android 发布目标和 Compose 示例，能够用 Kotlin 直接调用本项目现有读取能力。Pi 核心设计也适合此类 agent，但在当前原生项目中需要额外维护 JavaScript 运行环境及桥接。Codex 的 SDK 与 CLI 运行方式主要适用于桌面或服务端，Android 嵌入成本更高。

此结论基于官方文档、源码和项目匹配度，尚未验证 APK 构建、真机生命周期、包体开销及 DeepSeek 实际调用。Koog 的依赖体积与 DeepSeek 新能力适配，是进入实现前需要验证的两项重点。

## 三个框架的适配情况

| 候选 | 已核实的能力与运行方式 | 对本项目的判断 |
| --- | --- | --- |
| Koog | Kotlin 多平台，支持 Android；自定义工具、流式输出、历史压缩、状态持久化；多模型客户端 | 第一候选。论坛读取、协程取消、Room 和 Compose 可用同一语言衔接 |
| Pi agent core + pi-ai | 可独立使用的工具循环、事件、上下文转换；多服务商适配；pi-ai 支持浏览器环境 | 备选。需要在 App 内嵌入 JS 环境并连接原生网络、工具和存储，相关桥接尚未验证 |
| Codex SDK / app-server | SDK 控制本地 Codex 进程；TS SDK 要求 Node，Python SDK附带 CLI 运行时；app-server 提供会话与事件协议 | 首期不优先。要为 Android 移植并管理额外运行时与原生进程，或增加服务端；后者不符合当前本机优先方向 |

来源：[Koog 概览](https://docs.koog.ai/)、[Pi agent core](https://github.com/earendil-works/pi/blob/main/packages/agent/README.md)、[Pi 多模型与浏览器支持](https://github.com/earendil-works/pi/blob/main/packages/ai/README.md)、[Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)。

Pi 当前官方仓库已由 badlogic/pi-mono 重定向到 earendil-works/pi。检查到 agent-core 的 package.json 为 0.85.1，声明 Node >=22.19.0，同时 core 将 Node SQLite 适配分包。不能把这个 npm 版本要求扩大成“所有 Pi 核心代码只能运行于 Node”；但浏览器可用也不等于可直接作为 Android Kotlin 依赖。Pi 的 MIT 许可、Koog 的 Apache-2.0 许可均见官方仓库，实际打包仍需保留依赖许可声明。[Pi 包信息](https://github.com/earendil-works/pi/blob/main/packages/agent/package.json)、[Koog 仓库](https://github.com/JetBrains/koog)。

## Koog 核实结果

官方最新发布指向 1.2.0。对应发布源码声明 Android release 变体，基础 minSdk 23，Compose Android 示例 minSdk 26；本项目 minSdk 31、JVM 17。最低 SDK 层面匹配，但这不替代 Gradle 依赖解析和真机验证。[发布页](https://github.com/JetBrains/koog/releases/tag/1.2.0)、[Android 构建约定](https://github.com/JetBrains/koog/blob/1.2.0/convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts)、[Compose 示例](https://github.com/JetBrains/koog/blob/1.2.0/examples/demo-compose-app/androidApp/build.gradle.kts)。

HTTP 层已有 OkHttp 实现，可用于模型请求。应显式传入所选 HTTP factory，避免依赖 ServiceLoader 自动发现产生歧义，也避免复用 NGA 专用 CookieJar。论坛工具继续经现有 NgaClient 的 READ 流程读取。[HTTP 客户端说明](https://docs.koog.ai/prompts/http-clients/)。

但 Koog 1.2.0 的 agents-core 同时声明 Ktor 客户端组件、服务端 SSE/CIO，以及在 Android/JVM 共享源集中依赖 Jackson 序列化。选择 OkHttp 后端不能移除这些声明。需要在最小 Android 验证中检查最终依赖图、R8、启动开销与包体，而非预先承诺框架很轻。不能盲目 exclude 依赖。[agents-core 构建文件](https://github.com/JetBrains/koog/blob/1.2.0/agents/agents-core/build.gradle.kts)。

Koog 区分稳定模块和 beta 模块，当前 DeepSeek 客户端仍标注 beta。首期按需选择 agent core、预置策略、ChatMemory、Persistence、EventHandler、skills 和模型客户端，避免使用拉入全部模型的聚合依赖。用户已决定优先使用 Koog 的完整能力，不把自建运行层作为备选；依赖体积或适配问题通过模块裁剪、配置和向上游反馈解决，不迁移到 JS 或增加服务端。[模块版本政策](https://docs.koog.ai/module-versioning/)。

## DeepSeek 与图片、工具调用

官方当前列出 deepseek-flash 支持图片，deepseek-v4-pro 不支持图片；两者支持工具调用与思考模式。建议以 deepseek-flash 作为首期默认候选，具体模型设置尚未由用户确认。图片可以通过 URL、base64 或 Files API 传入，App 仍需遵循首次总计一张和按需增读规则。[模型说明](https://api-docs.deepseek.com/quick_start/pricing/)、[Vision](https://api-docs.deepseek.com/guides/vision/)。

Koog 1.2.0 的 DeepSeekModels 仍使用 deepseek-v4-flash 等预置标识，未声明 Vision.Image。它继承的 OpenAI 基础客户端实际已有 URL/base64 图片转换，并要求模型具备图片能力。因此一个可验证方案是添加与官方当前 API 对应的 LLModel 描述，必要时补充 DeepSeek 适配。源码提供这个扩展点，但尚不能断言仅改一项配置就完全可用。[DeepSeekModels](https://github.com/JetBrains/koog/blob/1.2.0/prompt/prompt-executor/prompt-executor-clients/prompt-executor-deepseek-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/deepseek/DeepSeekModels.kt)、[图片转换代码](https://github.com/JetBrains/koog/blob/1.2.0/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/AbstractOpenAILLMClient.kt)。

工具调用由模型提出、手机执行并回传结果。DeepSeek 当前有 Chat Completions、Responses 和 Anthropic 格式；本轮不依据旧资料将 Codex 排除为“DeepSeek 协议一定不兼容”，排除理由是 Android 嵌入方式。思考模式下的流式 reasoning 字段与工具调用后的回传，需要保持服务商要求，避免统一消息格式丢失必要信息。[Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)、[Responses API](https://api-docs.deepseek.com/guides/responses_api/)。

## DuckDuckGo：免费免 Key 的可用路径与限制

官方提供 HTML 和 Lite 两种无需 JavaScript 的搜索页面。首期可在手机本机用 HTTP 读取 Lite，并解析标题、原始链接、摘要，再按需读取结果网页正文。搜索摘要与已读网页正文应区分，不应仅凭摘要声称完成事实核查。[官方说明](https://duckduckgo.com/duckduckgo-help-pages/features/non-javascript)。

本次在当前开发机器用 Python 标准库做了两次匿名 GET，查询均为 `JetBrains Koog Android`，没有使用账户或 API Key：

| 入口 | 状态 | 检查结果 |
| --- | --- | --- |
| html.duckduckgo.com/html/ | HTTP 202 | 返回人机验证，没有搜索结果 |
| lite.duckduckgo.com/lite/ | HTTP 200 | 解析到 10 条结果，包含 JetBrains 官网、Koog 仓库和发布页 |

这是一次可行性探测，不能代表 Android 实现已完成，也不能代表所有用户网络下稳定可用。未绕过人机验证，未执行模型请求。HTML/Lite 是网页入口，未发现其提供面向此集成的稳定完整搜索 API 承诺；旧 `/api` 文档地址本次发生重定向，不能将它当作已验证的全文搜索接口。

实现方向：独立 SearchProvider，默认 DuckDuckGo Lite；限制并发、短期缓存、少量重试，区分无结果、网络失败和验证页；失败后保留论坛对话并说明外部检索未完成。未来替换搜索来源不修改论坛工具和会话核心。本期不引入 Python 搜索库到 Android，也不静默切换其他引擎或收费接口。

## 建议的本机结构与后续验证

App 持有统一会话：入口范围与快捷 prompt → AgentRuntime 适配 → Koog → DeepSeek。Koog 注册 App 提供的读取主题页、定位楼层、读取回复链、读取个人历史、读图、搜索网页、读取网页等工具。工具直接连接原有 Kotlin 仓库；图片规则、300 条范围、来源引用、预算和论坛权限由 App 执行。

Room 保存业务会话、消息、来源快照和必要的可恢复状态；框架压缩后的上下文只是模型工作资料，不能代替原始引用记录。BYOK 由本机加密存储管理。模型适配、搜索适配与论坛凭证分开，后续新增服务商时沿用会话和工具接口。初期无需外置 MCP 服务；未来有具体需求时可增加兼容模块。

下一阶段若确认方向，最小验证应覆盖：

1. Koog 1.2.0 必要模块在本项目版本下解析、构建与 release/R8，记录依赖增量及真实包体影响。
2. DeepSeek 流式回答 → 一次读取工具 → 连续工具调用 → 最终回答，验证思考模式回传和错误重试。
3. 首次一张图片与后续按需读图，以及个人分析无法调用图片工具。
4. 停止时取消当前网络与工具；收起面板继续；进程结束后保留历史并明确中断。
5. Room 来源引用可恢复，长上下文压缩后仍能回读原文，300 条规则不靠模型自行遵守。
6. 用户实际手机网络下的 DuckDuckGo Lite 解析、验证页识别、无结果和网页正文读取失败。

本阶段完成范围为资料与源码研究、一次匿名搜索可行性探测和方案记录。未新增 App 依赖、修改 App 源码、运行框架样例、调用付费模型或完成真机验证。

## Comments

- 2026-09-13：根据用户补充的 Android 本机条件比较 Koog、Pi、Codex，核查当前官方文档及 Koog 1.2.0 发布源码。发现 DeepSeek 视觉能力与框架预置模型声明的差异，并记录 DuckDuckGo Lite 可用、HTML 返回验证页的单次探测结果。推荐先验证 Koog，选型仍待讨论。
- 2026-09-13：用户决定优先使用 Koog 完整能力、不自建等价机制；撤回「范围受控的自建 Kotlin 运行层」备选。
