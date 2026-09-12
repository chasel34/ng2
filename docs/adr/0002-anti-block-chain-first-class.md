# ADR-0002：反封锁链与读写分离

状态：沿用；2026-09-12 按当前 Kotlin 实现修订。以下替代 RN 阶段的 fetch/Expo 实现说明；保留编号供源码注释引用。

读请求默认依次尝试：格式与域名轮换 → 换下一个账号 → `read.php` 网页反解 → 主题页缓存。Web 反解可配置为 Disabled / Secondary（默认）/ Primary / Only；链失败后的「网页版打开」由用户在 UI 触发，不是自动策略。

**写请求只走 direct，固定发起账号，不轮换、不换号、不自动重放。** 即使失败提示涉及认证，也不能沿用旧 RN 文档中「写操作重试安全」的结论。服务端可能已执行而响应丢失。

装配入口：[NgaClient.kt](../../app/src/main/kotlin/com/chasel/ng2n/core/net/NgaClient.kt)。请求必须显式声明 `Operation`，见 [NgaRequest.kt](../../app/src/main/kotlin/com/chasel/ng2n/core/net/NgaRequest.kt)。不维护自建反向代理。

## 保留的规则与事故教训

1. **成功须通过响应形状校验。** JSON 可解析不等于业务结果有效；`NgaRequest.validate` 拒绝错误形状，避免把异常结果显示成空列表并缓存成功组合。默认使用带 `data/error` 的信封，顶层即数据的接口显式使用 `BARE`。
2. **组合缓存必须自愈。** 内存缓存 TTL 为 10 分钟，命中组合失败立即失效。key 为路径及 `__lib/__act`，因此 `thread.php` 的版块列表、搜索、收藏和用户主题共用记录；业务数据缓存另按账号隔离。
3. **重建传输必须真的更换连接资源。** 原生 `OkHttpTransportFactory.renew()` 新建 ConnectionPool 与 Dispatcher。旧 RN 版只创建 JS 闭包的做法已废弃。响应 body 一次读成字节，再交给 core 解码。
4. **认证默认使用 BOTH。** Cookie 与 `access_uid/access_token` 表单字段并用；设备层使用按请求凭证派生的自管 CookieJar，不能依赖镜像域名已有 WebView Cookie。默认系统 WebView UA 加 `X-User-Agent: Nga_Official`，`read.php` 的 Windows Phone UA 开关默认开启。
5. **成功和失败都留诊断。** 保留策略、组合、数据形状等排障信息；持久化前由 `data/diagnostics` 脱敏，凭证、正文和搜索词不原样入日志。
6. **认证错误与业务错误分开。** `Errors.kt` 中的认证错误可重试标记用于读链继续降级；不能据此重放写操作。
7. **重试看错误含义。** 错误来自服务端不代表一定不可重试；没有权限等业务错误也不能为了换路而无限重发。集中维护错误分类与对应测试。
8. **轮换档必须有不同的失效路径。** 默认顺序是 `__output=8` → `__output=11` → `lite=js`；`__output=11` 曾救回其他两档共同失败的坏字节响应。格式轮换默认最多 8 次尝试，域名外层、格式内层枚举。
9. **未支持的格式不进默认轮换。** 当前只解析 JSON 家族；XML 常量存在但尚无 XML 解析器。改变格式表应先补真实样本及解析测试。
10. **列表同时兼容对象和数组。** NGA 可返回数字字符串键对象，也可返回 JSON 数组，统一经 `orderedEntries` 遍历，不能将另一种形状静默当成空列表。

附件域名优先来自 `read.php` 的 `_ATTACH_BASE_VIEW`，静态地址仅作兜底。编码、端点和降级细节见 [API 文档](../API文档.md)，历史排查保存在 `.scratch/perf-2026-08/`，原生读写分链见 `.scratch/native-rewrite/issues/06-anti-block-chain.md`。
