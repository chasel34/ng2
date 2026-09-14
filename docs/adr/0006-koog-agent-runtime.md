# ADR-0006：Koog 本机 agent 与独立模型 HTTP

状态：草稿；Koog 接入已落地，后续能力沿本适配边界扩展。

## 决策与理由

AI 助手在 Android 本机运行 Koog 的 agent 执行框架，模型推理由用户配置的 DeepSeek 服务提供。业务范围、来源、预算与会话规则属于 App；框架类型集中在 `data/ai`，不进入 `core` 或界面模型。`KoogAgentFactory` 使用上游 `chatAgentStrategy()`，允许注入执行器、完整配置、工具注册表与 feature 安装回调，不另写工具循环或固定分析流程。

这是对 [ADR-0004](0004-native-stack-compose-bare-okhttp.md) 的局部例外：接受 Koog 的 Ktor、Jackson、反射等传递依赖，以及 DeepSeek、skills、文件工具的 beta 模块，以复用已有的运行、检查点、历史、事件、压缩和技能加载能力。论坛网络继续直接使用既有 OkHttp 与 READ/WRITE 规则。

直接依赖只有以下模块，版本统一在[版本目录](../../gradle/libs.versions.toml)：

| 模块 | 用途 |
| --- | --- |
| `agents-core` | Agent、`chatAgentStrategy()`、历史压缩节点；策略不需要额外聚合包 |
| `agents-features-memory` | ChatMemory 及历史 provider |
| `agents-features-snapshot` | Persistence 及检查点 provider |
| `agents-features-event-handler` | 模型、工具、运行事件 |
| `skills`（beta） | 技能发现与目录 prompt |
| `agents-ext`（beta） | 内置技能需要的框架文件读取工具 |
| `prompt-executor-deepseek-client`（beta） | DeepSeek 协议适配 |
| `http-client-okhttp` | 显式 OkHttp factory |

不使用 `koog-agents`、`agents`、`agents-features` 或全服务商执行器等聚合依赖。`skills` 与 `agents-ext` 必须传递引入 `rag-base` 的文件系统接口；这不代表启用检索、嵌入、向量存储或长期记忆。核心也会传递引入 OpenAI 客户端、Ktor 客户端和服务器基础模块；不盲目排除它们，不注册其他模型服务商。OkHttp BOM 对齐 SSE 与现有 OkHttp 版本，Kotlin 反射库对齐项目编译器版本。

官方版本表未单独列出 skills；其发布源码明确标记 beta，实际 Maven 解析也确认为 `1.2.0-beta`。升级时同时复核稳定模块与对应 beta 的兼容性、传递依赖和 release 测试。

## HTTP 与运行边界

`DeepSeekClientFactory` 使用 `BudgetHttpFactory` 为 Koog 注入独立 OkHttp 客户端，关闭透明重试、重定向与可能输出正文的默认 HTTP 日志，不继承 NGA 的 CookieJar、连接池或拦截器。公开入口固定官方 endpoint，测试内部入口允许本机 MockWebServer。凭证来自用户的本机加密配置，启动不自动请求模型，空 Key 在创建客户端之前拒绝。

联网搜索与网页读取使用第三个独立 OkHttp 客户端（`WebReader.shared`），同样不继承 NGA 的 CookieJar、连接池或拦截器，并关闭自动重定向。URL 策略在每一跳之前执行：只允许 http(s)、拒绝携带账号信息的地址、拒绝 localhost 与保留域名后缀、拒绝私网与保留网段的字面地址；自定义 `Dns` 再过滤解析结果中的私网地址，全部为私网时拒绝解析。正文下载有字节上限，站外请求最多两个并发、失败重试一次。搜索固定 DuckDuckGo Lite 的表单 POST：实测同一查询的 GET 稳定返回 202 验证页，POST 返回结果页。结果分为有结果、零结果、`challenge`（人机验证）与 `request_failed`，验证页不当作零结果，也不切换到其他引擎或收费服务；验证页的识别保留 `bots use duckduckgo`、`anomaly.js`、`challenge-form` 等原有字样，并补充实测响应中的 `anomaly-modal` 与 `/assets/anomaly`；结构未知的页面仍按验证页兜底。单次最多编号 10 条结果，超出条数只作说明，不分配 sourceId。正文读取同时受字节上限与字数上限约束，触达任一上限即在工具结果与来源状态上标记未读完，不允许按已读全文引用。个人分析入口不注册这两个工具。

