package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaEnvelope
import kotlinx.serialization.json.JsonObject

/**
 * 各端点的**形状否决器**([com.chasel.ng2n.core.net.NgaRequest.validate])。
 *
 * `rejectNonTopicList`(在 `TopicList.kt`)是这一族的原型,出处是 ADR-0002 第 1 条
 * 与 2026-08-13 的「版块全空」排查:反封锁链原本只认「洗得成 JSON」= 成功,于是一个
 * **能解析但根本不是这个接口的响应**会被当成功、被记进成功组合缓存、并把「0 条数据」
 * 当结果交给 UI。一次瞬时失败就能把某个接口的组合缓存钉死在坏组合上。
 *
 * RN 版只给 `thread.php` 挂了这道闸(`TOPIC_LIST_REQUEST`);票 07 按票面要求把它
 * **推广到形状足够确定的其余读端点**。判据只看「是不是这个接口的东西」,
 * **不做业务校验**——权限不足、空列表、翻到底都属于正常结果。
 *
 * 没给否决器的那几个(版块收藏 `forum_favor2`、收藏夹 `topic_favor_v2`)是故意的:
 * 它们的空态响应就是一个**空对象** `{}`,没有任何键可以断言。硬编一条会在
 * 「一个都没收藏」时把好响应判成坏组合,比不判更糟。
 */

/** 假错误(「没找到」「找不到用户」这类)是正常终止,不是坏组合——一律放行。 */
private inline fun approveFakeError(envelope: NgaEnvelope, judge: (JsonObject) -> String?): String? {
  if (envelope.fakeError != null) return null
  val data = envelope.data as? JsonObject ?: return "响应里没有 data"
  return judge(data)
}

/**
 * `read.php`:一页帖子详情至少要有楼层表 / 主题信息 / 楼数之一。
 * 只看某楼、只看某人这些过滤视图同样带 `__R`(实测),所以这条对它们也成立。
 */
fun rejectNonTopicDetail(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  if (listOf("__R", "__T", "__ROWS").any { it in data }) {
    null
  } else {
    "响应里没有帖子详情结构（多半是被限流或拦截了）"
  }
}

/**
 * `app_api.php?__lib=home&__act=category`:**bare 信封**,读的是顶层——
 * 分类在 `data`、图标清单/公告/推荐版块在 `other`,两个键有一个就算这个接口回了话。
 */
fun rejectNonBoardTree(envelope: NgaEnvelope): String? {
  if (envelope.fakeError != null) return null
  // 看的是 `root` 而不是 `data`:这个接口的数据横跨两个顶层键,解析器拿的也是 root
  val root = envelope.root
  return if ("data" in root || "other" in root) null else "响应不是版块分类树（多半是被限流或拦截了）"
}

/** `nuke.php?__lib=noti&__act=get_all`:三个容器都挂在 `data["0"]` 下。 */
fun rejectNonNotificationFeed(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  if ("0" in data) null else "响应里没有通知结构（多半是被限流或拦截了）"
}

/**
 * `nuke.php?__lib=ucp`(资料 / 头像 / 官方屏蔽词):数据一律在 `data["0"]`。
 *
 * 「找不到用户」是假错误白名单里的一条,走 [approveFakeError] 放行——
 * 真的没这个人由 `fetchUserProfile` 自己按 data 为空报出来,不是坏组合。
 */
fun rejectNonUcpPayload(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  when {
    "0" in data -> null
    // **空 data 放行**:RN 版把「data 在、user 不在」单独报成「资料响应里没有用户」,
    // 说服务端偶尔这样抽风(`user-profile.ts` 的注释)。把它判成坏组合会吞掉那句话,
    // 排障时就分不清「查无此人」「服务端抽风」「被拦」——照抄那个分档,不简化
    data.isEmpty() -> null
    else -> "响应里没有 ucp 结构（多半是被限流或拦截了）"
  }
}

/**
 * `forum.php?key=…`:命中的版块以**数字键**直接挂在 `data` 上;
 * 一个都没搜到时 `data` 只剩 `__MESSAGE`(服务端的提示位)。两者都不在 = 不是这个接口。
 */
fun rejectNonBoardSearch(envelope: NgaEnvelope): String? = approveFakeError(envelope) { data ->
  val looksLikeResults = data.keys.any { key -> key.toLongOrNull() != null }
  if (looksLikeResults || "__MESSAGE" in data) {
    null
  } else {
    "响应里没有版块搜索结果结构（多半是被限流或拦截了）"
  }
}
