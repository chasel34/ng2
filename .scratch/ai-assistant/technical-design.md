Status: 技术方案已按 Koog 优先修订；框架接入与主题流式面板已实现，其余实现票继续推进

# AI 阅读与对话助手技术方案

产品范围以 [已确认需求](spec.md) 为准。用户已确定首期 DeepSeek、BYOK、DuckDuckGo 搜索，并要求基于 Koog 探索 Android 本机 agent harness。框架比较依据见 [框架研究](framework-research.md)，当前具体建议见 [Koog 最佳实践](koog-best-practices.md)。

## 建议方向

在现有 Android 应用内运行对话与工具调用流程。模型负责回答与提出读取请求，App 负责读取论坛内容、执行工具、控制上下文、维护来源和保存对话。现有页面通过统一入口提供当前内容范围，半屏和全屏复用同一会话。

按用户要求，采用自主 agent 与按需加载的 skills，并优先使用 Koog 的完整能力、不自建等价机制。运行基线是 Koog 预置的 `chatAgentStrategy()` 通用循环图：它只有请求模型、执行工具、回传结果三类节点，不是业务 workflow；总结、事实核查、批判性思考和个人发言分析的方法放在 skills 中，由模型选择。运行中恢复用 Koog Persistence 检查点，跨运行历史用 ChatMemory，二者通过 provider 接口写入 Room；上下文整理用 `nodeLLMCompressHistory`，进度用 EventHandler，模型层重试用 RetryingLLMClient，skills 用 `discoverSkills` 与框架文件工具。Room 只保存框架不覆盖的 App 数据：用户可见消息、来源快照与引用、用量账本、skills 版本。

沿用 Kotlin、Compose、Flow、Hilt、OkHttp、Room、DataStore。模型、搜索与论坛读取分别适配，避免服务商的接口格式影响所有页面。Koog 使用 OkHttp 后端并显式传入 HTTP factory；其核心带 Ktor 等传递依赖，这是采用框架的已接受代价。[ADR-0006](../../docs/adr/0006-koog-agent-runtime.md) 已记录对 [ADR-0004](../../docs/adr/0004-native-stack-compose-bare-okhttp.md)「裸 OkHttp、只用 stable」的例外（DeepSeek、skills 与文件工具使用 beta），版本目录已注明各模块用途与例外原因。依赖解析、离线构建与 R8 设备冒烟已通过，依赖增量和包体数据见 [接入测量](../../docs/adr/0006-koog-integration-measurements.md)；业务能力及真实模型联调待完成。

具体协议细节和数据结构以适配验证为准。业务规则、来源与 Room 数据模型归 App 所有；通过小范围的 AgentRuntime 适配接入框架，避免在所有页面和历史表中直接使用框架内部类型。

## 现有能力与复用边界

| 当前代码 | 可复用能力 | 需要补充或调整 |
| --- | --- | --- |
| [TopicRepository](../../app/src/main/kotlin/com/chasel/ng2n/data/topic/TopicRepository.kt) | 原始主题页读取、缓存、按 pid 查楼层、已加载页 | 原始读取与缓存位于 data/topic，UI 的 TopicPageLoader 负责渲染与引用预览组装；后续 AI 工具直接复用原始读取 |
| [TopicListRepository](../../app/src/main/kotlin/com/chasel/ng2n/data/board/TopicListRepository.kt) | 当前版块、排序、已加载主题 | AI 入口捕获当前筛选后的列表快照，限制 300 条 |
| [UserPostsRepository](../../app/src/main/kotlin/com/chasel/ng2n/data/user/UserPostsRepository.kt) | 用户主题与回复分页 | 合并两类发言的时间顺序、去重至 300 条，必要时补齐正文；可访问历史的完整性仍需真实样本验证 |
| [ReplyChain](../../app/src/main/kotlin/com/chasel/ng2n/core/local/ReplyChain.kt) | 引用关系和回复链构建 | 复用纯算法，补充来源范围、缺失楼层状态 |
| [BBCode AST](../../app/src/main/kotlin/com/chasel/ng2n/core/bbcode/Nodes.kt) | 文本、引用、图片和其他内容结构 | 生成面向 AI 的正文与媒体索引，保留作者归属和原文引用，不把整串 BBCode 当纯文本拼接 |
| [DataModule](../../app/src/main/kotlin/com/chasel/ng2n/di/DataModule.kt) | 应用级 IO scope、Room、DataStore | AI 任务按会话独立管理，收起面板不取消，停止操作传递到当前网络请求 |
| [本地存储约定](../../docs/storage.md) | 数据迁移、凭证加密与日志脱敏 | 新增聊天与来源持久化，模型 Key 单独保存，AI 历史不受浏览历史和主题缓存淘汰规则影响 |

