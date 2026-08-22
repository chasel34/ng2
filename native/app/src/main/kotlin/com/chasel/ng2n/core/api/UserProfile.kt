package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.local.toReputation
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.NgaErrorKind
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.QueryParams
import com.chasel.ng2n.core.net.jsNumber
import com.chasel.ng2n.core.net.jsTrunc
import com.chasel.ng2n.core.net.queryOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 用户资料的解析(API 文档 §11.1,`nuke.php?__lib=ucp`)。直译 `src/core/api/user-profile.ts`。
 *
 * 两条规矩把这个接口和别的接口区分开:
 *
 * 1. **必须带 Referer**,且要以 base url 开头,否则服务端拒绝(API 文档 §0.3)。
 *    用 [NgaRequest.refererPath] 而不是写死的完整 URL:反封锁链会换域名,
 *    Referer 得跟着当前 host 走。
 * 2. **「找不到用户」在假错误白名单里**(core/net 的 `FAKE_ERROR_MESSAGES`),
 *    命中时 `parseNgaJson` 当成功返回、`data` 是空。所以这里必须自己分三种情况:
 *    没有 data(真的没这个人)、有 data 但 `data["0"]` 是空的(服务端抽风)、正常。
 *    两种都得报错,但报的不是同一句话——排障时区分得开「查无此人」和「响应是空的」。
 */

/** `verified`/`yz` 的这个取值表示账号被 nuke(API 文档 §11.1)。 */
private const val NUKED_VERIFIED = -1L

/** 资料接口的 Referer,必须以 base url 开头(API 文档 §11.1)。 */
const val UCP_REFERER_PATH = "nuke.php?func=ucp"

/** 服务端在没有 IP 记录时给的占位,不是真的属地。 */
private const val NO_IP_RECORD = "尚无记录"

/** `Number(key)`:键是 `-2` / `650` 这类版面 id。解不出来的键跳过。 */
private fun forumIdOf(key: String): Long? {
  val value = jsNumber(key)
  return if (value.isFinite()) jsTrunc(value) else null
}

/**
 * `adminForums`:`{ "<fid>": "版面名" }`,fid 可以是负数(`-2` 这种是合集/特殊版面)。
 * 实测只有真的担任职务的账号才有这个键。
 */
private fun parseAdminForums(raw: JsonElement?): List<AdminForum> =
  orderedEntries(raw).mapNotNull { (key, value) ->
    val fid = forumIdOf(key) ?: return@mapNotNull null
    if (value !is JsonPrimitive || !value.isString) return@mapNotNull null
    val name = value.content.trim()
    if (name.isEmpty()) return@mapNotNull null
    AdminForum(fid = fid, name = name)
  }

/**
 * `reputation`:各版声望。抓包样本里没见过这个键(只有攒过声望的账号才有),
 * 所以两种可能的形状都收:`{ "<fid>": 42 }` 与 `{ "<fid>": { name, value } }`。
 * 名字解不出来就退回 `版面 <fid>`——条形图那行总得有个左边的标签。
 */
private fun parseReputations(raw: JsonElement?): List<ReputationEntry> =
  orderedEntries(raw).mapNotNull { (key, value) ->
    val fid = forumIdOf(key) ?: return@mapNotNull null

    if (value is JsonObject) {
      val amount = int(value, "value") ?: int(value, "1") ?: int(value, "0")
        ?: return@mapNotNull null
      val name = str(value, "name") ?: str(value, "0")
      return@mapNotNull ReputationEntry(fid = fid, name = name ?: "版面 $fid", value = amount)
    }

    // 裸数字/数字串那一档:包一层交给 `int` 走同一套「字符串数字也收」的规则
    val amount = int(JsonObject(mapOf("value" to value)), "value") ?: return@mapNotNull null
    ReputationEntry(fid = fid, name = "版面 $fid", value = amount)
  }

/**
 * 账号状态(设计稿基础信息卡的「状态」一格)。
 *
 * 被 nuke 压过禁言:一个已经被封的号还标「禁言中」没有意义。
 * `muteTime` 是禁言到期的秒级时间戳,0 表示没禁言;过了期也不再算禁言,
 * 所以要拿 [nowSeconds] 比一比而不是只判非 0。
 */
