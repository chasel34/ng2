package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

class NotificationsGoldenTest {

  @Test
  fun `api-notifications 金样本全量对拍`() = runGoldenDomain("api/notifications") {
    fn("parseNotificationFeed") { case -> golden(parseNotificationFeed(case.parserInput())) }
    fn("notificationKind") { case -> golden(notificationKind(case.longField("type").toInt())) }
  }
}