## 职责划分

### core/ai：纯 Kotlin 规则和契约

定义内容入口、消息、工具请求与结果、来源引用、上下文规则、预算和对话状态。首期内置快捷操作以独立定义表达，包含适用入口、展示文案、用户任务和可选的 skill 标识；后续自定义可以复用同一执行路径。skill 目录元信息常驻，正文及参考资料按需读取。

默认范围、图片上限和个人分析纯文本限制必须由规则控制，不能只依靠 prompt 约束。

引用是 core/ai 的正式契约：模型输出使用 `[[sN]]` 标记指向 sourceId；UI 丢弃未知 ID 并隐藏流式半截标记。skills 的输出要求、回答渲染与来源预览共同使用这一契约，具体规则已记录于 ADR-0006。

### data/ai：读取、模型通信和对话运行

提供论坛读取适配、模型协议适配、图片读取、联网搜索、网页文本读取和会话存储。模型请求使用独立的 HTTP 配置，不携带 NGA 登录 Cookie；论坛数据读取仍经现有 NgaClient 的 READ 流程。

App 在入口处捕获范围、创建会话并提供默认上下文与任务。之后由 Koog 策略图运行通用循环：调用模型，执行模型提出的有效工具请求并回传，直至模型回答或用户停止。加载 skills 也是可用能力，任务的分析与调查顺序由模型决定。保存、预算与协议校验通过 EventHandler 事件和工具实现作用于每次请求和工具调用，不构成业务 workflow。过程中通过 Flow 更新界面。

论坛工具共用一个 NGA 请求速率限制器：agent 可能连续发起几十次 read.php，需与现有 READ 链的重试分开限速。论坛工具在返回前套用当前屏蔽规则与官方屏蔽词，并报告被过滤的数量；匿名作者以「匿名用户」返回，不带本地假名。工具结果区分「已删除或不可访问」与「请求失败」，续聊时系统规则告知 agent 早先读到的内容可能已被论坛清理。网页读取工具设 URL 策略：拒绝私网地址与 localhost，不对任何域名携带 NGA Cookie。

工具候选为读取主题页、定位楼层、读取回复链、查询用户历史、按关键词检索相关内容、列出图片、读取指定图片、搜索网页和读取网页。参数应以已识别来源和论坛标识为主，工具返回实际读取范围与失败信息。

论坛正文、图片文字、网页内容均作为资料处理，不作为改变 App 行为的指令。工具限于本次功能所需读取能力；来源索引由 App 维护，引用指向已获得的资料。

### ui/ai：共享聊天与历史

各内容入口只负责提供来源范围。统一会话控制半屏、全屏、生成状态、快捷操作、来源预览和历史记录。聊天回答单独渲染，论坛原文预览复用已有正文能力。回答渲染需要流式 Markdown 与引用标记解析，项目现无 Markdown 渲染能力，已选择自研 Compose Markdown，协议与选型见 ADR-0006。

临时收起与重新发起分析应是不同操作：前者保留并继续当前会话，后者基于新的来源范围新建。不能仅凭 tid 判定所有入口共用一条历史对话。

## 上下文与图片

将完整已读取资料与每次送入模型的上下文分开。列表的 300 条是主题摘要记录，个人分析的 300 条是独立发言；二者成本不同。个人分析的回复条目自带正文（`searchpost=1` 的 `__P`），主题条目只有标题，是否为主题条目补读主楼由规则和预算决定并在报告中说明。长主题、回复链和长篇历史超出模型容量时，用 Koog 压缩节点整理并保留来源映射，后续仍可回读原文；不得静默截断后声称已覆盖全部默认范围。

图片保留所属主题、楼层和可读取标识。首次遵循已确认的单张规则，后续允许按问题增读；读图不受「仅 Wi-Fi 加载图片」设置限制。通过图片尺寸、单次读取数量和对话预算控制开销，避免将全部图片在每轮对话中重复带入。个人分析关闭图片内容读取路径。

具体 token、调用轮数、图片尺寸和超时限额应在服务及模型确定后，结合样本测试设定；不提前写死未经验证的数值。预算用尽时保留已完成结果并说明覆盖范围，支持用户继续。

开销与异常的产品原则已确认，详细规则见 [专项方案](cost-and-recovery.md)。每次用户操作内的模型调用、摘要和重试共享预算，多对话统一预留开销；金额是依据服务商 usage 与价格表的估算，结果不明的请求不能视为免费。自动重试应区分传输阶段和错误类别，不能仅凭尚未输出文字就重发。具体额度待样本验证，界面待评审。

## 对话持久化与生命周期

