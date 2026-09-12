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

private const val NUKED_VERIFIED = -1L

const val UCP_REFERER_PATH = "nuke.php?func=ucp"

private const val NO_IP_RECORD = "尚无记录"

private fun forumIdOf(key: String): Long? {
  val value = jsNumber(key)
  return if (value.isFinite()) jsTrunc(value) else null
}

private fun parseAdminForums(raw: JsonElement?): List<AdminForum> =
  orderedEntries(raw).mapNotNull { (key, value) ->
    val fid = forumIdOf(key) ?: return@mapNotNull null
    if (value !is JsonPrimitive || !value.isString) return@mapNotNull null
    val name = value.content.trim()
    if (name.isEmpty()) return@mapNotNull null
    AdminForum(fid = fid, name = name)
  }

private fun parseReputations(raw: JsonElement?): List<ReputationEntry> =
  orderedEntries(raw).mapNotNull { (key, value) ->
    val fid = forumIdOf(key) ?: return@mapNotNull null

    if (value is JsonObject) {
      val amount = int(value, "value") ?: int(value, "1") ?: int(value, "0")
        ?: return@mapNotNull null
      val name = str(value, "name") ?: str(value, "0")
      return@mapNotNull ReputationEntry(fid = fid, name = name ?: "版面 $fid", value = amount)
    }

    val amount = int(JsonObject(mapOf("value" to value)), "value") ?: return@mapNotNull null
    ReputationEntry(fid = fid, name = "版面 $fid", value = amount)
  }

private fun parseStatus(raw: JsonObject, nowSeconds: Long): Pair<UserStatus, Long?> {
  val verified = int(raw, "verified") ?: int(raw, "yz")
  if (verified == NUKED_VERIFIED) return UserStatus.NUKED to null

  val mutedUntil = nonZero(int(raw, "muteTime") ?: int(raw, "mute_time"))
  if (mutedUntil != null && mutedUntil > nowSeconds) return UserStatus.MUTED to mutedUntil
  return UserStatus.ACTIVE to null
}

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
    ipLocation = if (ipLoc == NO_IP_RECORD) null else ipLoc,
    status = status,
    mutedUntil = mutedUntil,
    signature = str(raw, "sign") ?: str(raw, "signature"),
    adminForums = parseAdminForums(raw["adminForums"]),
    reputations = parseReputations(raw["reputation"]),
  )
}

suspend fun fetchUserProfile(client: NgaClient, uid: Long): UserProfile =
  fetchUcpProfile(client, queryOf("__lib" to "ucp", "__act" to "get", "uid" to uid))

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

  val data = result.data
  if (data !is JsonObject) {
    throw NgaError(
      NgaErrorKind.SERVER,
      result.fakeError?.message ?: "找不到用户",
      via = result.via,
    )
  }

  return parseUserProfile(data)
    ?: throw NgaError(NgaErrorKind.PARSE, "资料响应里没有用户", via = result.via)
}

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
  return parseAvatarUrl((result.data as? JsonObject)?.get("0"))
}
