package com.chasel.ng2n.ui.bbcode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.chasel.ng2n.core.bbcode.parseBBCode
import com.chasel.ng2n.ui.image.ImageViewerKey
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Radius
import com.chasel.ng2n.ui.theme.Spacing
import com.chasel.ng2n.ui.theme.Typo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * **TODO(票 16 移除)**:BBCode 渲染器的模拟器手验入口(票 11)。
 *
 * 三档:
 * 1. **29 类型**——金样本 `bbcode/coverage-*` 的 29 条 input 逐条一块,与
 *    `src/ui/bbcode/render.tsx` 的分支逐条肉眼对照(票 11 验收项 ①);
 * 2. **拼成一楼**——`coverage-all-joined` 那一条,看各种块摞在一起的间距;
 * 3. **超长楼层**——上面那条重复 30 遍(≈2 万字、900 段),外加金样本里最长的那条
 *    `deep-nesting-5000`(5000 层 `[b]`,解析器 64 层封顶后退化成文本)。
 *    用来甩着看有没有崩、有没有明显卡死(票 11 验收项 ②;**性能不在这里裁**,
 *    模拟器与 debug 包永不裁性能,正式闸在票 19)。
 *
 * 票 16 铺真首页时连同本文件一起删掉。
 */
@Serializable
data object BBCodeDemoKey : NavKey

/** 模拟器 / uiautomator 找入口用的锚点。 */
const val BBCODE_DEMO_BUTTON_TAG: String = "ng2n-bbcode-demo"
const val BBCODE_DEMO_LIST_TAG: String = "ng2n-bbcode-demo-list"

private enum class DemoMode(val label: String, val tag: String) {
  TYPES("29 类型", "ng2n-demo-types"),
  JOINED("拼成一楼", "ng2n-demo-joined"),
  LONG("超长楼层", "ng2n-demo-long"),
}

@Composable
fun BBCodeDemoScreen(onOpenViewer: (ImageViewerKey) -> Unit) {
  val colors = LocalNg2nColors.current
  var mode by remember { mutableStateOf(DemoMode.TYPES) }
  val uriHandler = LocalUriHandler.current

  // demo 里的图片地址是假的(附件域名 + 编出来的路径),点开查看器只为验证接线通;
  // 真实图片在票 12 的 demo 里看
  val callbacks = remember(onOpenViewer) {
    BBCodeCallbacks(
      onOpenLink = { runCatching { uriHandler.openUri(it) } },
      onOpenUser = { },
      onOpenTopic = { },
      onOpenFloor = { },
      onOpenMention = { },
      onOpenImage = { url -> onOpenViewer(ImageViewerKey(urls = listOf(url), index = 0)) },
      onOpenExternal = { runCatching { uriHandler.openUri(it) } },
      onOpenChain = { },
    )
  }

  Column(modifier = Modifier.fillMaxSize().background(colors.bg)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.page, vertical = Spacing.sm),
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      for (entry in DemoMode.entries) {
        val selected = entry == mode
        Text(
          text = entry.label,
          fontSize = Typo.listMeta.size,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
          color = if (selected) colors.onPrimary else colors.fg2,
          modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(if (selected) colors.primary else colors.surface2)
            .clickable { mode = entry }
            .semantics { contentDescription = entry.tag }
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        )
      }
    }

    when (mode) {
      DemoMode.TYPES -> TypesDemo(callbacks)
      DemoMode.JOINED -> SingleFloorDemo(DEMO_JOINED, callbacks)
      DemoMode.LONG -> LongFloorDemo(callbacks)
    }
  }
}

/** 29 条 input 各占一行卡片,行首标着类型名。 */
@Composable
private fun TypesDemo(callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val options = demoOptions()
  val entries = remember(options) {
    DEMO_SAMPLES.map { (type, source) ->
      type to RenderModelBuilder.build(parseBBCode(source), options)
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .semantics { contentDescription = BBCODE_DEMO_LIST_TAG },
    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.page),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    items(entries, key = { it.first }) { (type, model) ->
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Radius.lg))
          .background(colors.surface)
          .padding(Spacing.md),
      ) {
        Text(
          text = type,
          fontSize = Typo.caption.size,
          fontWeight = FontWeight.Bold,
          color = colors.accent,
        )
        BBCodeContent(model = model, callbacks = callbacks)
      }
    }
  }
}

/** 一整段 BBCode 当成一楼画。 */
@Composable
private fun SingleFloorDemo(source: String, callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val options = demoOptions()
  // 建模丢后台:这正是票 13 该有的用法,demo 顺手把这条路走通
  val model by produceState(initialValue = EMPTY_MODEL, source, options) {
    value = withContext(Dispatchers.Default) {
      RenderModelBuilder.build(parseBBCode(source), options)
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .semantics { contentDescription = BBCODE_DEMO_LIST_TAG },
    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.page),
  ) {
    item {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Radius.lg))
          .background(colors.surface)
          .padding(Spacing.md),
      ) {
        BBCodeContent(model = model, callbacks = callbacks)
      }
    }
  }
}

