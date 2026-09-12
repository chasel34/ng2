package com.chasel.ng2n.ui.nav

import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.core.api.BoardKind
import com.chasel.ng2n.core.local.NgaBoardKind
import com.chasel.ng2n.core.local.NgaLink
import com.chasel.ng2n.core.local.NgaLinkResult
import com.chasel.ng2n.core.local.parseNgaLink

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

fun navKeyForLink(input: String): NavKey? =
  (parseNgaLink(input) as? NgaLinkResult.Ok)?.link?.toNavKey()
