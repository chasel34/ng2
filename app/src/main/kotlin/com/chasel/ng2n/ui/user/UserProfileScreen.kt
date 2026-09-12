package com.chasel.ng2n.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.chasel.ng2n.core.api.ATTACH_BASE_FALLBACK
import com.chasel.ng2n.core.api.UserProfile
import com.chasel.ng2n.core.api.UserStatus
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.core.bbcode.unescapeNgaText
import com.chasel.ng2n.core.local.formatMoney
import com.chasel.ng2n.core.local.formatReputation
import com.chasel.ng2n.data.user.UserProfileRepository
import com.chasel.ng2n.ui.bbcode.BBCodeContent
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.BBCodeRenderOptions
import com.chasel.ng2n.ui.bbcode.RenderModelBuilder
import com.chasel.ng2n.ui.board.dateText
import com.chasel.ng2n.ui.common.InputDialog
import com.chasel.ng2n.ui.common.LoadFailedNotice
import com.chasel.ng2n.ui.common.LoadingState
import com.chasel.ng2n.ui.common.Snackbars
import com.chasel.ng2n.ui.common.StateVariant
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.common.failureText
import com.chasel.ng2n.ui.common.showNotAvailable
import com.chasel.ng2n.ui.home.initialOf
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.nav.Navigator
import com.chasel.ng2n.ui.nav.UserKey
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.Elevation
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.LocalNg2nTitleColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import com.chasel.ng2n.ui.theme.avatarColorFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val BANNER_HEIGHT = 118.dp
private val BANNER_AVATAR = 62.dp

private val STRIPE_WIDTH = 12.dp
private val STRIPE_PITCH = 34.dp
private const val STRIPE_COUNT = 20

private val REPUTATION_BAR_WIDTH = 96.dp

@Composable
fun UserProfileScreen(key: UserKey, nav: Navigator, modifier: Modifier = Modifier) {
  val colors = LocalNg2nColors.current
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()

  val all by deps.userProfiles.states.collectAsStateWithLifecycle()
  val state = all[key.uid] ?: UserProfileRepository.State()
  LaunchedEffect(key.uid) { deps.userProfiles.ensureLoaded(key.uid) }

  val currentUid by deps.accounts.currentUid.collectAsStateWithLifecycle(initialValue = null)
  val isMine = currentUid != null && currentUid?.toLongOrNull() == key.uid
  var signOpen by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize().background(colors.bg)) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = nav::pop,
      )
      TopBarTitle(text = "用户资料", variant = TopBarTitleVariant.SUB)
      Spacer(Modifier.weight(1f))
      TopBarButton(
        icon = Ng2nIcon.SMS,
        size = 22.dp,
        contentDescription = "发短消息",
        onClick = ::showNotAvailable,
      )
      TopBarButton(
        icon = Ng2nIcon.MORE_VERT,
        size = 22.dp,
        contentDescription = "更多",
        onClick = ::showNotAvailable,
      )
    }

    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 24.dp),
    ) {
      Banner(
        uid = key.uid,
        name = state.profile?.name ?: key.name ?: "UID ${key.uid}",
        avatarUrl = state.profile?.avatarUrl,
      )

      when {
        state.profile == null && state.loading -> LoadingState(variant = StateVariant.INLINE)
        state.profile == null -> LoadFailedNotice(
          error = state.error,
          onRetry = { scope.launch { deps.userProfiles.reload(key.uid) } },
        )
        else -> ProfileBody(
          profile = state.profile,
          onEditSignature = if (isMine) ({ signOpen = true }) else null,
        )
      }
    }
  }

  InputDialog(
    open = signOpen,
    title = "修改签名",
    hint = "支持 BBCode 与 emoji;留空即清除签名",
    confirmLabel = "保存",
    multiline = true,
    initialValue = unescapeNgaText(state.profile?.signature ?: ""),
    onCancel = { signOpen = false },
    onConfirm = { text ->
      signOpen = false
      val writer = currentUid
      if (writer == null) {
        Snackbars.show("登录后才能改签名")
      } else {
        scope.launch {
          runCatching { deps.userProfiles.saveSignature(writer, text) }.fold(
            onSuccess = { Snackbars.show("签名已保存") },
            onFailure = { Snackbars.show(failureText(it)) },
          )
        }
      }
    },
  )
}