`KoogAgentFactory.run` 负责关闭本次 agent；独立创建 agent 时由调用方关闭，执行器始终由调用方持有并关闭。`createExecutor` 只注册 DeepSeek，不配置备用服务商。取消沿 Koog 协程传播。共享预算经 EventHandler 发送前预留与 usage 事件结算，重试仅由 RetryingLLMClient 执行；KnownResultClient 防止未知空流重放。已确认未发送的失败释放预留，进程重启后无法确认发送状态的预留保守保留。流式恢复通过 Persistence/ChatMemory 接入。

需要注意上游 `chatAgentStrategy()` 的实际行为：首轮若直接输出纯文本，会要求模型调用工具；工具结果回传后的文本才进入结束节点。`KoogAgentFactory` 保留上游策略，离线测试覆盖其正常工具路径；主题面板的流式图允许首轮直接回答，见下节。

## 主题面板的流式策略与引用

主题面板使用 `TopicAgentRuntime`，以预置 chat 图的「请求 → 执行工具 → 回传结果 → 请求」结构为基线。请求节点改用 `llm.writeSession { requestLLMStreaming() }`；只有收齐完整帧并正常结束才转换为框架 `Message.Assistant` 和执行工具。纯文本允许直接结束，删除上游强制首轮调用工具的反馈分支，避免空工具注册表下反复调用模型。此扩展仍是 Koog 图，不自行实现 agent 循环。工具注册表包含论坛读取工具与内置技能文件工具。

`EventHandler.onLLMStreamingStarting` 与 `onLLMStreamingFrameReceived` 提供思考与逐步输出进度；读取范围由 App 的实际仓库读取结果更新。运行取消传播至 Koog HTTP 和图片 Call，UI 用运行代次拒绝迟到更新。成功回答保留原始 assistant 消息及 reasoning 字段用于当前对话续聊，停止/截断回答保留为界面草稿，不伪造为完成的协议消息。UI 只消费 App 的 `AiStreamUpdate`，不直接处理 Koog 类型。

来源标记契约固定为 `[[sN]]`，其中 N 是从 1 开始的十进制整数，不带空格，例如 `[[s1]]`。sourceId 由 App 在本会话实际读取的去重来源中分配，映射 tid/pid、楼层、页码、作者与时间；模型只能引用给定 ID。已闭合但未知或非法的双括号标记丢弃；流式尾部的 `[`、`[[` 或未闭合标记暂不渲染，补齐后一次显示标签，停止后仍不暴露半截标记。普通 Markdown 链接保持有效。标记优先于 Markdown 解析，代码块内也不能绕过来源校验。

回答选择自研轻量的原生 Compose Markdown 渲染，不新增依赖、不使用 WebView。支持段落、标题、列表、引用、粗体、斜体、行内代码、围栏代码、HTTP(S) 链接与管道表格。围栏内保留原始行及缩进，不进行标题、引用、列表或表格转换；行内代码不递归解析强调和链接，两者仍遵守来源标记优先校验规则。重新解析当前已收到的文本，使用 `InlineTextContent` 插入来源标签，未知 ID 不生成可点击内容。HTML 作为文字处理，不执行远端图片、脚本或自定义 URI。论坛正文继续沿用原来的 BBCode AST 渲染路径。来源标签和回答页脚列表先打开原文预览，只使用引用坐标及楼层／贴条定位信息经当前账号 READ 重新读取，禁止主题缓存回退和 Room 工作正文读取；确认跳转后收起面板、跳楼并高亮。图片查看叠加在预览之上，返回时保留聊天位置。

