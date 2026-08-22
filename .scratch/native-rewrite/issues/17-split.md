# 票 17 拆分(主控 2026-08-22)

票 17 体量太大,拆三个子代理并行。**每个子代理只做自己那份**,共用规则:

- 基线 `android-native`(票 16 已合并:`ui/nav/Keys.kt` 全部键已定;`ui/home/HomeEntries.kt` 里是 `PlaceholderScreen` 占位;`ui/AppDeps.kt` 取仓库;`ui/common/` 有 SnackbarHost/LoginPrompt/PlaceholderScreen;`ui/drawer/` 抽屉;`data/` 各仓库票 14 已落地)。
- 导航接线:新建 `ui/<feature>/<Feature>Entries.kt`,签名 `fun EntryProviderScope<NavKey>.xxxEntries(nav: Navigator, …)`;在 `ui/Ng2nApp.kt` 的 `entryProvider { … }` 里**只加一行**调用;在 `HomeEntries.kt` 里**只删自己那几行占位**,别动别的行。
- `ui/Ng2nApp.kt`、`HomeEntries.kt`、`libs.versions.toml` 之外不要碰别人的文件;需要改公共件(`ui/common`、`data/*` 仓库接口)→ 改动最小,并在 Comments 记下。
- 票据回写:三份都写进 `issues/17-其余屏幕.md` 的 `## Comments`,各自加 `### 17a/17b/17c` 小节;**Status 不改**(主控收齐后改)。验收项由主控勾。
- 模拟器 `emulator-5554`:**不要设 `settings put global http_proxy`**(模拟器本来直连,设了就全断);票 13 正在用,**你被主控明确通知前不要 install/launch**,先靠编译+单测;主控会给时间片。
- 其余照 `agent-brief.md`。

## 17a — 列表类屏(搜索 / 收藏 / 历史 / 缓存管理 / 通知)
搜索(主题/版块/用户三 tab,主题可限版块可搜正文,每 tab 独立历史 20 条;**游客禁用版块搜索**,提示登录);收藏(多夹、20 夹上限、设默认/重命名/删除、tid→夹反向索引本地维护,**P1-02 按 uid 隔离**);历史(200 条+进度);缓存管理(逐帖查看/删除);通知(前台 60s 轮询、按类型分组、本地已读、清空、抽屉未读徽标接线)。键:SearchKey / FavoritesKey / FavoriteFoldersKey / HistoryKey / CachesKey / NotificationsKey。

## 17b — 屏蔽 / 用户 / 桩
屏蔽规则三 tab(本地规则:用户/关键词(正则,**修 P3-05**:编译失败拒绝、长度上限、匹配超时/输入截断)/分类;官方屏蔽词云端全表覆盖写);用户资料(本人可改签名)+ 我的主题/我的回复(UserKey / UserPostsKey);toast 桩(`showNotAvailable` 等价物,桩清单照 research/inventory.md §2 第 50 行,落在 `ui/common/Stubs.kt`,各处入口接上);楼层/抽屉里指向这些屏的入口接线。键:FiltersKey / UserKey / UserPostsKey。

## 17c — 设置树 / 关于 / web 兜底 / 深链
设置树全部(通用/阅读/通知/内容与存储/高级 五分组,含域名 5 选 1、夜间/跟随系统、主题风格 ink/plain/近黑、左手模式、纯色背景、自动下一页、仅 Wi-Fi 图片、图片策略、签名档、手势返回开关、常亮、字号头像滑块屏、实验室(web 兜底四档 + WP UA 开关 + 组合表分享 + 诊断日志导出)、恢复默认;**每项改动立即生效**,域名切换下个请求生效等语义照抄 RN);关于(抽屉入口;DevMenu 长按入口保留);`/web` 网页兜底 WebView(WebKey);深链接收(`ng2n://` scheme;NGA 域名 intent-filter **并行期不注册**;冷启深链用 TaskStackBuilder 垫首页防死返回;`core/local/DeepLink.kt` 已有解析)。键:SettingsKey / AboutKey / WebKey。
