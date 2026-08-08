# 09 — WebView 登录 + 多账号

**What to build:** 抽屉点「登录账号」进 WebView 登录页(顶部 URL 提示条与底部隐私说明照设计稿),登录成功自动抓取 cookie 返回;账号安全存储,支持多账号;账号管理页(当前账号高亮/切换/退出/添加,过期天数展示);抽屉头部左右滑动切换当前账号;全局请求自动携带当前账号凭证,登出后回游客态。

**Blocked by:** 02, 04

**Status:** resolved

- [x] WebView 轮询/回调两种时机都能抓到 uid+cid,用户名 GBK 双重 URLDecode 正确
- [x] 凭证存 SecureStore,切换账号后下一个请求即用新 cookie
- [x] 账号管理页与设计稿 1:1;游客态各页降级不崩

## Comments

实现要点(agent 实现,主控代为收尾提交):

- **core 层**(`src/core/account/`,纯 TS + vitest):
  - `login-cookies.ts`:从 `document.cookie` 文本解析 `ngaPassportUid`/`ngaPassportCid`/`ngaPassportUrlencodedUname`
  - `username.ts`:用户名 GBK 双重 URLDecode(复用 `core/net/encoding` 的 GB18030 解码)
  - `accounts.ts`:多账号纯函数模型(增/切/删、同 uid 重登就地刷新、cookie 30 天过期天数推算与文案)
- **store 层**:`src/store/accounts.ts` Zustand + expo-secure-store 落盘;`nga-client.ts` 的 fetcher 改为按请求现取凭证(`getCredentials: currentCredentials`),切号/登出后下一个请求即生效,游客态返回 null 不带凭证
- **UI**:`src/app/login.tsx` WebView 登录页(顶部 URL 提示条 + 底部隐私说明;injectedJavaScript 500ms 轮询 + onMessage 回调两种时机抓 cookie);`src/app/accounts.tsx` 账号管理页(当前账号高亮/切换/退出/添加/过期天数);`src/ui/app-drawer.tsx` 抽屉头部左右滑动切换当前账号(复用 horizontal-drag)
- 游客态:抽屉/首页降级为「登录账号」入口,不崩
