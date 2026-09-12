package com.chasel.ng2n.ui.common

/**
 * 未实现功能的统一出口 —— 直译 RN 侧 `src/ui/toast.ts` 的 `showNotAvailable()`。
 *
 * spec §一.2「只读向」把写操作里的一整块排除在 v1 之外(`research/inventory.md` §2
 * 「toast 桩(不实现,入口保留)」那一行),但**入口一律保留**:点了要给一句话,
 * 不能静默无反应 —— 静默的按钮会被当成 bug 反复点。
 *
 * ## 与 RN 版的一处有意偏离
 *
 * RN 版用的是 `ToastAndroid`(系统气泡);这里落到 [Snackbars] 上。两个原因:
 *
 * 1. `Toast` 要 `Context`,而桩入口散在各种叶子组件里(楼层卡的四个动作、菜单条目、
 *    顶栏钮),为一句提示逐层传 `Context` 或到处 `LocalContext.current` 不值当;
 *    [Snackbars] 是进程级单例,任何地方一行就能调。
 * 2. Android 12 起后台应用的 `Toast` 会被系统吞掉/改样式,而 snackbar 是我们自己画的,
 *    浅深两套配色下都与设计稿对得上。
 *
 * **文案一字不改**([NOT_AVAILABLE_MESSAGE] =「本版本未开放」,与 RN 版同一个常量),
 * 票 18 的功能验收按这句话逐个点。想要系统气泡的场合仍可用 [rememberToaster]。
 *
 * ## 桩清单(`research/inventory.md` §2 第 50 行,逐条照抄)
 *
 * | 桩 | 入口在哪 | 归哪张票接 |
 * |---|---|---|
 * | 发帖 | 版块页 FAB | 票 16(已接) |
 * | 回帖 | 楼层卡「回复」/ 详情页发帖钮 | 票 13 |
 * | 编辑 | 楼层菜单 | 票 13 |
 * | 短消息 | 抽屉「短消息」/ 用户资料页顶栏 | 票 16(抽屉,已接)· 票 17b(资料页) |
 * | 发贴条 | 楼层菜单(**贴条展示照常**,只有「发」是桩) | 票 13 |
 * | 举报 | 楼层菜单 / 详情页菜单 | 票 13 |
 * | 投票操作 | 投票卡的投票钮(**结果只读渲染照常**) | 票 13 |
 * | 主题页复制链接 / 分享 | 详情页 kebab(**图片查看器的保存/分享/复制是真的**) | 票 13 |
 * | 主题页菜单「夜间模式」 | 详情页 kebab | 票 13 |
 * | 网页页「网页字号」 | `/web` 兜底页菜单 | 票 17c |
 * | 精华区「按版块筛选」 | 精华区顶栏 | 票 16(已接) |
 *
 * 表里没有的「更多」kebab(用户资料页)也走这条:RN 版同样是 `showNotAvailable`。
 *
 * 票 16 已接的那三处直接写的 `Snackbars.show(NOT_AVAILABLE_MESSAGE)` —— 与本函数
 * 逐字等价,没有为统一写法去改它们(并行期少碰别人的文件)。
 */
fun showNotAvailable() {
  Snackbars.show(NOT_AVAILABLE_MESSAGE)
}
