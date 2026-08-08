# 18 — 反封锁链前半段 + 错误页

**What to build:** 把 02 预留的策略链填上前半段(ADR-0002):格式参数交替(lite=xml ↔ __output=10 ↔ JSON)× 域名,仅解析错误/HTTP 状态错误触发重试,成功组合按接口 key 缓存优先复用,每次重试前重建 HTTP client;换账号重试(取下一个已登录账号 cookie 一次);read.php 可切 Windows Phone UA(策略开关)。全链失败时进设计稿的「加载失败」页:错误说明 + 重试 / 用网页版打开 / 重新登录 三动作 + 诊断信息落本地日志。

**Blocked by:** 09

**Status:** implemented

- [x] 策略链单测:模拟封禁响应时按序降级、成功组合被缓存、下次优先命中
- [x] 换账号重试只在多账号时启用且只试一次
- [ ] 错误页与设计稿 1:1,诊断信息(tid/page/ua)真实写入日志(真机验收)

## Comments

### 实现结构(2026-08-08)

链的最终顺序在 `src/store/nga-client.ts`:

1. `format-rotation`(`src/core/net/strategies/format-rotation.ts`)—— 格式 × 域名组合枚举
2. `switch-account`(`src/core/net/strategies/switch-account.ts`)—— 换下一个已登录账号 cookie,只试一次
3. 留给 19 的 Web 反解 / 网页兜底、留给 20 的帖子缓存:实现 `FetchStrategy` 塞进 `strategies` 数组即可,上层无感

三档共用 `strategies/attempt.ts` 的 `runAttempt()`(拼 URL、附认证、解码、失败分类),原 `direct` 策略退成它的单次包装。

### 与票面的两处偏差(需要确认)

**1. 轮换的格式档位是 JSON 家族三档,不含 XML。**

票面写的是 `lite=xml ↔ __output=10 ↔ JSON`(照抄 API 文档 §0.8 的 MNGA 做法)。但 MNGA 下游是 XML 解析器,本项目 `core/api` 全部按 `__output=8` 的 JSON 信封手工遍历字段——把 XML 响应喂进去,每一个解析器都得重写,且仓库里没有任何 XML 抓包样本可做 fixture。

所以 `DEFAULT_ROTATION_FORMATS` 是 `json`(`__output=8`)/ `jsonLite`(`lite=js`)/ `jsonVerbose`(`__output=11`):三者洗出来的信封同构,轮换零成本。XML 档在 `RESPONSE_FORMATS` 里已经齐了,19 票补上 XML→信封的转换后把它加进这个数组即可(`combo.ts` 的 `isRotatableFormat` 是唯一的闸门)。

**2. read.php 的 Windows Phone UA 开关默认是「开」,不是票面写的「关」。**

07 票起 `fetchTopicDetail` 就在请求上写死了 `userAgent: 'windowsPhone'`,并已过 M2 真机验收。18 票把它从写死改成开关(`src/store/net-settings.ts` 的 `readPhpWindowsPhoneUa`,core 侧是 `NgaFetcherOptions.getReadPhpUserAgent`),但默认值保持 07 的行为——默认改成关等于把一条真机验证过的路径换掉,风险不对等。22 票的设置页把这个开关接出来即可。

### 真机才能验的项

- 错误页与设计稿 1:1(尺寸/圆角/字号都按 `design/project/NGA客户端.dc.html` 的 `isError` 屏写,但没在设备上比过)
- 诊断信息真实写入 MMKV(`src/store/diagnostics.ts`,key `diagnostics.log.v1`),22 票的「导出诊断日志」消费它
- 真被封时的降级效果(单测只能模拟封禁响应)

### 顺带修掉的一个链上缺陷

`runStrategyChain` 原先无条件用最后一档的错误当最终错误。换账号那一档在单账号时返回 `unavailable`(「这一档不适用」),会把前面真正的「被封」错误盖掉,用户看到的成了「只有一个已登录账号,没有可换的」。现在 `unavailable` 不再覆盖更实质的错误——20 票的帖子缓存档(缓存里没有这条)也会走同一条路。