private fun parseStatus(raw: JsonObject, nowSeconds: Long): Pair<UserStatus, Long?> {
  val verified = int(raw, "verified") ?: int(raw, "yz")
  if (verified == NUKED_VERIFIED) return UserStatus.NUKED to null

  val mutedUntil = nonZero(int(raw, "muteTime") ?: int(raw, "mute_time"))
  if (mutedUntil != null && mutedUntil > nowSeconds) return UserStatus.MUTED to mutedUntil
  return UserStatus.ACTIVE to null
}

/**
 * 解一份用户资料。传的是响应的 `data`。
 *
 * 解不出用户时返回 null(而不是抛):调用方要按「找不到用户 / 响应是空的」
 * 分别措辞,判空的活儿留给它。
 *
 * @param nowSeconds 判定禁言是否还在有效期内的基准时刻,秒级 unix 时间戳
 */
fun parseUserProfile(
  data: JsonElement?,
  nowSeconds: Long = System.currentTimeMillis() / 1000,
): UserProfile? {
  val raw = (data as? JsonObject)?.get("0") as? JsonObject ?: return null

  val uid = int(raw, "uid") ?: return null
  val ipLoc = str(raw, "ipLoc")
  val (status, mutedUntil) = parseStatus(raw, nowSeconds)

  return UserProfile(
    uid = uid,
    name = str(raw, "username") ?: "UID $uid",
    avatarUrl = parseAvatarUrl(raw["avatar"]),
    group = str(raw, "group"),
    email = str(raw, "email"),
    phone = str(raw, "phone"),
    postCount = int(raw, "posts") ?: int(raw, "postnum") ?: 0,
    money = int(raw, "money") ?: 0,
    reputation = toReputation((int(raw, "rvrc") ?: int(raw, "fame") ?: 0L).toDouble()),
    registeredAt = nonZero(int(raw, "regdate")),
    // 「尚无记录」是服务端的占位文案而不是属地,别把它当成一个地名显示出去
    ipLocation = if (ipLoc == NO_IP_RECORD) null else ipLoc,
    status = status,
    mutedUntil = mutedUntil,
    signature = str(raw, "sign") ?: str(raw, "signature"),
    adminForums = parseAdminForums(raw["adminForums"]),
    reputations = parseReputations(raw["reputation"]),
  )
}

/**
 * 拉一份用户资料(`POST nuke.php?__lib=ucp&__act=get`)。
 *
 * 接口本身 uid / username 二选一(API 文档 §11.1,uid 优先),所以按名字查
 * (用户搜索)也是这条路:[fetchUserProfileByName]。用户名按 UTF-8 编码即可——
 * 传输层默认声明 `__inchst=UTF8`,2026-08-08 中文名真机验证过。
 */
suspend fun fetchUserProfile(client: NgaClient, uid: Long): UserProfile =
  fetchUcpProfile(client, queryOf("__lib" to "ucp", "__act" to "get", "uid" to uid))

/** 按用户名拉资料(用户搜索:输入不是纯数字时走这条)。 */
suspend fun fetchUserProfileByName(client: NgaClient, username: String): UserProfile =
  fetchUcpProfile(client, queryOf("__lib" to "ucp", "__act" to "get", "username" to username))

private suspend fun fetchUcpProfile(
  client: NgaClient,
  query: QueryParams,
): UserProfile {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = query,
      refererPath = UCP_REFERER_PATH,
      validate = ::rejectNonUcpPayload,
    ),
  )

  // 「找不到用户」在假错误白名单里,走不到 NgaError,只能靠 data 为空认出来
  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(
      NgaErrorKind.SERVER,
      result.fakeError?.message ?: "找不到用户",
      via = result.via,
    )
  }

  // data 在、user 不在:服务端偶尔这样抽风,和「查无此人」不是一回事
  return parseUserProfile(data)
    ?: throw NgaError(NgaErrorKind.PARSE, "资料响应里没有用户", via = result.via)
}

/**
 * 头像补充查询(API 文档 §11.2)。资料接口的 `avatar` 是空串时才用得上,
 * 拿不到就返回 null——UI 那边还有首字占位兜底,不该为一张头像报错。
 */
suspend fun fetchUserAvatar(client: NgaClient, uid: Long): String? {
  val result = client.execute(
    NgaRequest(
      path = "nuke.php",
      operation = Operation.READ,
      query = queryOf("__lib" to "ucp", "__act" to "get_avatar", "uid" to uid),
      refererPath = UCP_REFERER_PATH,
      validate = ::rejectNonUcpPayload,
    ),
  )
  // 这个接口的 data["0"] 是 URL 字符串本身,不是对象
  return parseAvatarUrl((result.data as? JsonObject)?.get("0"))
}
