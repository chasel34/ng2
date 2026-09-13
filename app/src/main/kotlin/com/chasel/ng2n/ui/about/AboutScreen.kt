package com.chasel.ng2n.ui.about

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.chasel.ng2n.ui.updates.UpdateViewModel
import com.chasel.ng2n.ui.updates.UpdateDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarButton
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.icons.AppIcon
import com.chasel.ng2n.ui.icons.Ng2nIcon
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo

const val ABOUT_SCREEN_TAG: String = "ng2n-about-screen"

internal object AboutKeys {
  const val HEADER = "header"
  const val SOURCE = "source"
  const val UPDATE = "update"
  const val GITHUB = "github"
  const val LINKS = "links"
  const val DIAGNOSTIC = "diagnostic"
  const val LICENSES = "licenses"
  const val DISCLAIMER = "disclaimer"

  const val FOOTER = "disclaimer-footer"

  val rows: List<String> = listOf(SOURCE, UPDATE, GITHUB, LINKS, DIAGNOSTIC, LICENSES, DISCLAIMER)

  val all: List<String> = listOf(HEADER) + rows + FOOTER
}

private const val DISCLAIMER =
  "本客户端与 NGA 官方无关,仅供个人学习与自用。所有内容版权归原作者与 NGA 所有,不做任何分发。"

private const val DISCLAIMER_DETAIL =
  "本客户端是个人开发的第三方阅读工具,与 NGA(bbs.nga.cn)及其运营方没有任何关联。" +
    "所有帖子、图片、表情等内容的版权归原作者与 NGA 所有;本应用只做阅读呈现," +
    "不缓存分发任何内容,也不提供公开下载。仅供个人学习与自用。"

private val LICENSES = listOf(
  "Kotlin · AndroidX · Jetpack Compose — Apache-2.0",
  "Navigation 3 · Hilt · Room · DataStore — Apache-2.0",
  "OkHttp · Okio — Apache-2.0",
  "kotlinx.serialization · kotlinx.coroutines — Apache-2.0",
  "Coil 3 — Apache-2.0",
)

private const val DATA_SOURCE =
  "所有数据直接读 NGA 官方接口,不经任何中转服务;登录凭证只存在本机,不上传。"

