package com.chasel.ng2n.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.api.NgaNotification
import com.chasel.ng2n.core.api.NotificationKind
import com.chasel.ng2n.data.account.EMPTY_ACCOUNTS
import com.chasel.ng2n.data.account.currentAccountOf
import com.chasel.ng2n.data.notifications.groupNotifications
import com.chasel.ng2n.ui.Login
import com.chasel.ng2n.ui.board.relativeTimeText
import com.chasel.ng2n.ui.common.EmptyState
import com.chasel.ng2n.ui.common.ListKeys
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.NOT_AVAILABLE_MESSAGE
import com.chasel.ng2n.ui.common.SignedInGate
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateAction
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.rowClickable
import com.chasel.ng2n.ui.common.showLoginPrompt
import com.chasel.ng2n.ui.common.signedInGate
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.TopicKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

@Composable
fun NotificationsScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val accountsState by deps.accounts.accounts.collectAsStateWithLifecycle(
    initialValue = EMPTY_ACCOUNTS,
  )
  val uid = currentAccountOf(accountsState)?.uid
  val loggedIn = uid != null

  val items by deps.notifications.notifications.collectAsStateWithLifecycle()
  val refreshing by deps.notifications.refreshing.collectAsStateWithLifecycle()
  val error by deps.notifications.lastError.collectAsStateWithLifecycle()

  DisposableEffect(Unit) {
    deps.notifications.start()
    onDispose { deps.notifications.stop() }
  }
  LaunchedEffect(Unit) { deps.notifications.refresh() }
  LaunchedEffect(items) {
    if (items.isNotEmpty()) deps.notifications.markRead(items.map { it.id })
  }

  val groups = remember(items) {
    groupNotifications(dedupeNotifications(items), GROUP_ORDER) { it.kind }
  }
  val nowMs = remember(items) { System.currentTimeMillis() }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "我的被喷", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.DELETE,
        size = 23.dp,
        contentDescription = "清空全部通知",
        onClick = {
          when (val gate = signedInGate(uid, "登录后才能清空通知")) {
            is SignedInGate.NeedLogin -> showLoginPrompt(nav, gate.message)

            is SignedInGate.Proceed -> scope.launch {
              runCatching { deps.notifications.clearAll() }.fold(
                onSuccess = { Snackbars.show("已清空全部通知") },
                onFailure = { Snackbars.show(failureText(it)) },
              )
            }
          }
        },
      )
    }

    when {
      !loggedIn -> EmptyState(
        icon = Ng2nIcon.PERSON_ADD,
        text = "登录后才能收通知",
        action = StateAction("去登录") { nav.push(Login) },
      )

      items.isEmpty() && refreshing -> LoadingState()

      items.isEmpty() && error != null -> LoadFailedNotice(
        error = error,
        onRetry = { scope.launch { deps.notifications.refresh() } },
        variant = StateVariant.SCREEN,
      )

      items.isEmpty() -> EmptyState(icon = Ng2nIcon.NOTIFICATIONS_ACTIVE, text = "最近没人喷你")

      else -> LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
          val meta = GROUPS.getValue(group.kind)
          item(key = ListKeys.groupHead(group.kind), contentType = "group-head") {
            GroupHeader(icon = meta.icon, label = meta.label, count = group.items.size)
          }
          items(
            count = group.items.size,
            key = { index -> group.items[index].id },
            contentType = { "notification" },
          ) { index ->
            val item = group.items[index]
            NotificationRow(item = item, nowMs = nowMs) {
              if (item.kind == NotificationKind.MESSAGE || item.tid == 0L) {
                Snackbars.show(NOT_AVAILABLE_MESSAGE)
              } else {
                nav.push(TopicKey(tid = item.tid, title = item.subject, page = item.page))
              }
            }
          }
        }
        item(key = ListKeys.TAIL, contentType = "tail") { ListTail() }
      }
    }
  }
}

internal fun dedupeNotifications(items: List<NgaNotification>): List<NgaNotification> =
  items.distinctBy { it.id }

