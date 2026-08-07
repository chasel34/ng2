# 09 — WebView 登录 + 多账号

**What to build:** 抽屉点「登录账号」进 WebView 登录页(顶部 URL 提示条与底部隐私说明照设计稿),登录成功自动抓取 cookie 返回;账号安全存储,支持多账号;账号管理页(当前账号高亮/切换/退出/添加,过期天数展示);抽屉头部左右滑动切换当前账号;全局请求自动携带当前账号凭证,登出后回游客态。

**Blocked by:** 02, 04

**Status:** ready-for-agent

- [ ] WebView 轮询/回调两种时机都能抓到 uid+cid,用户名 GBK 双重 URLDecode 正确
- [ ] 凭证存 SecureStore,切换账号后下一个请求即用新 cookie
- [ ] 账号管理页与设计稿 1:1;游客态各页降级不崩

## Comments