/**
 * 超长楼层:`coverage-all-joined` 重复 30 遍,再加一条 5000 层嵌套。
 *
 * 一楼一张卡,和真实楼层流一个形状(票 13 会换成真的 `FloorCard`)。
 */
@Composable
private fun LongFloorDemo(callbacks: BBCodeCallbacks) {
  val colors = LocalNg2nColors.current
  val options = demoOptions()
  val models by produceState(initialValue = persistentListOf<FloorRenderModel>(), options) {
    value = withContext(Dispatchers.Default) {
      val long = List(LONG_FLOOR_REPEATS) { DEMO_JOINED }.joinToString("<br/>")
      listOf(long, DEEP_NESTING_5000)
        .map { source -> RenderModelBuilder.build(parseBBCode(source), options) }
        .toPersistentList()
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .semantics { contentDescription = BBCODE_DEMO_LIST_TAG },
    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.page),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    items(models.size) { index ->
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Radius.lg))
          .background(colors.surface)
          .padding(Spacing.md),
      ) {
        BBCodeContent(model = models[index], callbacks = callbacks)
      }
    }
    if (models.isEmpty()) {
      item {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Text(text = "建模中…", color = colors.meta)
        }
      }
    }
  }
}

@Composable
private fun demoOptions(): BBCodeRenderOptions {
  val colors = LocalNg2nColors.current
  return remember(colors) {
    BBCodeRenderOptions(
      attachBase = "https://img.nga.cn/attachments",
      // 让 [noimg] 有日期目录可补(2026-08-07 12:00 UTC+8)
      postedAt = 1786075200L,
      // 骰子点数归票 10;demo 里给一颗假的,好看清结果卡长什么样
      dice = persistentListOf(
        DiceOutcome(
          expression = "1d100",
          terms = persistentListOf(DiceTerm.Roll(faces = 100, value = 37)),
          sum = 37,
        ),
      ),
      colors = colors,
    )
  }
}

private val EMPTY_MODEL = FloorRenderModel(persistentListOf())

private const val LONG_FLOOR_REPEATS = 30

/** 与金样本 `bbcode/deep-nesting-5000` 同形:64 层封顶之后余下的开标签退化成文本。 */
private val DEEP_NESTING_5000: String =
  "[b]".repeat(5000) + "深" + "[/b]".repeat(5000)

/** 29 条 input,与金样本 `bbcode/coverage-*` 逐条相同。 */
private val DEMO_SAMPLES: List<Pair<String, String>> = listOf(
  "text" to "一段字",
  "linebreak" to "上<br/>下",
  "bold" to "[b]粗[/b]",
  "italic" to "[i]斜[/i]",
  "underline" to "[u]下划线[/u]",
  "strike" to "[del]删除线[/del]",
  "color" to "[color=red]红[/color]",
  "size" to "[size=120%]大[/size]",
  "font" to "[font=宋体]宋体[/font]",
  "code" to "[code]const a = 1[/code]",
  "link" to "[url=https://example.test]站外[/url]",
  "userRef" to "[uid=123]某人[/uid]",
  "topicRef" to "[tid]45150945[/tid]",
  "floorRef" to "[pid=1,2,3]Reply[/pid]",
  "mention" to "[@某人]",
  "smiley" to "[s:ac:blink]",
  "quote" to "[quote]引用[/quote]",
  "image" to "[img]./mon_202608/07/a.jpg[/img]",
  "divider" to "======",
  "heading" to "===标题===",
  "align" to "[align=center]居中[/align]",
  "collapse" to "[collapse=提要]藏起来的话[/collapse]",
  "list" to "[list][*]甲[*]乙[/list]",
  "table" to "[table][tr][td]甲[/td][td]乙[/td][/tr][/table]",
  "box" to "[lessernuke]处罚说明[/lessernuke]",
  "dice" to "[dice]1d100[/dice]",
  "flash" to "[flash=video]./a.mp4[/flash]",
  "attach" to "[attach]./a.zip[/attach]",
  "album" to "[album=相册][img]./a.jpg[/img][img]./b.jpg[/img][/album]",
  // 上面 29 条是覆盖清单本身;下面几条是渲染器**特有**的分支,清单里没有
  "防剧透 [color=white]" to "答案是:[color=white]42[/color](点一下白字)",
  "Reply to 回复头" to
    "[b]Reply to [pid=879039681,47406116,1]Reply[/pid] Post by [uid=64858574]某人[/uid] " +
    "(2026-08-20 15:13)[/b]<br/>也就治治马保国了",
  "宽表格(横滑)" to
    "[table][tr][td]甲[/td][td]乙[/td][td]丙[/td][td]丁[/td][td]戊[/td][td]己[/td][/tr]" +
    "[tr][td]1[/td][td]2[/td][td]3[/td][td]4[/td][td]5[/td][/tr][/table]",
  "行内标签裹图片" to "[b][color=red][img]./mon_202608/07/a.jpg[/img][/color][/b]",
)

/** `coverage-all-joined`:29 条用 `<br/>` 拼成一段。 */
private val DEMO_JOINED: String = DEMO_SAMPLES.take(29).joinToString("<br/>") { it.second }