@Composable
private fun Banner(uid: Long, name: String, avatarUrl: String?) {
  val colors = LocalNg2nColors.current
  var failed by remember(avatarUrl) { mutableStateOf(false) }

  Box(
    Modifier
      .fillMaxWidth()
      .height(BANNER_HEIGHT)
      .background(colors.primary)
      .clipToBounds(),
  ) {
    for (index in 0 until STRIPE_COUNT) {
      Box(
        Modifier
          .offset(x = STRIPE_PITCH * index, y = -BANNER_HEIGHT)
          .requiredWidth(STRIPE_WIDTH)
          .requiredHeight(BANNER_HEIGHT * 3)
          .rotate(45f)
          .background(colors.primaryDark),
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomStart)
        .padding(horizontal = Spacing.page)
        .padding(bottom = Spacing.row),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(Spacing.row),
    ) {
      val avatarModifier = Modifier
        .size(BANNER_AVATAR)
        .clip(CircleShape)
        .border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape)
      if (avatarUrl == null || failed) {
        Box(
          avatarModifier.background(avatarColorFor(uid.toString())),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = initialOf(name),
            style = TextStyle(
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              color = colors.onPrimary,
            ),
          )
        }
      } else {
        AsyncImage(
          model = avatarUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          onError = { failed = true },
          modifier = avatarModifier,
        )
      }

      Column(Modifier.weight(1f).padding(bottom = Spacing.xs)) {
        Text(
          text = name,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = TextStyle(
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onPrimary,
          ),
        )
        Text(
          text = "用户 ID：$uid",
          modifier = Modifier.padding(top = Spacing.xs),
          style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = colors.onPrimary.copy(alpha = 0.85f)),
        )
      }
    }
  }
}

private data class BasicField(val label: String, val value: String, val color: Color? = null)

@Composable
private fun basicFields(profile: UserProfile, colors: Ng2nColors): List<BasicField> {
  val titleColors = LocalNg2nTitleColors.current
  val status = when (profile.status) {
    UserStatus.ACTIVE -> "已激活" to titleColors.green
    UserStatus.MUTED -> "禁言中" to colors.danger
    UserStatus.NUKED -> "已封禁(NUKED)" to colors.danger
  }
  return listOf(
    BasicField("邮箱", profile.email ?: "N/A"),
    BasicField("Tel", profile.phone ?: "N/A"),
    BasicField("用户组", profile.group ?: "N/A"),
    BasicField("发帖数", profile.postCount.toString()),
    BasicField("金钱", formatMoney(profile.money.toDouble())),
    BasicField("威望", formatReputation(profile.reputation)),
    BasicField("状态", status.first, status.second),
    BasicField("注册日期", profile.registeredAt?.let(::dateText) ?: "N/A"),
    BasicField("属地", profile.ipLocation ?: "N/A"),
  )
}