站外来源与论坛来源共用 `[[sN]]` 契约和同一套 ID 分配，`AiSource.web` 记录网址、标题与是否已读正文。标签用地球图标显示域名，点击打开站外预览而不是论坛 READ 流程；未读正文的搜索摘要与未读完的正文在资料与界面中都标注为不能作为已核实证据。「外部搜索暂时不可用」通知条只在本轮完全没有取得外部资料时出现，部分失败由工具行自行标记，避免通知条与同屏的已读网页标签互相矛盾。事实核查的说法卡片是渲染约定而非新协议：以 `:::claim 状态` 开头、`:::` 结束的块被渲染为卡片，状态只接受支持、部分支持、无法核实，未知状态与围栏内的同名文本按字面保留。

初始图片先有界下载和采样，然后使用 `AttachmentSource.Image(AttachmentContent.Binary.Base64(...), "jpeg")` 传给 Koog；不能把 data URI 传给 `image(String)`，该重载只接受文件 URL。自定义 `deepseek-flash` 模型增加 `Vision.Image` 能力，显式开启思考；输出上限与 `maxAgentIterations` 取自当前单次额度档位的运行上限，不写死常数。收敛靠工具执行次数上限与连续重复读取阈值，循环上限只作兜底，必须容得下技能读取、多页论坛读取、读图、联网核查与收尾。`AIAgentMaxNumberOfIterationsReachedException` 在最后一个节点执行之后才抛出：此时若已有不含工具调用的完整回答，本轮按正常完成返回；否则转成 `AiRunLimitReached`，界面按「达到限制」保留文字与来源并允许继续。模型仅使用 RetryingLLMClient 在共享额度内执行允许的自动重试，图片、原始主题读取与模型客户端分别取消，论坛 Cookie 不进入模型请求。

## R8 与验证

保留两个 App 适配工厂的公开入口，使尚未接入界面的框架运行路径也实际进入 release 的可达分析。没有整包 `-keep ai.koog.**`、关闭压缩或用全局 `-dontwarn` 隐藏缺类。已有 kotlinx.serialization 规则继续保留 serializer 反射入口。Ktor 的桌面调试器探测引用 Android 没有的两个 JMX 类；核对 3.3.3 字节码确认该探测捕获 `Throwable` 并返回 false，因此仅对 `ManagementFactory` 与 `RuntimeMXBean` 忽略缺类警告，不忽略 Koog 运行必需类。

实测发现 Koog 1.2.0 的 JVM-only `http-client-okhttp` 引用 `ai.koog.utils.io.Coroutines_jvmKt.getSuitableForIO`，但 Android utils 的实际类名为 `Coroutines_androidKt`，HTTP 首次调用抛出 `NoClassDefFoundError`。App 在 `ai/koog/utils/io/CoroutinesJvmBridge.kt` 提供相同 JVM 签名，直接委托 Android 的 `SuitableForIO`。这是一个二进制兼容桥，不替换 HTTP 实现、不复制协程调度或重试逻辑。此文件必须使用上游包名；其余适配仍在 `data/ai`。上游修复后应删除此桥，并复跑两类测试；不能同时打包有同名类的 JVM utils。

同一组 Kotlin 测试用于 JVM 与 release 设备测试。release 冒烟构建临时将测试主体放入目标 APK，与生产实现一起经过 R8，仅保留两个测试入口；跨 APK runner 只使用平台 API。普通出包不包含测试主体和它的测试依赖。验证内容：假执行器验证模型 → 工具 → 调用 ID 和结果回传 → 最终回答；真实 Koog DeepSeek 客户端向本机 MockWebServer 发请求，验证序列化、响应解码、无论坛 Cookie、无论坛拦截器、与论坛不同连接，以及不重放响应的 Set-Cookie。设备验证入口见[测试说明](../testing.md)。

