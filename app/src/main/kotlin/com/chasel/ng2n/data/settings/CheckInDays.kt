package com.chasel.ng2n.data.settings

import java.time.Instant
import java.time.ZoneOffset

/**
 * 签到的本地去重 —— `src/core/local/check-in.ts` 的直译。
 *
 * 服务端不给「今天签没签过」的查询接口,只在重复签到时回一句「今天已经签到」
 * (还是**假错误**白名单里的,当成功处理)。所以要不要发这次请求由本地记账决定:
 * 每个账号记一条「最后签到日」,日期一致就不再打接口。
 *
 * 日界线按 **UTC+8** 而不是设备时区:NGA 的一天是北京时间的一天,
 * 用户人在别的时区时,按设备时区算会在午夜前后多签或少签一次。
 */

/** 北京时间相对 UTC 的偏移(NGA 全年不调时,固定 +8)。 */
private val BEIJING = ZoneOffset.ofHours(8)

/** 某账号最后一次签到的日期,`uid → YYYY-MM-DD`(UTC+8)。 */
typealias CheckInDays = Map<String, String>

val EMPTY_CHECK_IN_DAYS: CheckInDays = emptyMap()

/** 某个时刻落在 UTC+8 的哪一天,形如 `2026-08-08`。 */
fun beijingDayKey(nowMs: Long): String =
  Instant.ofEpochMilli(nowMs).atOffset(BEIJING).toLocalDate().toString()

/** 这个账号今天(UTC+8)签过了吗。 */
fun isCheckedInOn(days: CheckInDays, uid: String, nowMs: Long): Boolean =
  days[uid] == beijingDayKey(nowMs)

/** 记一次签到成功。同一账号只留最后一天,不攒历史。 */
fun withCheckedIn(days: CheckInDays, uid: String, nowMs: Long): CheckInDays =
  days + (uid to beijingDayKey(nowMs))

private val DAY_KEY_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * 从持久化的表还原。**坏数据一律当「没签过」** ——
 * 最坏结果只是多发一次签到请求,而服务端本来就幂等(重复签到回假错误)。
 */
fun sanitizeCheckInDays(days: Map<String, String>): CheckInDays =
  days.filterValues { DAY_KEY_PATTERN.matches(it) }