@Composable
private fun ProfileBody(profile: UserProfile, onEditSignature: (() -> Unit)?) {
  val colors = LocalNg2nColors.current
  val fields = basicFields(profile, colors)

  Column(
    Modifier.padding(top = Spacing.md, start = Spacing.md, end = Spacing.md),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Card {
      CardTitle(":: 基础信息 ::")
      Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        for (row in fields.chunked(2)) {
          Row(Modifier.fillMaxWidth()) {
            for ((column, field) in row.withIndex()) {
              Text(
                text = buildAnnotatedString {
                  withStyle(SpanStyle(color = colors.fg2)) { append("${field.label}：") }
                  withStyle(SpanStyle(color = field.color ?: colors.fg)) { append(field.value) }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (column == 1) TextAlign.End else TextAlign.Start,
                modifier = Modifier
                  .weight(1f)
                  .padding(
                    start = if (column == 1) Spacing.md / 2 else 0.dp,
                    end = if (column == 1) 0.dp else Spacing.md / 2,
                  ),
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = colors.fg2),
              )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      }
      profile.mutedUntil?.let { until ->
        Text(
          text = "禁言至 ${dateText(until)}",
          modifier = Modifier.padding(top = 10.dp),
          style = TextStyle(
            fontSize = Typo.note.size,
            lineHeight = Typo.note.lineHeight,
            color = colors.danger,
          ),
        )
      }
    }

    if (profile.signature != null || onEditSignature != null) {
      Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.weight(1f)) { CardTitle(":: 签名 ::") }
          if (onEditSignature != null) {
            Row(
              modifier = Modifier
                .padding(bottom = Spacing.md)
                .clip(RoundedCornerShape(Radius.xs))
                .clickable(onClickLabel = "修改签名", onClick = onEditSignature)
                .padding(vertical = 2.dp, horizontal = Spacing.sm),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              AppIcon(icon = Ng2nIcon.EDIT, tint = colors.primary, size = 16.dp)
              Text(
                text = "编辑",
                style = TextStyle(
                  fontSize = Typo.listMeta.size,
                  lineHeight = Typo.listMeta.lineHeight,
                  fontWeight = FontWeight.SemiBold,
                  color = colors.primary,
                ),
              )
            }
          }
        }
        val signature = profile.signature
        if (signature == null) {
          CardCaption("还没有签名,点「编辑」写一段。")
        } else {
          Signature(signature)
        }
      }
    }

    if (profile.adminForums.isNotEmpty()) {
      Card {
        CardTitle(":: 管理权限 ::")
        CardCaption("在以下版面担任版主")
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(7.dp),
          verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
          for (forum in profile.adminForums) {
            Text(
              text = forum.name,
              modifier = Modifier
                .clip(RoundedCornerShape(Radius.xs))
                .background(colors.primaryContainer)
                .padding(vertical = 6.dp, horizontal = 11.dp),
              style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
              ),
            )
          }
        }
      }
    }

    if (profile.reputations.isNotEmpty()) {
      val max = profile.reputations.maxOf { abs(it.value) }
      Card {
        CardTitle(":: 声望 ::")
        CardCaption("表示与 论坛/某版面/某用户 的关系")
        for (entry in profile.reputations) {
          val tint = if (entry.value < 0) colors.danger else colors.primary
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
          ) {
            Text(
              text = entry.name,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
              style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = colors.fg),
            )
            Box(
              Modifier
                .width(REPUTATION_BAR_WIDTH)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.track),
            ) {
              Box(
                Modifier
                  .fillMaxWidth(if (max == 0L) 0f else abs(entry.value).toFloat() / max)
                  .height(6.dp)
                  .background(tint),
              )
            }
            Text(
              text = if (entry.value > 0) "+${entry.value}" else entry.value.toString(),
              textAlign = TextAlign.End,
              modifier = Modifier.width(34.dp),
              style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun Signature(bbcode: String) {
  val colors = LocalNg2nColors.current
  val model by produceState<FloorRenderModel?>(null, bbcode, colors) {
    value = withContext(Dispatchers.Default) {
      RenderModelBuilder.build(
        parseBBCode(bbcode),
        BBCodeRenderOptions(
          attachBase = ATTACH_BASE_FALLBACK,
          colors = colors,
          bodyFontSize = SIGNATURE_FONT_SIZE,
          bodyLineHeight = SIGNATURE_LINE_HEIGHT,
        ),
      )
    }
  }
  Box(
    Modifier
      .fillMaxWidth()
      .border(1.5.dp, colors.track, RoundedCornerShape(Radius.xs))
      .padding(vertical = 11.dp, horizontal = Spacing.md),
  ) {
    model?.let { BBCodeContent(model = it) }
  }
}

private const val SIGNATURE_FONT_SIZE = 13.5f
private const val SIGNATURE_LINE_HEIGHT = 1.7f

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  val colors = LocalNg2nColors.current
  Column(
    Modifier
      .fillMaxWidth()
      .shadow(Elevation.level1, RoundedCornerShape(Radius.lg))
      .clip(RoundedCornerShape(Radius.lg))
      .background(colors.surface)
      .padding(Spacing.lg),
    content = content,
  )
}

@Composable
private fun CardTitle(text: String) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier.padding(bottom = Spacing.md),
    style = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = colors.accent),
  )
}

@Composable
private fun CardCaption(text: String) {
  val colors = LocalNg2nColors.current
  Text(
    text = text,
    modifier = Modifier.padding(bottom = 10.dp),
    style = TextStyle(
      fontSize = Typo.listMeta.size,
      lineHeight = Typo.listMeta.lineHeight,
      color = colors.meta,
    ),
  )
}
