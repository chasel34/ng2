package com.chasel.ng2n.core.api

import com.chasel.ng2n.core.net.NgaErrorThrowDescriber
import com.chasel.ng2n.golden.longField
import com.chasel.ng2n.golden.runGoldenDomain
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test

/**
 * `api/board-tree` domain 全量对拍(6 条,票 07)。
 *
 * 这个接口读的是**顶层**(`part: "root"`、`envelope: "bare"`):图标清单、公告、
 * 推荐版块都挂在 `other` 上而不是 `data` 里。
 * 两条 throws 用例锁的是「一个版块都没解析出来 ⇒ 抛 parse(可重试)」——
 * 对调用方来说这和被封是一回事,该继续用本地缓存而不是把首页刷成空。
 */
class BoardTreeGoldenTest {

  @Test
  fun `api-board-tree 金样本全量对拍`() = runGoldenDomain("api/board-tree") {
    throwsDescribedBy(NgaErrorThrowDescriber)
    fn("parseBoardTree") { case -> golden(parseBoardTree(case.parserInput())) }
    fn("pickActiveAnnouncement") { case ->
      val announcements: List<HomeAnnouncement> =
        ApiGoldenJson.decodeFromJsonElement(case.field("announcements"))
      golden(pickActiveAnnouncement(announcements, case.longField("now")))
    }
  }
}
