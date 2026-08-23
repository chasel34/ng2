package com.chasel.ng2n.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chasel.ng2n.core.net.NgaError
import com.chasel.ng2n.core.net.describeFetchFailure
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

/**
 * 空态 / 加载态 / 「拉失败了」的统一口径 —— 直译 RN 侧 `ui/state-view.tsx`
 * 与 `ui/error-screen.tsx` 里被本票用到的那几个形状。
 *
 * 设计稿没有单独画空屏,但 isError 那一屏定下了这套语言:一枚 meta 色的大图标 +
 * 居中说明文字(+ 可选的一个出路)。
 */

/** 设计稿 isError 的图标是 34,空态没有那圈 72 的底,所以放大到 40 撑住版面。 */
private val EMPTY_ICON_SIZE = 40.dp

enum class StateVariant {
  /** 撑满剩余高度并垂直居中(整屏没内容时用) */
  SCREEN,

  /** 只占一段固定高度(嵌在列表里、上面还有筛选条或分组头时用) */
  INLINE,

  /**
   * 列表**头部**的空态:上留白撑开、下留白只留一档。
   *
   * 收藏夹管理屏的空态下面还紧跟着一段说明文字([INLINE] 的下 56 会把两者拉开
   * 一屏那么远,票 50)。RN 侧这一屏本来就没走共用的 `EmptyState`,而是自己写了
   * 一份 `center`(`paddingTop: 60` + `padding: 20`),这一档就是它。
   */
  INLINE_HEAD,
}

data class StateAction(val label: String, val onClick: () -> Unit)

@Composable
private fun StateBox(
  variant: StateVariant,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  val base = when (variant) {
    StateVariant.SCREEN -> Modifier.fillMaxSize().padding(Spacing.xl)
    // 与 LoadFailedNotice 的纵向 56 对齐,列表里两种块换着出现时高度不跳
    StateVariant.INLINE ->
      Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = Spacing.xl)
    // RN 侧 `favorites/folders.tsx` 的 `center`:上 60、下 20(左右仍是 20)
    StateVariant.INLINE_HEAD -> Modifier
      .fillMaxWidth()
      .padding(top = 60.dp, bottom = Spacing.xl, start = Spacing.xl, end = Spacing.xl)
  }
  Column(
    modifier = base,
    verticalArrangement = if (variant == StateVariant.SCREEN) {
      Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically)
    } else {
      Arrangement.spacedBy(Spacing.md)
    },
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content,
  )
}

/** 「这儿还没有内容」。 */
@Composable
fun EmptyState(
  icon: Ng2nIcon,
  text: String,
  modifier: Modifier = Modifier,
  action: StateAction? = null,
  variant: StateVariant = StateVariant.SCREEN,
) {
  val colors = LocalNg2nColors.current
  Box(modifier) {
    StateBox(variant) {
      AppIcon(icon = icon, tint = colors.meta, size = EMPTY_ICON_SIZE)
      Text(
        text = text,
        textAlign = TextAlign.Center,
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          color = colors.fg2,
        ),
      )
      if (action != null) PillButton(action)
    }
  }
}

/** 「正在拉」。整屏首次加载与列表内的分段加载共用同一个转圈。 */
@Composable
fun LoadingState(
  modifier: Modifier = Modifier,
  text: String? = null,
  variant: StateVariant = StateVariant.SCREEN,
) {
  val colors = LocalNg2nColors.current
  Box(modifier) {
    StateBox(variant) {
      CircularProgressIndicator(color = colors.primary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
      if (text != null) {
        Text(
          text = text,
          textAlign = TextAlign.Center,
          style = TextStyle(
            fontSize = Typo.notice.size,
            lineHeight = Typo.notice.lineHeight,
            color = colors.fg2,
          ),
        )
      }
    }
  }
}

/**
 * 列表底部「正在载入下一页」的那一行。设计稿(isList 底部提示)是一行 12.5 的
 * meta 字,不带转圈;翻页时把转圈也带上,但整行高度维持设计稿的 20 内距。
 */
@Composable
fun LoadingFooter(text: String, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xl),
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(color = colors.meta, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    Text(
      text = text,
      style = TextStyle(fontSize = Typo.listMeta.size, lineHeight = Typo.listMeta.lineHeight, color = colors.meta),
    )
  }
}

/** 出路按钮:40 高的胶囊(照 LoadFailedNotice 的重试钮)。 */
@Composable
fun PillButton(action: StateAction, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  Box(
    modifier = modifier
      .height(40.dp)
      .clip(RoundedCornerShape(Radius.full))
      .background(colors.primary)
      .clickable(onClickLabel = action.label, onClick = action.onClick)
      .padding(horizontal = Spacing.xl),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = action.label,
      style = TextStyle(
        fontSize = Typo.drawerItem.size,
        lineHeight = Typo.drawerItem.lineHeight,
        fontWeight = FontWeight.SemiBold,
        color = colors.onPrimary,
      ),
    )
  }
}

/**
 * 「拉失败了」的轻量形态(RN 侧 `LoadFailedNotice`)。
 *
 * 文案统一走 [failureText] —— 见那里对「为什么不能直接印 [NgaError.text]」的说明。
 */
@Composable
fun LoadFailedNotice(
  error: Throwable?,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  variant: StateVariant = StateVariant.INLINE,
) {
  EmptyState(
    icon = Ng2nIcon.CLOUD_OFF,
    text = failureText(error),
    action = StateAction("重试", onRetry),
    variant = variant,
    modifier = modifier,
  )
}

/** 谁都说不清这次失败时的兜底话术。 */
const val FAILURE_FALLBACK = "没能拿到数据"

/**
 * 一句话说清这次失败。**全 app 只有这一处**把异常翻成用户看的话(票 24)。
 *
 * ## 为什么不能直接印 `error.message`
 *
 * 反封锁链在传输层失败时包的是 `NgaError(NETWORK, cause.message)`
 * (`core/net/strategies/Attempt.kt`),而 `cause` 是 okhttp 抛的 `UnknownHostException`
 * ——`message` 就是 `Unable to resolve host "bbs.ngacn.cc": No address associated with
 * hostname`。这句话有两个毛病:一是英文异常原文,二是里面那个域名是**轮换链当时试到的
 * 那一个**,不是用户在设置里选的,把反封锁链的内部状态漏给了用户(票 24 现象)。
 *
 * 所以这里与主题详情的失败面板走**同一张文案表**([describeFetchFailure]):
 * 按 [NgaErrorKind] 分档翻成中文,只有服务端自己把话说清楚的那一档(`server`)
 * 才照搬原文 —— 那是论坛给用户看的中文说明,比我们编的强。
 * 状态码那一截跟在后面(`服务端返回 HTTP 403`),它不含域名也不含异常原文。
 *
 * 认不出的异常一律退化成 [FAILURE_FALLBACK]:排障线索该进诊断日志
 * (`NgaError.cause` 与 `NgaError.diagnostic` 都还留着整条链的记录),不该进屏。
 */
fun failureText(error: Throwable?): String {
  if (error !is NgaError) return FAILURE_FALLBACK
  val copy = describeFetchFailure(error.kind.wire, error.status, error.text)
  val headline = copy.headline.ifBlank { FAILURE_FALLBACK }
  return if (copy.code == null) headline else "$headline ${copy.code}"
}
