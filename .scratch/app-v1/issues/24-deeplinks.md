# 24 — 深链 + URL 读取

**What to build:** 在微信/浏览器等处点 NGA 四个官方域名的主题/版块链接,系统弹出用本 app 打开,直达对应详情页(含 tid/page/pid 参数)或列表页(fid/stid);自定义 scheme 同样可跳;抽屉「由 URL 读取」对话框粘贴链接解析跳转,不合法链接有提示。

**Blocked by:** 05, 07

**Status:** ready-for-agent

- [ ] read.php(tid/page/pid/fav)与 thread.php(fid/stid)参数解析单测
- [ ] 真机验证外部点击链接进入正确页面
- [ ] 对话框与设计稿 1:1

## Comments
