package com.chasel.ng2n.ui.topic

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * 主题相关三屏的导航键。参数与 RN 版 `expo-router` 的路由参数一一对应
 * (`research/inventory.md` §1 的第 6 / 7 / 17 行)。
 *
 * 键是 `@Serializable` 的 data class:Nav3 的 back stack 要能被
 * `rememberNavBackStack` 存进 SavedState,进程被杀后原样还原。
 */

/**
 * 主题详情(`/topic/[tid]`)。
 *
 * @param tid 真实 tid
 * @param title 从列表带进来的标题,免得等 `read.php` 回来才有字可显示
 * @param fav fav 码(CONTEXT.md「fav 码」),访问隐藏/过期主题必带
 * @param page 直接开在第几页(通知点进来时带)
 * @param pid 只看某一楼(「我的回复」/ pid 深链)
 * @param floor 进场就定位到第几楼(回复链的「在原帖中查看」)
 */
@Serializable
data class TopicKey(
  val tid: Long,
  val title: String? = null,
  val fav: String? = null,
  val page: Int? = null,
  val pid: Long? = null,
  val floor: Long? = null,
) : NavKey

/** 回复链(`/chain`)。从详情页某楼的引用块进来。 */
@Serializable
data class ChainKey(
  val tid: Long,
  /** 展开起点的楼层 pid */
  val pid: Long,
  val fav: String? = null,
) : NavKey

/**
 * 用户资料(`/user/[uid]`)。**占位**:资料屏本体归票 17,
 * 这里只保证「点头像有地方去」,票 17 落地时把占位屏换掉即可。
 */
@Serializable
data class UserKey(
  val uid: Long,
  val name: String? = null,
) : NavKey