private data class AboutRow(
  val key: String,
  val icon: Ng2nIcon,
  val label: String,
  val sub: String? = null,
  val detail: String? = null,
  val onClick: (() -> Unit)? = null,
)

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLab: () -> Unit, updater: UpdateViewModel = hiltViewModel()) {
  UpdateDialog(updater)
  val colors = LocalNg2nColors.current
  val context = LocalContext.current
  var expanded by remember { mutableStateOf<String?>(null) }
  val version = remember(context) { versionOf(context) }

  val rows = remember(version, context, updater, onOpenLab) {
    listOf(
      AboutRow(AboutKeys.SOURCE, Ng2nIcon.CODE, "数据来源", "直接读 NGA 官方接口", detail = DATA_SOURCE),
      AboutRow(
        key = AboutKeys.UPDATE,
        icon = Ng2nIcon.UPDATE,
        label = "检查更新",
        sub = "从 GitHub Release 获取新版本",
        onClick = { updater.open() },
      ),
      AboutRow(
        key = AboutKeys.GITHUB,
        icon = Ng2nIcon.CODE,
        label = "GitHub",
        sub = "chasel34/ng2 · 源码与版本发布",
        onClick = {
          runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/chasel34/ng2")))
          }.onFailure {
            Toast.makeText(context, "无法打开 GitHub 链接", Toast.LENGTH_SHORT).show()
          }
        },
      ),
      AboutRow(
        key = AboutKeys.LINKS,
        icon = Ng2nIcon.UPDATE,
        label = "系统设置",
        sub = "打开本应用的系统设置(权限 / 默认打开方式)",
        onClick = { openAppSettings(context) },
      ),
      AboutRow(
        key = AboutKeys.DIAGNOSTIC,
        icon = Ng2nIcon.BUG_REPORT,
        label = "诊断日志",
        sub = "接口失败的记录在「设置 · 实验室与诊断」里导出",
        onClick = onOpenLab,
      ),
      AboutRow(AboutKeys.LICENSES, Ng2nIcon.DESCRIPTION, "开源许可", "${LICENSES.size} 个第三方组件", detail = LICENSES.joinToString("\n")),
      AboutRow(AboutKeys.DISCLAIMER, Ng2nIcon.GAVEL, "免责声明", detail = DISCLAIMER_DETAIL),
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(colors.bg)
      .semantics { contentDescription = ABOUT_SCREEN_TAG },
  ) {
    TopBar(paddingHorizontal = 4.dp) {
      TopBarButton(
        icon = Ng2nIcon.ARROW_BACK,
        size = 24.dp,
        box = 46.dp,
        contentDescription = "返回",
        onClick = onBack,
      )
      TopBarTitle(text = "关于", variant = TopBarTitleVariant.SUB)
    }

    LazyColumn(Modifier.fillMaxSize()) {
      item(AboutKeys.HEADER) {
        Column(
          Modifier
            .fillMaxWidth()
            .padding(top = 34.dp, start = Spacing.xl, end = Spacing.xl, bottom = 26.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            Modifier
              .size(76.dp)
              .clip(RoundedCornerShape(Radius.dialog))
              .background(colors.primary),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "NG",
              style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary),
            )
          }
          Text(
            text = "NG2N",
            modifier = Modifier.padding(top = Spacing.row),
            style = TextStyle(
              fontSize = Typo.dialogTitle.size,
              lineHeight = Typo.dialogTitle.lineHeight,
              fontWeight = FontWeight.SemiBold,
              color = colors.fg,
            ),
          )
          Text(
            text = "v$version · 第三方客户端",
            modifier = Modifier.padding(top = 6.dp),
            style = TextStyle(
              fontSize = Typo.listMeta.size,
              lineHeight = Typo.listMeta.lineHeight,
              color = colors.meta,
            ),
          )
        }
      }

      items(rows.size, key = { rows[it].key }) { index ->
        val row = rows[index]
        val open = expanded == row.key
        Column {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable(onClickLabel = row.label) {
                val click = row.onClick
                if (click != null) click() else expanded = if (open) null else row.key
              }
              .semantics { contentDescription = row.label }
              .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
              }
              .padding(vertical = Spacing.row, horizontal = Spacing.page),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.row),
          ) {
            AppIcon(icon = row.icon, tint = colors.fg2, size = 21.dp)
            Column(Modifier.weight(1f)) {
              Text(
                text = row.label,
                style = TextStyle(
                  fontSize = Typo.drawerItem.size,
                  lineHeight = Typo.drawerItem.lineHeight,
                  color = colors.fg,
                ),
              )
              if (row.sub != null) {
                Text(
                  text = row.sub,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(top = 3.dp),
                  style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = colors.meta),
                )
              }
            }
            AppIcon(
              icon = Ng2nIcon.CHEVRON_RIGHT,
              tint = colors.meta,
              size = 20.dp,
              modifier = if (row.detail == null) Modifier else Modifier.rotate(90f),
            )
          }
          if (open && row.detail != null) {
            Text(
              text = row.detail,
              modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2)
                .drawBehind {
                  val y = size.height - 0.5.dp.toPx()
                  drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                .padding(
                  top = Spacing.md,
                  bottom = Spacing.md,
                  start = Spacing.page + 21.dp + Spacing.row,
                  end = Spacing.page,
                ),
              style = TextStyle(
                fontSize = Typo.notice.size,
                lineHeight = Typo.notice.lineHeight,
                color = colors.fg2,
              ),
            )
          }
        }
      }

      item(AboutKeys.FOOTER) {
        Text(
          text = DISCLAIMER,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
          style = TextStyle(fontSize = Typo.meta.size, lineHeight = 19.55.sp, color = colors.meta),
        )
        Spacer(Modifier.height(Spacing.sm))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
      }
    }
  }
}

private fun versionOf(context: Context): String = runCatching {
  val info = context.packageManager.getPackageInfo(context.packageName, 0)
  @Suppress("DEPRECATION")
  val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
  "${info.versionName} (build $code)"
}.getOrDefault("未知")

private fun openAppSettings(context: Context) {
  val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    .setData(Uri.fromParts("package", context.packageName, null))
  runCatching { context.startActivity(intent) }
}
