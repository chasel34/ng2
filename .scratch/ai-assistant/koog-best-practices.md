Status: 已按「自主 agent + Koog 优先」修订；框架接入与主题流式面板已实现，其余业务适配继续推进

# NG2 的 Koog 使用方案与最佳实践

研究日期：2026-09-13。基于已确认的 [需求](spec.md)、项目 Kotlin 源码、Koog 1.2.0 发布源码以及当前 DeepSeek 官方文档。本文件区分框架已提供的能力、已完成的接入和后续业务适配建议。框架接入、Android 离线构建与 R8 设备冒烟验证已完成，具体结果见 [ADR-0006](../../docs/adr/0006-koog-agent-runtime.md)；业务能力及真实模型联调待完成。用户已要求优先使用 Koog 的完整能力，不自建等价机制：凡 Koog 已提供的循环、持久化、压缩、事件、重试与 skills 加载，一律通过其扩展点接入；App 只实现框架没有覆盖的论坛读取、来源、预算与界面。

DeepSeek V4.1 Flash 支持图片是已确认事实。当前 API 请求名使用 `deepseek-flash`；框架的预置模型表可能滞后，应由 App 维护经过验证的模型能力描述。图片能力、工具图片消息的转换及图片预算是三个不同问题。[DeepSeek 模型说明](https://api-docs.deepseek.com/quick_start/pricing/)、[Vision](https://api-docs.deepseek.com/guides/vision/)。

## 1. 自主 agent、Koog 通用循环与按需加载的 skills

用户明确不采用固定业务 workflow，同时要求优先使用 Koog 已有能力。两者不冲突：Koog 的策略图只是执行框架，预置的 `chatAgentStrategy()` 本身就是「请求模型 → 执行工具 → 回传结果 → 再请求」的通用循环，没有业务节点。建议以它为基线运行所有入口；列表、主题、楼层、回复链和个人分析通过上下文、任务与可用工具区分。不要在图里加入「概览后必须搜索」「最后必须再审稿」等业务路径。[预置策略](https://docs.koog.ai/predefined-agent-strategies/)、[自定义策略图](https://docs.koog.ai/custom-strategy-graphs/)。

选择策略图而不是函数式接口的原因：Persistence 的自动检查点按节点创建，`nodeLLMCompressHistory` 是图节点，二者在函数式接口下没有对应的框架能力，用函数式接口就得自建这两块。

流式输出：文档没有提供流式请求节点。需要在图中用 Koog 的 `llm.writeSession { requestLLMStreaming() }` 组成请求节点，处理 `StreamFrame`，在 `ToolCallComplete` 后交给 `nodeExecuteTools`。这是按 Koog 文档示例组合框架 API；若验证发现当前版本已有流式节点，直接使用。[Streaming API](https://docs.koog.ai/streaming-api/)。

| 部分 | 职责 |
| --- | --- |
| Agent | 理解当前问题，选择和组合 skills，决定读什么、搜索什么、是否读图与何时给出答案 |
| Tools | 提供读取楼层、分页、用户发言、图片、搜索和网页正文等明确能力，返回真实资料与状态 |
| Skills | 表达可复用的任务方法、检查要点、示例和输出要求；可按情况调整执行顺序 |
| Harness | 处理模型协议、工具执行、事件、存储、上下文容量、取消及已确认的访问/媒体范围 |

### Skills 的组织与加载

Koog 已提供 skills 发现及目录 prompt 生成，遵循 Agent Skills 的渐进加载方式：先让模型知道名称、用途和适用条件，使用时再读取正文及参考文件。它不自动提供所有工具实现，Android 的资源读取仍需接入。[Koog Skills](https://docs.koog.ai/skills/)、[Agent Skills 规范](https://agentskills.io/specification)。

首期直接使用 Koog 的加载路径：`discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(skillsRoot))` 得到描述，`generateSkillsPrompt(skills, SkillsPromptFormat.XML)` 生成目录，模型用框架自带的 `ListDirectoryTool` 与 `ReadFileTool` 按需读取正文和参考文件；不另写 read_skill 工具。发现要求绝对路径，因此随 App 打包的 SKILL.md 与 references 在启动或版本变化时释放到应用私有目录（目录名带版本），再交给 Koog 发现。技能目录和已激活的版本纳入会话记录。文档型 skills 即可覆盖当前分析任务，本期不注册脚本执行工具；若框架要求必须提供，注册一个固定拒绝执行的占位工具。

候选内置 skills：

- 讨论概览：归纳议题、主要分歧、代表性证据，说明覆盖范围。
- 事实核查：辨别可核查主张、寻找适合的来源、区分支持/反驳/无法确认。
- 批判性思考：检查前提与推理、查找反例、辨别事实与价值判断。
- 个人发言分析：尽可能读取约定的历史样本，关注本人观点、变化和反例，保持中立并附证据。
- 前情与回复关系：追踪引用、补齐上下文，解释各方正在回应什么。

这些是待细化的能力划分，不意味着为每个入口固定选择一串 skills。自然提问由模型根据目录选择；用户点击快捷操作时，App 发送对应任务并可显式指定 skill。首次概览同样通过任务和概览 skill 表达。新增技能不要求改动通用循环；skill 可以包含步骤，但不由执行器强制按业务节点运行。

例如用户要求核查某条回复：agent 可以发现关键说法来自引用，先回读原楼层，也可以先查外部资料；需要看截图时调用读图工具，发现原话是条件假设时解释语境。查询顺序、工具组合和必要的后续调查由模型根据资料决定。

App 仍提供已确认的入口范围和真实数据结构，例如第一页与当前页的去重、主楼和目标发言的关联。个人历史的读取方法放入 skill，由分页工具提供采样进度与去重结果，工具执行 300 条上限。Skills 不改变既定的采样目标，也不能解除个人分析纯文本限制。它们描述如何完成任务，工具和 harness 保证执行符合已确认的产品范围。

停止和网络失败可发生在任何时刻。已取得资料尽早入库，流式草稿独立保存，引用标识按实际来源解析；这些运行行为不要求建立固定分析步骤。

## 2. 把原始资料、模型工作上下文、聊天界面记录分开

| 数据 | 应保存什么 | 用途 |
| --- | --- | --- |
| 来源引用 | tid/pid、作者、时间、读取时间和内容哈希；不保存正文 | 引用预览时重新读取；帖子被清理时显示不可访问 |
| 模型工作上下文 | 当前问题、必要主楼与目标内容、近期完整对话、证据摘要、服务商必需字段 | 控制每次请求的 token 与协议合法性 |
| 用户可见历史 | 问答、快捷操作、覆盖范围、来源引用、未完成状态、来源入口 | 半屏/全屏切换和历史续聊 |

300 条是已确认的采样范围，不等于把 300 条全部拼入每次请求，也不等于未来工具只能访问这 300 条。初始个人分析需要真实获取可访问的最多 300 条独立发言；数据较长时全部进入分批整理流程，记录哪些已整理、哪些仅已下载。只处理了部分样本时，报告只能说明实际处理范围。

建议的工作上下文由系统规则、当前任务、主楼/目标内容、已读资料索引、相关证据、近期对话组成。长资料分块时保留发言边界和引用归属，每块摘要指向 sourceId；上层摘要同时记录反例、时间变化及未解决的问题。具体引文或有争议的判断需要回读原文。

先使用来源 ID、关键词及论坛结构定位资料；本期无需把向量数据库设为前置条件。以后需要跨大量会话检索时再增加索引模块。

## 3. 上下文整理使用 Koog 的压缩节点与内置策略

Koog 的 `WindowSizePreProcessor` 实现是 `messages.takeLast(windowSize)`，且 ChatMemory 预处理在读取和保存时都会执行。它不理解我们的主楼、300 条覆盖范围或工具调用配对，不能用于裁剪唯一的历史档案。[窗口实现](https://github.com/JetBrains/koog/blob/1.2.0/agents/agents-features/agents-features-memory/src/commonMain/kotlin/ai/koog/agents/chatMemory/feature/ChatMemoryPreProcessor.kt)。

整理上下文使用 Koog 的 `nodeLLMCompressHistory` 节点和内置策略，不自建上下文管理器：

- 先验证内置策略：`WholeHistory`（默认）、`Chunked`、`FactRetrievalHistoryCompressionStrategy`。对阅读场景，以「主楼要点、当前问题、来源映射、分歧与反例、未解决问题」作为概念列表的 `FactRetrievalHistoryCompressionStrategy` 最接近需求。
- 只有样本表明内置策略丢失来源映射或反例时，才继承 `HistoryCompressionStrategy` 实现 `compress`，仍运行在同一节点内。
- 压缩节点放在通用循环的固定位置：一组工具结果回传后、下一次模型请求前，按 token 估算决定是否进入。不在工具结果未收齐时压缩，不等到服务端拒绝后再压。
- 工作上下文预算计入文字、图片、工具定义、历史思考字段，并预留输出与下一次工具结果空间。
- 原文与用户可见历史继续保存在 Room，压缩只影响模型工作上下文。[历史压缩](https://docs.koog.ai/history-compression/)。

DeepSeek 的思考模式请求只要带 `tools`，后续请求就应回传历史 `reasoning_content`，即使之前某轮没有真正调用工具。保留在请求中的 assistant 消息必须保留其原始必需字段。压缩旧完整轮次时可建立新的工作上下文，以明确标识的摘要资料承接，不能给被保留的 assistant 消息伪造或清空 reasoning。该流程需真实协议测试。[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)。

稳定的系统规则、工具定义和资料前缀有助于 DeepSeek 缓存命中。避免把不断变化的进度、时间戳放在固定前缀最前面。记录实际 cache hit/miss usage，缓存命中仍不等于不占上下文或不产生费用。[上下文缓存](https://api-docs.deepseek.com/guides/kv_cache/)。

## 4. 图片作为真正的模型输入，并保留来源关系

初始单图规则不变：主题优先主楼第一张，否则当前页第一张；楼层/回复链在选定内容中取一张。个人分析默认与后续工具都不允许图片内容读取。

其余图片先提供轻量索引：imageId、所属 sourceId、顺序和已有的尺寸/说明。agent 通过 `read_image(imageId)` 按需读取。图片字节保存在本机文件缓存中，Room 保存引用、内容哈希和状态，避免把 base64 在多个表和检查点反复保存。

### 首期推荐的 DeepSeek 适配路径

复用 Koog 的 DeepSeek 客户端和 Chat Completions 协议，显式定义 `deepseek-flash` 的图文与工具能力。预置模型能力不完整通过自定义 LLModel 解决，这是框架扩展点。工具结果里的图片按以下顺序处理，不自建客户端：

1. 先核对当前 Koog 版本：`ReceivedToolResult.parts` 支持图片，1.2.0 源码读到的基础 Chat Completions 客户端会把工具结果转成文本、忽略图片，但普通 user 内容里的图片能转成 URL/base64 图片块。若新版本已修正，直接使用。
2. 若仍丢弃，向 Koog 提 issue 或 PR。期间用 Koog 的 prompt API 在 `llm.writeSession { updatePrompt { user { image(...) } } }` 追加带来源说明的图文资料：读图工具先返回写明 imageId/sourceId 的文本结果，该批工具结果完整回传后再追加图文消息。该资料是工具获取的内容，在 App 记录中保留真实来源，不能在聊天界面伪装成用户主动发了消息。转换要有稳定标识防止恢复后重复追加，也不能让待发送图片绕过个人分析的媒体限制。
3. 该追加改变了轮次结构，须在验证矩阵中单独确认：思考模式带 tools 时 reasoning_content 回传仍合法，缓存前缀未被打断，模型不把它当作用户发言。[工具结果类型](https://github.com/JetBrains/koog/blob/1.2.0/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/environment/ReceivedToolResult.kt)、[Chat Completions 转换](https://github.com/JetBrains/koog/blob/1.2.0/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/AbstractOpenAILLMClient.kt)。

DeepSeek 的 Responses 格式已支持工具结果包含 `input_image`。只有 Koog 提供对应客户端时才把它列为备选；不为此自写协议实现。不能把“Responses 能接收工具图片”等同于“现有 Koog DeepSeek 客户端已使用该协议”。[Responses 图片输出](https://api-docs.deepseek.com/guides/responses_api/)。

### 图片开销

App 在下载时限制字节，在解码前读取尺寸，按预算缩放并限制并发。普通图片可先低分辨率读取；长截图、图表、含细字的图片需要保留可读性，必要时由工具读取局部区域。初始只放一张，不能自动把一张长图切成几十张加入首次概览。

近期相关图片可留在工作上下文，旧图片在完成观察后转成带 sourceId 的描述，必要时重新读取。图片描述是模型观察，不能当作完整原图或无误转录。Files API 可减少重复上传，但不会自动消除模型读图的 token；首期不必为了单图默认场景先引入远端文件生命周期管理。[Vision](https://api-docs.deepseek.com/guides/vision/)。

## 5. 工具边界与读取覆盖

建议用显式类型、序列化和参数 schema 定义 Koog 工具，构造时注入仓库。工具处理 scope、权限、分页、大小及失败，模型只看到本次能使用的工具。论坛内容与网页正文作为不可信资料，不进入 system 指令。[自定义工具](https://docs.koog.ai/tools/class-based-tools/)。

| 工具组 | 业务接口方向 | 返回信息 |
| --- | --- | --- |
| 论坛 | 读取主题页、定位楼层、读取回复链、检索已读资料 | sourceId、内容、实际范围、尚未读取的关联内容 |
| 用户历史 | 分页读取主题/回复，必要时补齐发言和主楼 | 目标作者自己的发言、样本去重键、时间、游标、缺失状态 |
| 图片 | 列出图片、读取指定图片或局部 | 图像附件引用、所属发言、读取精度、失败原因 |
| 外部搜索 | DuckDuckGo Lite 搜索 | 标题、链接、摘要、查询与获取时间、是否成功 |
| 外部正文 | 读取选定结果链接 | 网页正文、页面标题、sourceId、正文是否完整可读 |

论坛读取复用现有 READ 流程。TopicRepository 的原始读取、热门回复与缓存已位于 data/topic，UI 的 TopicPageLoader 负责渲染与引用预览组装；论坛工具可直接复用 data 层入口。UserPostsRepository 的列表状态是 UI 缓存，不能作为完整历史永久来源；AI 读取应使用相同 API 能力，同时维护自己的采样进度。

工具结果区分“没有更多内容”“无权限”“已删除或已被论坛清理”“请求失败”“内容只有摘要”，避免模型把失败当作事实不存在；论坛工具返回前套用屏蔽规则并报告过滤数量，匿名作者以「匿名用户」返回。系统规则告知 agent：历史对话中读到的帖子可能已被清理，需要引用原句时重新读取。来源 ID 与实体分开：论坛实体键是 `(tid,pid)`，sourceId 标识实际读取的版本；外部来源保存原始 URL 与获取时间，引用指向本次获得的资料。

初次普通概览优先使用已准备的论坛资料；事实核查在有可核查主张时检索外部证据，批判性思考按需要检索。搜索 query 只带查证所需信息，避免将整段论坛对话或用户历史作为搜索词。搜索失败允许继续解释论坛内容，但不得声称已完成外部核查。

每次模型提出工具调用后，先收齐该次响应的完整工具参数并校验，再执行。工具并行必须受各类资源限制：同一用户的分页按顺序，独立来源可有限并行，图片解码与 DuckDuckGo 请求单独限流。相同工具参数和资料版本优先复用已有结果；多次没有获得新资料时应结束检索并说明局限。

## 6. 持久化：Persistence 与 ChatMemory 接 Room，Room 只存 App 自己的数据

- 运行中恢复用 Koog Persistence。策略图默认开启连续持久化，每个节点执行后自动创建检查点，内容含消息历史、最后节点及其输出、模型与工具选择、`AIAgentStorage`。实现 `PersistenceStorageProvider`（`getCheckpoints`/`saveCheckpoint`/`getLatestCheckpoint`）把检查点写入 Room，按 conversationId 与 runId 归档。用户选择继续时用 `rollbackToLatestCheckpoint` 从最近节点边界恢复，让模型决定下一步。只有可序列化值进入检查点，工具结果和图片引用要设计成可序列化，图片字节留在文件缓存。[Persistence](https://docs.koog.ai/features/agent-persistence/)。
- 跨运行的对话历史用 ChatMemory，实现 `ChatHistoryProvider`（`store`/`load`）接 Room，以 conversationId 寻址。它只在 `agent.run()` 成功后保存，中途失败或取消不保存本轮；这正好与 Persistence 分工：成功轮次归 ChatMemory，中途状态归检查点。预处理只用 `ChatMemoryPreProcessor` 扩展点（例如剔除已过期的图片块），不用 windowSize。[ChatMemory](https://docs.koog.ai/features/chat-memory/)。
- Room 自己的表只保存框架不覆盖的 App 数据：用户可见消息与流式草稿、引用坐标、用量账本、skills 版本、运行状态与中断原因。不保存论坛原文快照；检查点与 ChatMemory 里的已读正文只作为续聊工作上下文，随对话删除。收到用户输入即保存；回答草稿生成期间节流保存，结束或停止时提交状态，不只订阅成功完成事件。
- 已成功获取的工具结果随检查点复用；没有完整接收的模型输出作为未完成草稿保留，不能伪装成完整协议记录。

App 内区分 `conversationId`（持续对话）和 `runId`（本次用户操作/执行）。Koog 的 `agent.run(input, sessionId)` 将传入的 sessionId 用作内部 runId，ChatMemory 使用这个值，不能把它直接当作 App 本次执行 ID。建议 ChatMemory 以 conversationId 寻址，App 另行记录 runId 关联消息与工具事件。继续中断运行时只采用一份选定的工作上下文，避免 ChatMemory 再覆盖或追加，防止重复问题和图片。[Agent ID 映射](https://github.com/JetBrains/koog/blob/1.2.0/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/AIAgentBase.kt)、[ChatMemory 实现](https://github.com/JetBrains/koog/blob/1.2.0/agents/agents-features/agents-features-memory/src/commonMain/kotlin/ai/koog/agents/chatMemory/feature/ChatMemory.kt)。

会话要记录来源入口、原始阅读范围、实际模型、prompt 和已激活 skill 版本。重新从帖子入口发起会新建 conversationId；收起再展开使用原 conversationId。读取缓存的淘汰不删除 AI 历史。遵循现有 Room 正式迁移约定。

## 7. Android 生命周期、取消与流式输出

运行管理器由 App 级可注入 scope 管理，复用项目现有 SupervisorJob 方式。每条会话同一时间只有一个执行任务；同一会话的重复点击不能启动两个相互覆盖历史的运行。每个运行绑定不可变的入口、凭证配置和预算。工具运行期间仍按当前真实权限校验论坛访问。

界面进度用 Koog 的 EventHandler：`ToolCallStartingEvent`/`ToolCallCompletedEvent`/`ToolCallFailedEvent` 映射为「正在读第 N 页」「搜索资料」「读取图片」，`LLMStreamingFrameReceivedEvent` 驱动逐字显示，`AgentExecutionFailedEvent` 驱动失败卡。不另写事件总线。[EventHandler](https://docs.koog.ai/agent-events/)。

UI 通过 StateFlow 观察会话；收起 sheet、进入全屏、打开引用预览仅改变观察者，不终止任务。用到 UI 可见性时选择生命周期感知收集，但不让订阅消失决定后台任务取消。CPU 较重的正文处理、图片解码和序列化不在主线程执行。[Android 协程最佳实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)。

停止时取消该 run 的 Job，传递到模型流、论坛读取、搜索和图片请求，并保留未完成回答。CancellationException 必须继续传播；停止不是可重试错误。进程被系统结束后，下一次打开标记未完成运行中断，等待用户重试或继续，不因打开历史就自动发起付费请求。

Koog 通过 Flow<StreamFrame> 提供文本、reasoning 和工具调用的增量及完整帧。文本增量用于显示，完整帧用于保存协议记录。不能只收集文本后再手工推断工具；也不能把所有模型中间文本都当作最终答案。App 将工具事件映射为“正在读第 N 页”“搜索资料”“读取图片”等状态。reasoning 原始字段属于协议数据，与最终回答分开管理。[Streaming API](https://docs.koog.ai/streaming-api/)。

## 8. 预算、重试与 BYOK

至少分别控制本轮模型调用次数、工具次数、输入/输出与图片预算、总耗时、下载字节和资源并发。记账放在 EventHandler 的模型调用与工具调用事件里，停止条件通过图的边条件与 Koog 的最大迭代数配置表达；策略图的迭代保护只限制轮数，不理解费用，费用预算仍由 App 在发送前检查。资源预算限定开销，分析路径和工具顺序由 agent 决定。具体数值由真机延迟和代表性论坛样本确定。

预算不足时保留已读资料和已得结论，明确覆盖范围并允许用户继续。摘要调用也计入预算；重试也可能产生费用。个人分析的 300 条与主题初始页数由业务规则保证，不能让预算机制静默改变范围后仍声称完整分析。

模型层自动重试只用 Koog 的 RetryingLLMClient 及其 RetryConfig：配置可重试的错误类别与次数，认证、参数和余额错误不列入。它在流式输出首个 frame 之前才重试，开始输出后传播失败，取消不重试；未收到 frame 不能证明服务端尚未执行，所以「已发送但结果未知不重放」依赖它的这一既有行为再加上 App 的记账，不另写重试逻辑。配置无法表达的需求向上游反馈。NGA 工具保留现有 READ 恢复流程，不在最外层重复重试整次 agent，以免调用层层相乘。[重试实现](https://github.com/JetBrains/koog/blob/1.2.0/prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/retry/RetryingLLMClient.kt)。

用户已强调开销控制与异常处理。更完整的预算预留、usage 不完整、跨会话限额、失败分类及恢复建议见 [开销控制与异常处理](cost-and-recovery.md)；不能将本机费用估算表述为服务商账单的精确硬上限。

模型 Key 采用现有 Keystore 加密模式，单独的版本化存储；只发送给对应模型 endpoint。模型/网页 HTTP 配置不使用 NGA CookieJar。日志记录请求耗时、状态、token 使用和工具类型，默认不记录正文、图片 base64、Key 或论坛凭证。

Provider 适配保留 `reasoning_content`、图片输入方式、缓存 usage 等专有字段。统一接口应统一“如何使用”，不能把每家协议简化成只有 role/content。首期默认服务 DeepSeek，未来增加模型客户端时验证其图文、工具和续聊兼容性；不会静默把用户内容发给另一个服务商。

## 9. 模块范围与验证顺序

已接入 Koog 1.2.0 稳定模块与配套 1.2.0-beta 模块，不直接依赖全部模型和后端的聚合包。首期依赖已包含 agents core（含预置策略与压缩节点）、ChatMemory、Persistence、EventHandler、skills、框架文件工具、DeepSeek 客户端与 OkHttp HTTP 后端；其中 skills、文件工具与 DeepSeek 客户端使用 beta。不启用检索、嵌入、向量存储或长期记忆，允许 skills/文件工具的文件系统接口所需的 `rag-base` / `rag-base-android` 传递依赖。业务类型与规则归 core/ai；Koog 运行及模型适配位于 data/ai，IO 二进制兼容桥因调用签名要求使用上游包名，具体边界见 [ADR-0006](../../docs/adr/0006-koog-agent-runtime.md)。内置 skills 的目录、按需加载及版本记录纳入首期；用户自定义/动态安装、MCP 和多 agent 协作不作为当前前置要求。[模块版本](https://docs.koog.ai/module-versioning/)。

实际依赖解析已确认核心带入 Ktor、Jackson、OpenAI 等传递依赖，不盲目 exclude，也不据此注册其他模型服务商。[ADR-0006](../../docs/adr/0006-koog-agent-runtime.md) 已记录对 ADR-0004「裸 OkHttp、只用 stable」的例外，以及 Android IO 兼容桥与精确 R8 规则。当前 Kotlin/AGP/OkHttp 下的依赖解析、debug/release APK 构建、离线工具循环及 R8 设备冒烟均已通过，依赖增量与包体数据见 [接入测量](../../docs/adr/0006-koog-integration-measurements.md)。以下业务验证仍待后续实现；性能只从真机 release 包测量。

| 验证 | 必须看到的结果 |
| --- | --- |
| 主楼+当前页、楼层与链 | 原文归属、去重及初始范围符合需求；能通过工具读取额外页面 |
| 最多 300 条个人发言 | 主题+回复合计去重，引用他人的话不变成被分析者观点，覆盖数量真实 |
| 初始图片与工具读图 | 模型真实接收到图片块；后续图片未被客户端丢弃；个人分析没有图像输入 |
| 两次连续工具调用后续聊 | tool call ID 和 reasoning 完整回传，不出现 400；保留文本与图文资料 |
| 压缩后继续追问 | 主楼、当前问题、反例和引用映射存在，能够回读精确原文 |
| 流中断、停止、收起与进程重建 | 部分回答存在、停止不重试、收起继续、重建不自动发起付费运行 |
| ChatMemory + Persistence 接 Room | 会话/执行 ID 不混淆、工作上下文不覆盖 UI 档案、从检查点继续时不重复输入或工具结果 |
| 内置压缩策略质量 | FactRetrieval/WholeHistory 压缩后主楼要点、来源映射与反例仍在；不足时才换自定义策略 |
| 工具图片追加消息 | 追加图文资料后思考字段回传合法、缓存前缀命中、模型不把它当用户发言 |
| Skills 的选择与组合 | 快捷操作能激活对应方法；自然提问能按需选择/组合技能，不被固定业务顺序限制 |
| Skills 与上下文容量 | 未使用的正文不整包注入；长对话后可重载技能，版本可追溯，技能不能扩张工具权限 |
| 搜索失败与网页只有摘要 | 明确区分验证页/无结果/失败，不产生已核查的假结论 |
| 恶意正文与越界参数 | 正文不变成指令，模型不能调用未注册工具或把论坛凭证用于外部请求 |

已使用假执行器、工具和协程测试环境验证通用循环，并以 MockWebServer 验证 Koog DeepSeek HTTP 的序列化、响应解码及论坛 Cookie/连接隔离；同组测试的 R8 设备冒烟也已通过。后续继续补齐上表的业务协议与流程验证，再以真实 DeepSeek、NGA READ 和用户手机网络验证质量与性能。当前未调用真实付费模型，离线接入验证不代表业务能力或真实模型联调已完成。

## 主题流式面板已落实的契约

- 引用固定为 `[[sN]]`（N 为从 1 开始的十进制整数），App 为本会话实际读取的去重来源分配 ID 并映射 tid/pid/楼层；未知 ID 与非法闭合标记丢弃，流式尾部未闭合标记隐藏至补齐，停止后也不显示半截标记。
- 回答使用自研 Compose Markdown：段落、标题、列表、引用、强调、代码、链接与表格，`InlineTextContent` 渲染来源标签，不使用 WebView、不执行 HTML。详细选型与协议见 [ADR-0006](../../docs/adr/0006-koog-agent-runtime.md#主题面板的流式策略与引用)。
- `TopicAgentRuntime` 保留 chat 图的通用工具循环结构，以 writeSession 流式请求组成节点，EventHandler 映射进度；允许首轮纯文本结束，避免 Koog 1.2.0 预置策略强制首轮调用工具而导致无工具时循环。已注册五个论坛读取工具，Persistence 与 ChatMemory 经绑定会话/运行 ID 的 Room provider 保存，成功工具结果与回复链游标用于中断恢复；工具图片在工具结果后追加带来源说明的 user 图文消息，成功轮次保留完整 Koog 协议消息，停止与失败不自动重放。
- 初始范围复用 TopicRepository，先应用本地及官方屏蔽规则、匿名去假名与单张图片规则，再发送。图片经有界下载与解码后使用 Koog 二进制 Image API；不能向 URL 重载直接传 data URI。来源点击先按坐标重新读取并打开原文预览，确认跳转后收起当前面板，加载目标楼层后高亮；预览固定当前账号且禁用主题缓存回退。
- 半屏顶部与拖动阈值按设计稿 332/844、170/844、560/844 比例适配可用高度；临时收起不取消 ViewModel 中的执行，胶囊恢复半屏并保留聊天位置。

## Comments

- 2026-09-13：完成基于 Koog 1.2.0 的最佳实践探索，确认 V4.1 Flash 图像能力，并定位工具图片转换、ChatMemory 成功后保存、窗口粗截断、会话 ID 与执行 ID 混用的接入风险。建议采用简单图式策略、Room 分层持久化、按协议传递的图片资料与按完整轮次压缩的工作上下文。方案待讨论。
- 2026-09-13：用户明确希望 agent 自主决定分析路径，固定方法应由 skills 表达。撤回图式业务编排及图检查点方案，改为 Koog 函数式通用工具循环、按需加载内置 skills、Room 消息和工具执行记录。保留原有上下文范围、图片能力与限制、历史保存及停止行为。
- 2026-09-13：细化费用和异常恢复，补充“尚未收到 frame 不等于未执行”，模型重试需结合发送状态、结果是否确定及共享预算。
- 2026-09-13：按用户逐条确认更新来源表、工具状态与 Room 范围：不保存原文快照，工具区分已清理状态并套用屏蔽规则。
- 2026-09-13：用户要求优先使用 Koog 完整能力、不自建等价机制。改为以 `chatAgentStrategy()` 通用循环图为基线（非业务 workflow），Persistence 检查点与 ChatMemory 通过 provider 接 Room，压缩用 `nodeLLMCompressHistory` 与内置策略，进度用 EventHandler，重试只用 RetryingLLMClient，skills 用 `discoverSkills` 与框架文件工具。撤回自写 read_skill、自建上下文管理器与自建重试的建议；核对了 Koog 文档中的策略、Persistence、ChatMemory、压缩、事件、多模态与 skills 页面。

- 2026-09-13：票 01 已实现并完成离线构建、单测与 release 模拟器验证；具体接入、Android ABI 兼容修复及依赖/包体数据见 [实现报告](reports/01-impl.md) 与 [ADR-0006](../../docs/adr/0006-koog-agent-runtime.md)。本记录只更新框架接入进度，不表示其余一期能力已实现。
