package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.local.jsNumber
import com.chasel.ng2n.data.history.pageOfFloor

/**
 * 详情页翻页的全部算术。直译 `src/ui/paging.ts`。
 *
 * 页码条、跳页对话框、左右滑动是三个入口,但**只能有一套页码规则**——
 * 各自写一遍夹逼与边界判断,迟早会出现「滑动能到第 0 页而页码条不能」这种不一致。
 * 三个入口都从这里取值,一致性就是结构性的,不靠人盯。
 *
 * **RN 版的 `swipe*` 一族没有移植**:那几个函数(位移门槛 0.28 屏宽、甩动速度
 * 0.4 px/ms、跟手 1:1 与边缘阻尼 0.22)是给自绘 pager 用的,而这里用
 * `HorizontalPager` —— `research/inventory.md` §8 的原话:「RN 的收尾弹簧
 * stiffness 500 / damping 48 是对拍原生 ViewPager 逐帧调出来的,Kotlin 用 Pager
 * 免费获得」。留下的是三个入口都要的纯算术。
 */

/** 页码窗口:当前页前后各露几格。 */
private const val WINDOW_RADIUS = 4

/** 夹到 `1 – totalPages`。非法输入(NaN / 小数 / 负数)一律退到第 1 页。 */
fun clampPage(page: Int, totalPages: Int): Int {
  val total = maxOf(1, totalPages)
  return minOf(maxOf(1, page), total)
}

/**
 * 页码条上要画哪几格:当前页前后各 [WINDOW_RADIUS] 格,外加固定露出的首尾页。
 * 上千页的帖子全铺出来会卡,所以只画一个窗口。
 */
fun visiblePages(page: Int, totalPages: Int): List<Int> {
  val total = maxOf(1, totalPages)
  val current = clampPage(page, total)
  val window = sortedSetOf(1, total)
  for (value in (current - WINDOW_RADIUS)..(current + WINDOW_RADIUS)) {
    if (value in 1..total) window.add(value)
  }
  return window.toList()
}

/**
 * 跳页对话框输进来的那串东西。
 *
 * 与另外两个入口不同,这里**不夹逼**——用户手打了 999,该告诉他超范围,
 * 而不是默默跳到最后一页。数字口径走 JS 的 `Number(trim(input))`
 * (票 10 的 [jsNumber]):`" 13 "` 认、`"3.5"` 不认、`"1e3x"` 不认。
 */
fun parseJumpTarget(input: String, totalPages: Int): Int? {
  val value = jsNumber(input.trim())
  if (!value.isFinite() || value != kotlin.math.floor(value)) return null
  val page = value.toInt()
  return if (page >= 1 && page <= maxOf(1, totalPages)) page else null
}

/**
 * 横滑 pager 的 `pageCount`。
 *
 * 数据回来之前 `totalPages` 只是个估值(进场页兜的底),而 `PagerState` 会把
 * `currentPage` 无条件钳进 `[0, pageCount - 1]` —— 被钳掉的那一下会顺着
 * `settledPage` 回写成「用户翻到了第 1 页」,于是「在原帖中查看」「上次读到第 N 楼」
 * 「通知点进来」「我的回复」四条带页码进场的入口一起报废(票 20)。
 *
 * 所以这里立一条结构性的下界:**pager 至少要装得下当前页**。真实页数回来后
 * 它自然被 [totalPages] 顶上去,估多了也只是多一格空页,不会再把页码吃掉。
 */
fun pagerPageCount(totalPages: Int, page: Int): Int = maxOf(1, totalPages, page)

// ---------------------------------------------------------------- 跳楼定位(票 34)

/**
 * 详情页列表里排在楼层**前面**的非楼层行数。
 *
 * `FloorList` 给主动页固定挂一个 header item(`ListKeys.HEADER`)——「只看此人」条
 * 与热门回复区都塞在**同一个** item 里,所以这个数**恒为 1**,与热门回复区在不在场无关。
 *
 * 票 34 的第二半就栽在这:老代码按 `hotReplies.isNotEmpty()` 现算 offset,
 * 没有热门回复的页(除了第 1 页,全部)于是整整差一楼。header 是不是空的、里面装了
 * 几段内容,都不改变「它占列表的第 0 格」这件事。
 */
const val TOPIC_LIST_HEADER_ROWS: Int = 1

/**
 * **目标楼号 → LazyColumn 的 item index**。带楼号的四条入口(回复链「在原帖中查看」/
 * 浏览历史条目 /「上次读到第 N 楼 · 回到那里」/ 通知与「我的回复」)最后都落到这里。
 *
 * 一个函数把两次换算合成一次是有原因的(票 34):楼层号 → 楼层下标 在 ViewModel、
 * 楼层下标 → item index 在屏幕上,两个 index 空间同名叫 `index`,谁也不知道自己拿的是哪个,
 * 中间那一格 header 就这么丢了。现在只有一个出口,`ScrollTarget.listIndex` 从头到尾
 * 就是「能直接喂给 `scrollToItem` 的那个数」。
 *
 * @param floorLous 本页楼层号,**按列表顺序**(被屏蔽折起来的楼也算一行,它只是换了张卡)
 * @param targetFloor 目标楼号(主楼是 0)
 * @param page 手上这一页的页码,**用服务端回的 `__PAGE`**——请求页超范围时会被钳到末页
 * @param rowsPerPage 服务端回的每页楼数
 * @return 列表 item index;目标楼不在本页(或本页压根没楼)时是 null,**这时候不许滚**
 */
fun floorScrollIndex(
  floorLous: List<Long>,
  targetFloor: Long,
  page: Int,
  rowsPerPage: Int,
  headerRows: Int = TOPIC_LIST_HEADER_ROWS,
): Int? {
  if (targetFloor < 0 || floorLous.isEmpty()) return null
  // 翻页期间屏上可能还是旧页,不核对就会拿旧页的楼层号错滚一通(RN 侧同一条注释)
  if (pageOfFloor(targetFloor.toInt(), rowsPerPage) != page) return null
  // 有楼层被删时 lou 有空洞,目标楼可能不在了:落到它后面最近的一楼;
  // 整段尾巴都被删光就落到本页最后一楼(RN 侧的 `scrollToEnd`)
  val hit = floorLous.indexOfFirst { it >= targetFloor }
  val row = if (hit >= 0) hit else floorLous.lastIndex
  return row + maxOf(0, headerRows)
}