测量与完整依赖增量见 [接入测量](0006-koog-integration-measurements.md)。这些数据是当前接入路径的 APK 和构建结果，不是性能结论，也不是完整 AI 功能的最终包体；性能仍需真机 release 测量。

## 参考

- [Koog 模块版本](https://docs.koog.ai/module-versioning/)
- [1.2.0 skills 模块声明](https://github.com/JetBrains/koog/blob/1.2.0/skills/build.gradle.kts)
- [1.2.0 通用策略源码](https://github.com/JetBrains/koog/blob/1.2.0/agents/agents-core/src/commonMain/kotlin/ai/koog/agents/ext/agent/AIAgentStrategies.kt)
- [1.2.0 OkHttp factory 源码](https://github.com/JetBrains/koog/blob/1.2.0/http-client/http-client-okhttp/src/main/kotlin/ai/koog/http/client/okhttp/OkHttpKoogHttpClient.kt)

贴条引用的定位信息随会话元数据持久化：有 pid 时使用 pid，无 pid 时使用稳定字段与正文指纹，不依赖易变化的贴条序号。不保留原文快照。缺少定位信息的旧引用只展示明确标注的父楼及当前贴条范围，不能声称已精确找到引用内容。

### 模型 HTTP 预算适配

`BudgetHttpFactory` 保留 Koog 的客户端协议与响应解码，只观察完整 usage、状态码和 Retry-After，并通过运行预算登记每次尝试。为关闭 OkHttp 的透明重试及重定向、关闭可能带正文的默认 HTTP 错误日志，使用固定 Koog 1.2.0 artifact 的公开 JVM 构造签名注入独立 OkHttpClient。该构造函数在 Kotlin 元数据中是 internal，调用处以局部可见性抑制接入；升级 Koog 时须重新验证该 ABI、HTTP 隔离与 release/R8。

Koog 1.2.0 的 OkHttp 客户端按请求体类型选择媒体类型，`String` 请求体默认 `text/plain`，而 OpenAI 基类（DeepSeek 继承）正是把已序列化的 JSON 以 `String` 传入且不带 `Content-Type`，服务端因此返回 415。非流式 `post` 仍走上游实现，由本机在调用头中补上 `application/json`，调用方已声明时不覆盖。

流式路径不使用上游实现，`sse` 与 `lines` 在本机用同一个 OkHttpClient 直接发请求并自行解析 SSE。上游 `sse` 把 `EventSourceListener` 的事件用 `trySend` 推进 `callbackFlow` 的有界缓冲：缓冲满时事件被静默丢弃，消费端（逐帧落库的界面）稍慢于服务端就会出现正文前段完整、尾部变成碎片并截断，结束帧本身也可能丢失，运行随即以「回答生成中断」结束。上游 `onFailure` 又在 OkHttp Dispatcher 线程上读取 `response.body.string()`，而 okhttp 5 对 `EventSourceListener` 的响应体做了 strip，取消流时读取即抛且无人捕获，进程被杀。本机实现按 SSE 规范按行累积 `data` 字段、空行分发事件，事件以挂起方式下发，背压回到 socket 读取；失败只按状态码分类，不读取响应体；取消通过 `Call.cancel()` 结束读取并按取消处理。okhttp 版本不因此回退。离线 MockWebServer 回归测试覆盖任意字节边界的分块（含多字节 UTF-8 跨块）、慢消费端下的完整文本与结束帧，以及流中途取消不产生未捕获异常。上游修正后应改回委托实现。

`KnownResultClient` 将未完成空流异常改成不可自动重试的结果不明错误；实际重试仍只由 RetryingLLMClient 执行，应用不实现重试循环。每次发送在同一分析账本内原子预留，完整 usage 经 EventHandler 结算；重试尝试复用同一运行预算回调，不把 HTTP 层尝试隐藏成一次记账。429 以外的 4xx 是服务端明确拒绝且无 usage，HTTP 层据此标记该次运行，预留按 `rejected` 释放而不是记为结果不明。
