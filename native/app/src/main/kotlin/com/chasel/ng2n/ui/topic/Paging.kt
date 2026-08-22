package com.chasel.ng2n.ui.topic

import com.chasel.ng2n.core.local.jsNumber

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
