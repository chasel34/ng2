package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `api/notifications` domain 全量对拍(18 条,票 07)。
 *
 * 锁住的几处:稳定 ID 是 `时间戳-类型-tid-pid`(服务端不提供逐条已读,全靠它去重)、
 * 三个容器合并后按时间戳降序、缺类型码或时间戳的条目跳过、
 * 空账号的 `data["0"]` 是**空串**而不是对象。
 */
class NotificationsGoldenTest {

  @Test
  fun `api-notifications 金样本全量对拍`() = runGoldenDomain("api/notifications") {
    fn("parseNotificationFeed") { case -> golden(parseNotificationFeed(case.parserInput())) }
    fn("notificationKind") { case -> golden(notificationKind(case.longField("type").toInt())) }
  }
}
