# 02 — core/net 基建

**What to build:** core 层可以向 NGA 发出任一读接口请求并拿回干净的结构化数据:自动带 UA 身份头与公共参数、空值参数剔除、GBK/GB18030 回落解码、响应清洗(剥前缀/截错误尾/修非法数字/删坏字段/整数 key 加引号/控制字符转义)、错误模型区分真错误与假错误白名单。fetcher 从第一天就是策略链形态(本票仅实现单策略),为反封锁链(ADR-0002)留好槽位。纪律:禁止 clone response。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 用真实抓包样本作 fixture 的单测通过:清洗 8 步、GBK 解码、两种认证方式、假错误白名单(完毕/没找到/没有符合条件的结果/今天已经签到/找不到用户)
- [x] HTTP 非 2xx 时仍先解析 body 再报错
- [x] `.env.local` 的测试 cookie 能真实请求通知接口拿到合法 data(集成冒烟,可跳过)

## Comments

### 实现摘要(2026-08-07)

全部落在 `src/core/net/**`,纯 TS、零 RN 依赖,HTTP 传输层由外部注入。

- `fetcher.ts` — 策略链框架(ADR-0002)。`runStrategyChain` 按序跑 `FetchStrategy[]`:先成功者返回;失败且 `retryable`(网络/HTTP 状态/解析失败 ≈ 被封)落到下一档;失败且不可重试(服务端语义错误、调用方取消)立刻抛出。本票只塞了 `direct` 一档,后续「格式参数交替 / 换账号 / Web 反解 / 帖子缓存 / 网页兜底」按同一接口追加即可,调用方无感。
- `strategies/direct.ts` — 直连策略。默认 POST、业务参数进 query、认证进 body/头。
- `encoding/gb18030.ts` + `gb18030-index.ts` — 纯 JS GB18030 编解码。Hermes 的 TextDecoder 只认 utf-8,所以自带 WHATWG 索引表(23940 项双字节表 + 210 段四字节游程,由 Node 的 TextDecoder 生成)。单测逐个对拍全部双字节序列、全部 BMP 四字节 pointer 和 2000 条随机字节流。附带 `gbkEncodeURIComponent`(`thread.php` 的 `author`、`forum.php` 的 `key` 要用)。
- `encoding/decode-body.ts` — 响应解码。优先信 `Content-Type` 的 charset;未声明时先试 UTF-8,出现替换字符再比较两种解码谁的 U+FFFD 少(实测 `thread.php` 就是不声明 charset 的 GBK)。
- `sanitize.ts` — API 文档 §0.6 全部步骤。整数 key 加引号与控制字符转义合成一次带字符串状态的扫描,不用上游那条裸正则,免得把正文里的 `{12:` 改坏。
- `errors.ts` / `envelope.ts` — 错误模型与信封解析。真错误抛 `kind:'server'`(不重试),假错误白名单命中时当成功返回并保留 `fakeError` 供调用方判空。HTTP 非 2xx 一律先解析 body,body 解不出东西才用状态码报错。
- `auth.ts` — 两种等价认证方式(form 的 `access_uid`/`access_token`、Cookie 头的 `ngaPassportUid`/`ngaPassportCid`)。form 方式配 GET 会明确报错,不静默降级成游客。
- `query.ts` — 公共参数拼装与空值参数剔除(`null`/`undefined`/空串/`false` 一律丢);`gbk()` 标记逐参数切字符集。请求里出现 GBK 参数时自动撤掉 `__inchst=UTF8`、表单体自动声明 `charset=GBK`。
- `transport.ts` — **fetcher 禁止 clone response**(expo/expo#47762),只调一次 `arrayBuffer()`,注释里注明。

fixture 在 `src/core/net/__fixtures__/`,是 2026-08-07 用 `.env.local` 测试账号 curl 到的**原始响应字节**(GBK),抓包账号 uid 已脱敏成 10000001,cookie/cid 不在响应体里。

测试:`pnpm typecheck` 通过;core/net 104 个用例通过 + 4 个联网冒烟默认跳过。联网冒烟 `NGA_INTEGRATION=1 pnpm test` 实跑通过——通知接口拿到合法 data、form 与 cookie 两种认证都通、`thread.php?fid=650` 的 GBK 中文解码正确、GBK 编码的 `author` 参数能筛到人。

收尾跑了一轮 code-review(标准 + spec 双轴),据此修掉:术语漂移(帖子→主题)、`isRecord` 重复、`buildFormBody` 纯转发、`JSON_FORMATS` 与 `RESPONSE_FORMATS` 双维护、`gbkEncodeURIComponent` 的表外字符实体没按 §0.5 的 UTF-16 码元拆代理对、非 2xx 时只要解析失败就报 HTTP 错(应当 body 为空才报)、清洗第 4 步的结尾边界过窄、生成表头注释指向了不存在的测试文件。

### 遗留问题

1. **ADR-0002 说「一律一次性 `.text()` 读取」,实现里用的是一次性 `arrayBuffer()`**:响应可能是 GBK,交给运行时按 UTF-8 转字符串就没救了。禁止 clone/tee 的纪律本身完全遵守(只读一次、之后不碰 response.body)。
2. **重试判据比 MNGA 宽一档**:MNGA 只在解析失败/HTTP 状态错误时重试,这里连 `network` 也标可重试——本项目链末端是帖子缓存与网页兜底,断网时正该落到缓存那一档。调用方主动取消的请求显式标成不可重试。ticket 18 接链时请确认这个取舍仍成立。
3. XML(`lite=xml` / `__output=10`)与网页 HTML 两条解析路线没做:`RESPONSE_FORMATS` 里已有对应的格式参数与 `kind`,但 `direct` 遇到非 JSON 格式会返回 `kind:'unavailable'` 且标记可重试,留给 ticket 18/19 加解析策略。
4. 「成功组合按 key 缓存」没有预留字段(先前的 `NgaRequest.cacheKey` 无消费方,已删),ticket 18 接反封锁链时随消费方一起加。
5. 清洗第 5 步(删坏 `alterinfo`)的字符类比上游宽:Java 的 `\w` 不匹配中文,上游那条对中文站等于没生效;这里保留「方括号 + 结尾空白」的特征以免误删正常 alterinfo。真实抓包里没有出现该字段,没能拿真样本验证。
6. `read.php` 强制切 Windows Phone UA 只提供了机制(`request.userAgent: 'windowsPhone'`),没在 net 层写死策略——该由 core/api 的 read 服务决定。
7. 附件域名 `_ATTACH_BASE_VIEW` 已在 fixture 里确认存在(`img.nga.cn/attachments`),但提取逻辑属于 core/api,不在本票。
