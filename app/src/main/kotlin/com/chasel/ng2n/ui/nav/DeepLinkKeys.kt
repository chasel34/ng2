package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.local.NgaBoardKind
import com.chasel.ng2n.core.local.NgaLink
import com.chasel.ng2n.core.local.NgaLinkResult
import com.chasel.ng2n.core.local.parseNgaLink

/**
 * 深链目标 → 导航键。
 *
 * RN 侧对应的是 `ngaLinkPath()`(那边只能返回一个路由字符串);原生这边直接落成
 * [NavKey],省掉「拼字符串再解析回来」那一趟。**同一张映射表服务两个入口** ——
 * 系统深链(`ng2n://…`)与抽屉「由 URL 读取」,免得两处各写一份参数映射迟早走偏。
 *
 * 解析本身在 `core/local/DeepLink.kt`(纯 Kotlin,已有金样本对拍)。
 */
fun NgaLink.toNavKey(): NavKey = when (this) {
  is NgaLink.Board -> BoardKey(
    id = id,
    kind = when (boardKind) {
      NgaBoardKind.BOARD -> BoardKind.BOARD
      NgaBoardKind.COLLECTION -> BoardKind.COLLECTION
    },
  )

  is NgaLink.Topic -> TopicKey(
    tid = tid,
    page = page?.toInt(),
    pid = pid,
    fav = fav,
  )
}

/**
 * 一条外部链接 → 导航键;解不出来给 `null`。
 *
 * **不抛异常**:调用方之一是冷启动路径上的 intent 处理,那儿抛错会直接崩掉启动。
 */
fun navKeyForLink(input: String): NavKey? =
  (parseNgaLink(input) as? NgaLinkResult.Ok)?.link?.toNavKey()