private data class GroupMeta(val label: String, val icon: Ng2nIcon)

private val GROUPS: Map<NotificationKind, GroupMeta> = mapOf(
  NotificationKind.REPLY to GroupMeta("回复我的", Ng2nIcon.REPLY),
  NotificationKind.MENTION to GroupMeta("@ 我的", Ng2nIcon.ALTERNATE_EMAIL),
  NotificationKind.COMMENT to GroupMeta("给我贴条的", Ng2nIcon.STICKY_NOTE_2),
  NotificationKind.RATING to GroupMeta("收到的评价", Ng2nIcon.THUMB_UP),
  NotificationKind.MESSAGE to GroupMeta("短消息", Ng2nIcon.SMS),
  NotificationKind.OTHER to GroupMeta("其他通知", Ng2nIcon.NOTIFICATIONS_ACTIVE),
)

private val GROUP_ORDER: List<NotificationKind> = listOf(
  NotificationKind.REPLY,
  NotificationKind.MENTION,
  NotificationKind.COMMENT,
  NotificationKind.RATING,
  NotificationKind.MESSAGE,
  NotificationKind.OTHER,
)

internal fun notificationVerb(type: Int): String = when (type) {
  1 -> "回复了你的主题"
  2 -> "回复了你的楼层"
  3 -> "给你的主题贴条"
  4 -> "给你的楼层贴条"
  7, 8 -> "在帖子里 @ 了你"
  10 -> "发来一条短消息"
  11 -> "回复了你的短消息"
  17 -> "评价了你的帖子"
  else -> "发来一条通知"
}

@Composable
private fun GroupHeader(icon: Ng2nIcon, label: String, count: Int) {
  val colors = LocalNg2nColors.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface2)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(top = 15.dp, bottom = 9.dp)
      .padding(horizontal = Spacing.lg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    AppIcon(icon = icon, tint = colors.primary, size = 18.dp)
    Text(
      text = label,
      modifier = Modifier.weight(1f),
      style = TextStyle(
        fontSize = Typo.caption.size,
        lineHeight = Typo.caption.lineHeight,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
      ),
    )
    Text(
      text = "$count 条",
      style = TextStyle(
        fontSize = Typo.meta.size,
        lineHeight = Typo.meta.lineHeight,
        color = colors.meta,
      ),
    )
  }
}

@Composable
private fun NotificationRow(item: NgaNotification, nowMs: Long, onClick: () -> Unit) {
  val colors = LocalNg2nColors.current
  val headline = remember(item.id, item.userName, item.type, colors) {
    buildAnnotatedString {
      withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.link)) {
        append(item.userName)
      }
      append(" ")
      append(notificationVerb(item.type))
    }
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .rowClickable(onClickLabel = item.subject, onClick = onClick)
      .drawBehind {
        val y = size.height - 1f
        drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1f)
      }
      .padding(vertical = 13.dp, horizontal = Spacing.lg),
    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    InitialAvatar(
      name = item.userName,
      colorKey = (item.userId ?: item.userName).toString(),
      size = 36.dp,
      fontSize = Typo.notifyInitial.size,
      shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.md),
    )
    Column(Modifier.weight(1f)) {
      Text(
        text = headline,
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          color = colors.fg,
        ),
      )
      Text(
        text = item.subject,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
        style = TextStyle(
          fontSize = Typo.listMeta.size,
          lineHeight = Typo.listMeta.lineHeight,
          color = colors.fg2,
        ),
      )
      val time = relativeTimeText(item.timestamp, nowMs)
      Text(
        text = if (item.kind == NotificationKind.MESSAGE || item.tid == 0L) {
          time
        } else {
          "第 ${item.page} 页 · $time"
        },
        modifier = Modifier.padding(top = 5.dp),
        style = TextStyle(
          fontSize = Typo.notifyMeta.size,
          lineHeight = Typo.notifyMeta.lineHeight,
          color = colors.meta,
        ),
      )
    }
  }
}
