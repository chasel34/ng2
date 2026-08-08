# 10 — 版块收藏(云端)

**What to build:** 首页「我的收藏」分组展示云端收藏版块(forum_favor2);主题列表页顶栏星标即时收藏/取消并 toast(带撤销);抽屉「添加版面 ID」对话框输入 fid/stid 直接收藏并可打开;「清空我的收藏」带确认。

**Blocked by:** 05, 09

**Status:** resolved

- [x] 收藏/取消后首页收藏 tab 与服务端一致(重进 app 仍在)
- [x] fid 与 stid 两种输入都能正确识别与打开
- [x] 未登录时入口引导登录而非报错

## Comments

- **接口**(`src/core/api/board-favor.ts`,2026-08-08 真实抓包进 `__fixtures__`):端点只有一个 `nuke.php?__lib=forum_favor2&__act=forum_favor`,`action=get|add|del`。列表在 `data["0"]`,空收藏时 `data` 是 `{}`(连 `"0"` 键都没有)。
- **合集也走 `fid` 参数**:实测把 stid 当 fid 传,服务端自己识别,回来的条目带的是 `stid`。所以「添加版面 ID」不需要让用户说明输的是哪种 id——先按 fid 发,再从重拉的列表里认领(`useAddBoardFavoriteById`),`kind` 与版块名都以服务端为准,「打开」按钮因此能用正确的 kind 进列表页。
- **幂等**:`del` 天然幂等(删未收藏的照样「操作成功」);`add` 重复收藏报「你已经收藏了这个版面」,在 core 层吞掉当成功——乐观切换 + 撤销来回点时不怕竞态。
- **清空没有批量接口**,只能先 get 再逐个 del,**串行**(收藏一般十来个,不值得为它冒被风控的险,ADR-0002)。返回删掉的列表给「撤销」逐个收回,反序 add 正好还原服务端「新收藏在前」的顺序。
- **store**(`src/store/board-favor.ts`):缓存 key 按 uid 分桶(收藏是账号级数据,切号不能拿别人的充数),写操作全部乐观更新 + 失败回滚 + `onSettled` invalidate 对齐服务端;staleTime 5min,否则每开一个版块都要为星标多打一次接口。
- **首页**:「我的收藏」是设计稿的第一个 tab,这里包成一个合成分类(id `favorites/mine`)插在服务端分类前,组圆章按设计稿写「收」而不是首字「我」。默认选中它,但**游客直接落到第一个服务端分类**——游客那栏只有登录引导,拿它当首屏等于把首页开成空的。分类树没回来时不插合成 tab,否则首页的「拉不下来」错误屏再也走不到。
- **未登录**:统一走 `src/ui/login-prompt.ts` 的 `showLoginPrompt`(snack 条 + 「去登录」跳 `/login`),不把服务端那句「你必须先登录论坛」摔给用户;首页收藏 tab 内则是一整块带「去登录」按钮的占位。11/13/14 票的云端入口可以直接复用。
- **Snackbar**(`src/ui/snackbar.tsx`):设计稿的 snack 条(深底浅字 + 薄荷绿动作),挂在**根布局**而不是页面里——列表页收藏完退回首页,那条「撤销」还得活着能点。与 `showToast`(系统 ToastAndroid)分工:要带动作或要跟设计稿 1:1 的走 snack,纯气泡提示维持原状。色值进 `tokens.ts` 的 `snackbarColors`(不进 ColorTokens:设计稿里它也不是 `:root` 声明的 token)。
- **星标已收藏态用 accent 染色**而不是设计稿的实心:图标字体是静态 Material Icons Outlined,没有设计稿那根 `FILL` 可变轴(见 `src/ui/icon.tsx` 的说明)。为此 `TopBarButton` 加了可选 `color`。
- **对话框**:「添加版面 ID」复用 `InputDialog`;「清空我的收藏」新增 `ConfirmDialog`(与 InputDialog 同一形状,输入行换成正文,危险操作确定钮走 danger)。两个都由**宿主页面**弹(设计稿:先关抽屉再弹框),抽屉只加了两个可选回调 props,没接的宿主维持「本版本未开放」。
- **输入校验**:`parseBoardIdInput` 只收十进制整数(fid 可以是负数,如网事杂谈 -7),0 不算有效 id。设计稿 hint 里的示例 `-7bcf72` 不是合法 id(mock 随手写的),换成「填 fid 或合集 stid,例如 459、-7」。
