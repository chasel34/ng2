package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `api/topic-favor` domain 全量对拍(3 条,票 07)。
 *
 * `new-folder` 那条锁的是「新建收藏夹的响应拿去解列表要得到空列表」——
 * 那份 `data` 里装的是新夹 id 与「操作成功」文案,不是夹的数组。
 */
class TopicFavorGoldenTest {

  @Test
  fun `api-topic-favor 金样本全量对拍`() = runGoldenDomain("api/topic-favor") {
    fn("parseFavoriteFolders") { case -> golden(parseFavoriteFolders(case.parserInput())) }
  }
}
