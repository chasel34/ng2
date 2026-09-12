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

/**
 * 「最近被喷」页 —— 直译 RN 侧 `src/app/notifications.tsx`
 * (CONTEXT.md「通知」:UI 文案沿用设计稿,代码统一叫通知)。
 *
 * 设计稿 `isNotify` 屏 1:1:顶栏「我的被喷」+ 删除按钮;正文按类型分组,分组头是
 * 图标 + 组名 + 条数,条目是头像 + 三行(谁干了什么 / 主题 / 页码·时间)。
 * 设计稿条目第二行画的是对方内容摘要,但 `noti` 接口不给正文(API 文档 §9.1),
 * 这一行放主题标题,第三行放「第 N 页 · 时间」。
 *
 * 进页即把当前条目**全部标记已读**(角标就是为了引到这儿);条目点击跳对方楼层
 * 所在页,短信类点击是「本版本未开放」(spec §1 短消息不在 v1)。
 */
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

  // 通知屏也是「前台」:首页退场之后这条循环得由它接着跑,不然停在这一屏就不再刷新
  DisposableEffect(Unit) {
    deps.notifications.start()
    onDispose { deps.notifications.stop() }
  }
  // 进页刷一次,不等下一个轮询周期
  LaunchedEffect(Unit) { deps.notifications.refresh() }
  // 页面开着就算看过:当前条目(含轮询期间新到的)全部记已读,角标随之熄灭
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
          // 游客态先挡住(票 31):`clearAll()` 对游客是「正常返回」而不是抛,
          // 直接跑下去会走进 onSuccess 报一句「已清空全部通知」—— 没登录、没通知、
          // 连 `noti&__act=del` 都没发出去,纯粹是句谎话。
          when (val gate = signedInGate(uid, "登录后才能清空通知")) {
            is SignedInGate.NeedLogin -> showLoginPrompt(nav, gate.message)

            is SignedInGate.Proceed -> scope.launch {
              runCatching { deps.notifications.clearAll() }.fold(
                onSuccess = { Snackbars.show("已清空全部通知") },
                // 服务端怎么说就怎么显示(与全 app 同一套话术)
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

      // 拉失败也是空列表,得说清是「没人喷」还是「没拉到」
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
              // 短消息不在 v1(spec §1);没有 tid 的条目也没处可跳
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

/**
 * 按稳定 id 去重(留第一条)。
 *
 * `get_all` 的三个容器装的是同一批通知的不同视图,分类看的是条目自己的类型码而不是
 * 所在容器(`core/api/Notifications.kt`),所以同一条通知**可以在两个容器里各出现一次**,
 * 解出来就是两个 id 相同的条目。列表行的 key 正是这个 id —— 重复 key 会让 Compose
 * 在首次布局抛 `Key … was already used`,整屏必崩(票 28 同一类)。
 * RN 侧的 LegendList 只是打一条警告,所以这个坑在那边一直没响。
 */
internal fun dedupeNotifications(items: List<NgaNotification>): List<NgaNotification> =
  items.distinctBy { it.id }

/** 分组的展示文案与图标。组名照设计稿,设计稿没画的组(评价/短信)按同款式补。 */
private data class GroupMeta(val label: String, val icon: Ng2nIcon)

private val GROUPS: Map<NotificationKind, GroupMeta> = mapOf(
  NotificationKind.REPLY to GroupMeta("回复我的", Ng2nIcon.REPLY),
  NotificationKind.MENTION to GroupMeta("@ 我的", Ng2nIcon.ALTERNATE_EMAIL),
  NotificationKind.COMMENT to GroupMeta("给我贴条的", Ng2nIcon.STICKY_NOTE_2),
  NotificationKind.RATING to GroupMeta("收到的评价", Ng2nIcon.THUMB_UP),
  NotificationKind.MESSAGE to GroupMeta("短消息", Ng2nIcon.SMS),
  NotificationKind.OTHER to GroupMeta("其他通知", Ng2nIcon.NOTIFICATIONS_ACTIVE),
)

/** 分组顺序(RN 侧 `GROUP_ORDER`,一字未改)。 */
private val GROUP_ORDER: List<NotificationKind> = listOf(
  NotificationKind.REPLY,
  NotificationKind.MENTION,
  NotificationKind.COMMENT,
  NotificationKind.RATING,
  NotificationKind.MESSAGE,
  NotificationKind.OTHER,
)

/** 「谁干了什么」的动词,按**原始类型码**分。@ 的文案照设计稿原字。 */
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

/** 设计稿:分组头 padding 15/16/9,底 surface2,压一条分隔线。 */
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
        // 抽屉的「论坛功能」那种分节标题带 .4 字间距,设计稿这处组名没有
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

/** 设计稿:条目 padding 13/16,头像与正文 gap 12,头像 36 见方圆角 12。 */
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
