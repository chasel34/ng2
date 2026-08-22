package com.chasel.ng2n.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.data.settings.APPEARANCE_SLIDERS
import com.chasel.ng2n.data.settings.AppSettings
import com.chasel.ng2n.data.settings.DEFAULT_SETTINGS
import com.chasel.ng2n.data.settings.avatarSizeOf
import com.chasel.ng2n.data.settings.formatSliderValue
import com.chasel.ng2n.data.settings.sliderRatio
import com.chasel.ng2n.data.settings.sliderValueAt
import com.chasel.ng2n.ui.common.rememberToaster
import com.chasel.ng2n.ui.rememberAppDeps
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.coroutines.launch

/** 预览卡片里那位「楼主」的头像底色,取占位色板的第一档(设计稿 `#3E6B7E`)。 */
private val PREVIEW_AVATAR_COLOR = Color(0xFF3E6B7E)

private const val PREVIEW_TEXT =
  "体感消费不一直这样吗?楼主 22 年大学毕业直接进厂了,没怎么在社会上摸爬滚打。从哪个时间段开始的?"

/**
 * 字体和头像大小(设计稿 `isFontSize` 屏)—— `src/app/settings/font-size.tsx` 的移植。
 *
 * 五根滑杆改的都是同一份 `appearance`,改完立刻落 DataStore —— 所以上面那张预览卡片
 * 和详情页的真楼层看到的是同一份值,不需要「保存」这一步。
 */
@Composable
fun FontSizeScreen(onBack: () -> Unit) {
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()
  val toast = rememberToaster()
  val colors = LocalNg2nColors.current
  val settings: AppSettings by deps.settings.settings.collectAsStateWithLifecycle(DEFAULT_SETTINGS)
  val appearance = settings.appearance

  SettingsShell(
    title = "字体和头像大小",
    onBack = onBack,
    actions = {
      Text(
        text = "重置",
        modifier = Modifier
          .clickable(onClickLabel = "重置字号") {
            scope.launch {
              deps.settings.updateSettings { it.copy(appearance = DEFAULT_SETTINGS.appearance) }
              toast("已恢复默认字号")
            }
          }
          .padding(horizontal = Spacing.row, vertical = Spacing.sm),
        style = TextStyle(
          fontSize = Typo.notice.size,
          lineHeight = Typo.notice.lineHeight,
          fontWeight = FontWeight.SemiBold,
          color = colors.onTopbar,
        ),
      )
    },
  ) {
    item("preview-title") {
      // 设计稿这屏的分组标题不带字间距
      Text(
        text = "实时预览",
        modifier = Modifier.padding(
          top = Spacing.lg,
          start = Spacing.page,
          end = Spacing.page,
          bottom = 6.dp,
        ),
        style = TextStyle(
          fontSize = Typo.caption.size,
          lineHeight = Typo.caption.lineHeight,
          fontWeight = FontWeight.Bold,
          color = colors.primary,
        ),
      )
    }

    item("preview-card") {
      val avatar = avatarSizeOf(appearance.avatarScale).dp
      Column(
        Modifier
          .padding(horizontal = Spacing.md)
          .clip(RoundedCornerShape(Radius.lg))
          .background(colors.surface)
          .border(1.dp, colors.divider, RoundedCornerShape(Radius.lg))
          .padding(Spacing.row),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
          // 设计稿这张预览卡的头像是 14 圆角的方块,不是楼层里那种正圆
          Box(
            Modifier
              .size(avatar)
              .clip(RoundedCornerShape(Radius.lg))
              .background(PREVIEW_AVATAR_COLOR),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "阴",
              style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary),
            )
          }
          Column(Modifier.weight(1f)) {
            Text(
              text = "阴阳师妄想(楼主)",
              style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.primary),
            )
            Text(
              text = "级别: 学徒　威望: 1.0　发帖: 5075　[0 楼]",
              modifier = Modifier.padding(top = Spacing.xs),
              style = TextStyle(
                fontSize = Typo.meta.size,
                lineHeight = Typo.meta.lineHeight,
                color = colors.meta,
              ),
            )
          }
        }
        Text(
          text = PREVIEW_TEXT,
          modifier = Modifier.padding(top = 11.dp),
          style = TextStyle(
            fontSize = appearance.bodyFontSize.toFloat().sp,
            lineHeight = (appearance.bodyFontSize * appearance.bodyLineHeight).toFloat().sp,
            color = colors.fg,
          ),
        )
      }
    }

    items(APPEARANCE_SLIDERS.size, key = { APPEARANCE_SLIDERS[it].key }) { index ->
      val spec = APPEARANCE_SLIDERS[index]
      val value = spec.get(appearance)
      SettingsSlider(
        label = spec.label,
        text = formatSliderValue(spec, value),
        ratio = sliderRatio(spec, value).toFloat(),
        onSlide = { ratio ->
          scope.launch { deps.settings.setAppearance(spec, sliderValueAt(spec, ratio.toDouble())) }
        },
        onStep = { direction ->
          scope.launch { deps.settings.setAppearance(spec, value + direction * spec.step) }
        },
      )
    }
  }
}
