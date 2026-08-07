# 反封锁链是网络层的一等公民,第一天就进架构

NGA 会针对性封禁第三方客户端(表现为 XML/JSON 接口解析失败)。core 层的 fetcher 从 M1 起就是策略链而非裸 fetch:格式参数交替(`lite=xml` ↔ `__output=10` ↔ JSON,成功组合按 key 缓存)→ 换账号重试(取下一个已登录账号 cookie)→ Web 反解(read.php 专用,从网页内联 JS 反解)→ 帖子缓存 → 网页兜底页。UA 策略:默认 `X-User-Agent: Nga_Official` 辅助头 + 系统 WebView UA(NGA-CLIENT v4 验证的做法),`read.php` 可切 Windows Phone UA。附件图片域名从 `read.php` 响应的 `_ATTACH_BASE_VIEW` 动态获取,禁止硬编码。

不做自建反向代理(需要长期维护服务器,个人使用不值)。

附带纪律(Expo SDK 57 Android `expo/fetch` 乱序 bug,expo/expo#47762):fetcher 禁止 clone/tee response,一律一次性 `.text()` 读取。
