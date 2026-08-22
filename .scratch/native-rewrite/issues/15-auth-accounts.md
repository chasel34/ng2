# 15 — 登录与多账号(M3)

**What to build:** WebView 登录:加载 `nuke.php?__lib=login&__act=account&login`,轮询 `CookieManager.getCookie()` 收割 `ngaPassportUid`/`ngaPassportCid`(cid 是 HttpOnly,只能原生拿——RN 版 500ms 轮询节奏照抄);挂载前 `CookieManager.clearAll()` 保证多账号登录隔离。**修 P1-03**:app 的 HTTP 请求走自管 CookieJar(票 06),WebView CookieManager 只在「登录收割」这一个点交互,账号状态归属单一可推理。多账号管理屏(增删/切换/当前标记)、抽屉账号头左右滑循环切号、登出;30 天有效期的 UI 口径照抄(客户端假设值,不强制)。游客态:不发任何认证信息,全功能只读可用。

**Blocked by:** 01, 06

**Status:** open

- [ ] 真实登录→收割 cid→已登录请求链路通(测试账号)
- [ ] 双账号切换后,收藏/通知等数据按 uid 隔离(P1-02 端到端验证)
- [ ] 登出/切号后 WebView 与 CookieJar 状态互不污染