Koog Persistence 与 ChatMemory 分别通过 `PersistenceStorageProvider` 和 `ChatHistoryProvider` 写入 Room，负责运行中检查点与跨运行工作上下文。Room 另保存会话、用户可见消息与草稿、来源快照及引用关联、用量账本。会话采用独立标识，同一主题可有多条；来源记录保留原入口和当时的阅读范围。用户已确认不保存论坛原文快照：Room 永久保留对话、回答、阅读范围与引用坐标（tid/pid、作者、时间、读取时间），引用预览每次经 READ 流程重新读取，读不到时显示已删除或不可访问。检查点与 ChatMemory 中的已读正文属于模型工作上下文，为续聊所需，随对话删除而删除，不作为原文档案或引用预览来源。历史全账号共享，预览使用当前账号权限。

新增表遵循 [Room 正式迁移约定](../../docs/adr/0005-room-migrations-keep-user-data.md)，升级不能丢失聊天与已有书签。设置与密钥遵循 [存储约定](../../docs/storage.md)，日志不得原样记录密钥、论坛凭证和聊天正文。

任务生命周期独立于半屏／全屏组件。应用进程结束后，将未完成任务恢复为中断状态并允许重试；“面板收起继续”不等于承诺被系统杀死后仍后台运行。是否需要持续后台服务属于另一个产品决定，目前不默认加入。

## 模型接入与联网：方向已确定，适配待验证

首期 DeepSeek、BYOK，后续增加服务商。用户密钥按服务配置单独加密保存。模型请求在手机发起，服务商配置、模型能力和对话入口解耦；每条会话保留实际使用的服务与模型，来源和消息可独立于 SDK 保存。

DeepSeek V4.1 Flash 已支持图片，当前官方推荐的请求标识为 deepseek-flash。首期按它的图文、工具调用和思考能力设计。优先复用 Koog 的 DeepSeek Chat Completions 客户端，并维护当前模型能力描述。Koog 1.2.0 的基础 Chat Completions 转换会丢弃工具结果中的非文本内容，因此需要在媒体适配步骤中，将读图结果的实际图片放入附带来源说明的 user 图文消息；不能只补 Vision.Image 声明就视为完成接入。具体路径及 Responses 备选见最佳实践文件。

协议兼容性与能力分别判断：流式输出、工具调用、图片输入和服务商托管搜索不能仅根据一个兼容地址推断。模型能力不满足某项已确认需求时，应明确说明，而不能静默将功能视为完成。

DuckDuckGo 作为独立搜索工具，优先评估 Lite 页面解析，返回标题、链接和摘要；另设网页正文读取工具。当前机器的一次匿名检查中，Lite 返回 10 条结果，HTML 版本返回人机验证。需要缓存、请求节制、失败状态和手机网络验证；搜索失败时可继续基于论坛资料回答，并明确外部事实核查尚未完成。不将验证页当作零结果，也不自动换为收费服务。

OpenAI 官方资料确认工具调用由模型发出、应用执行并回传结果；官方 Web Search 有独立接口与模型条件，不能视作所有兼容聊天接口的通用能力。这里只将其作为适配设计依据，没有决定使用某个 OpenAI 模型。

