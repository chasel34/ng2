package com.chasel.ng2n.core.api

import com.chasel.ng2n.golden.runGoldenDomain
import kotlin.test.Test

/**
 * `api/board-favor` domain 全量对拍(10 条,票 07)。
 *
 * `write-ok` 那条锁的是「写操作成功时 `data["0"]` 是一句文本而不是数组」——
 * 拿它当列表解会得到空数组,不能报错。
 */
class BoardFavorGoldenTest {

  @Test
  fun `api-board-favor 金样本全量对拍`() = runGoldenDomain("api/board-favor") {
    fn("parseBoardFavorites") { case -> golden(parseBoardFavorites(case.parserInput())) }
    fn("parseBoardIdInput") { case -> parseBoardIdInput(case.inputString()) }
  }
}