- [Function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [Web search](https://developers.openai.com/api/docs/guides/tools-web-search)
- [Images and vision](https://developers.openai.com/api/docs/guides/images-vision)
- [DeepSeek Vision](https://api-docs.deepseek.com/guides/vision/)
- [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)
- [DuckDuckGo HTML 与 Lite](https://duckduckgo.com/duckduckgo-help-pages/features/non-javascript)

## 建议实施顺序与验证

0. Koog 首期模块接入、依赖解析、R8 与包体测量及 ADR 草稿已完成（票 01）；TopicRepository 原始读取层已抽取到 data/topic，UI 渲染仍位于 ui/topic。
1. 定义来源、引用标记、默认上下文、会话状态与模型契约，验证六类入口、去重和图片规则。
2. 用 `chatAgentStrategy()` 打通主题第一页／当前页的真实读取、一次工具调用、流式回答与停止，形成最小完整流程。
3. 接入 Persistence 与 ChatMemory 的 Room provider、来源保存、半屏／全屏和引用预览，验证收起、重进、检查点续跑、历史续聊与迁移保数据。
4. 补齐列表、单楼、回复链和个人分析入口，验证跨页前情、300 条上限、个人分析纯文本与缺失记录。
5. 接入选定的联网搜索和按需读图，验证工具循环、预算、取消、重试及真实来源引用。

分步用于控制实现和验收顺序，完成标准仍包含全部已确认的一期需求。实现票见 [issues](issues/)，每张票的 Blocked by 给出前置关系；界面以 [设计稿](../../design/ai-assistant/README.md) 为准。离线测试重点覆盖规则和工具状态，网络协议使用现有 MockWebServer；持久化迁移与移动交互按项目约定进行设备验证。实际 AI 服务与 NGA 的读取冒烟另行验证，不执行论坛写操作。

## 主题流式面板已落实的契约

- 引用固定为 `[[sN]]`（N 为从 1 开始的十进制整数），App 为本会话实际读取的去重来源分配 ID 并映射 tid/pid/楼层；未知 ID 与非法闭合标记丢弃，流式尾部未闭合标记隐藏至补齐，停止后也不显示半截标记。
- 回答使用自研 Compose Markdown：段落、标题、列表、引用、强调、代码、链接与表格，`InlineTextContent` 渲染来源标签，不使用 WebView、不执行 HTML。详细选型与协议见 [ADR-0006](../../docs/adr/0006-koog-agent-runtime.md#主题面板的流式策略与引用)。
- `TopicAgentRuntime` 保留 chat 图的通用工具循环结构，以 writeSession 流式请求组成节点，EventHandler 映射进度；允许首轮纯文本结束，避免 Koog 1.2.0 预置策略强制首轮调用工具而导致无工具时循环。已注册五个论坛读取工具，Persistence 与 ChatMemory 经绑定会话/运行 ID 的 Room provider 保存，成功工具结果与回复链游标用于中断恢复；工具图片在工具结果后追加带来源说明的 user 图文消息，成功轮次保留完整 Koog 协议消息，停止与失败不自动重放。
- 初始范围复用 TopicRepository，先应用本地及官方屏蔽规则、匿名去假名与单张图片规则，再发送。图片经有界下载与解码后使用 Koog 二进制 Image API；不能向 URL 重载直接传 data URI。来源点击先按坐标重新读取并打开原文预览，确认跳转后收起当前面板，加载目标楼层后高亮；预览固定当前账号且禁用主题缓存回退。
- 半屏顶部与拖动阈值按设计稿 332/844、170/844、560/844 比例适配可用高度；临时收起不取消 ViewModel 中的执行，胶囊恢复半屏并保留聊天位置。

## Comments

- 2026-09-13：在一期需求整体确认后整理技术初稿，核对现有源码、分层与存储约定，以及官方工具调用、搜索和图片文档。服务接入方式尚待回答，未新增依赖、修改应用源码或执行模型请求。
- 2026-09-13：记录用户选择 DeepSeek、BYOK、DuckDuckGo，以及 Android 本机 harness 优先条件。完成 Pi、Codex、Koog 官方资料与 Koog 1.2.0 源码探索；暂推荐验证 Koog，应用尚未接入框架。
- 2026-09-13：用户要求基于 Koog 继续探索。核实 ChatMemory 仅在成功结束时保存、窗口截断不保证工具配对、Chat Completions 工具图片被忽略，以及 DeepSeek 思考字段回传要求。新增最佳实践方案，包含图式执行、Room 增量保存、媒体转换与验证矩阵；未修改应用实现。
- 2026-09-13：用户明确不采用固定 workflow，要求方法通过 skills 提供。撤回图式业务编排建议，改为 Koog 函数式通用循环、按需加载内置 skills，以及 Room 消息和工具记录恢复；其余已确认产品行为保持有效。
- 2026-09-13：补充开销控制与异常处理专项草案，明确共享预算、未结用量、分类重试及恢复不会自动重放整次分析。
- 2026-09-13：用户确认开销与异常处理的产品原则；剩余额度和技术细节以验证确定，不重复作为需求问题提问。
- 2026-09-13：按用户逐条确认的补充项更新：屏蔽过滤、匿名处理、不保存原文快照、帖子清理告知、历史全账号共享、读图不受 Wi-Fi 设置限制。
- 2026-09-13：评审后用户要求优先使用 Koog 完整能力、不自建等价机制。运行基线改为 `chatAgentStrategy()` 通用循环图，Persistence/ChatMemory/压缩/事件/重试/skills 均用框架能力并经 provider 接 Room；新增 ADR-0004 例外、引用契约、NGA 限速、URL 策略、Markdown 渲染待定项和实施第 0 步。
- 2026-09-13：按设计稿与已确认需求拆成 12 张实现票（issues/01–12）：01 Koog 接入与 ADR、02 抽取读取层、03 设置与 Key、04 主题面板流式概览、05 论坛工具、06 skills 与快捷操作、07 持久化与历史、08 来源预览、09 开销与状态卡、10 其余入口、11 个人分析、12 联网搜索。

- 2026-09-13：票 01 已实现并完成离线构建、单测与 release 模拟器验证；具体接入、Android ABI 兼容修复及依赖/包体数据见 [实现报告](reports/01-impl.md) 与 [ADR-0006](../../docs/adr/0006-koog-agent-runtime.md)。本记录只更新框架接入进度，不表示其余一期能力已实现。
