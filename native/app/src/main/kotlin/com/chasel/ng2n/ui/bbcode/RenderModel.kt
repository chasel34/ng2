package com.chasel.ng2n.ui.bbcode

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.chasel.ng2n.core.bbcode.Align
import com.chasel.ng2n.core.bbcode.BoxVariant
import kotlinx.collections.immutable.ImmutableList

/**
 * 楼层正文的**渲染成品**(ADR-0001 + anzong 四原则的落点)。
 *
 * 关键约定:这棵树是[RenderModelBuilder]在**后台线程**一次性建好的,
 * composition 只负责把它贴出来——**滚动路径零计算**。所以这里的每一项都是
 * 「可以直接喂给某个 composable 的东西」而不是「还要再算一下的原料」:
 *
 * - 文字段是组装完毕的 [AnnotatedString](样式、链接 annotation、表情占位全在里面);
 * - 图片段是拼好的绝对 URL(附件基址、日期目录、缩略图后缀都算过了);
 * - 表格段的列宽、补齐格子数都是定值;
 * - 引用/折叠/表格单元格里的正文是**嵌套的** [FloorRenderModel],同样是成品。
 *
 * 全部落成不可变集合 + `@Immutable`,Compose 的跳过判断因此是一次引用比较。
 */
@Immutable
data class FloorRenderModel(val segments: ImmutableList<RenderSegment>) {
  val isEmpty: Boolean get() = segments.isEmpty()
}

@Immutable
sealed interface RenderSegment

/**
 * 一段行内文字。
 *
 * 底样式(字号/行高/颜色)单独放在字段里而不是压进 [text] 的 span:
 * 它是**整段**的属性,`Text(style = …)` 直接吃;而 `AnnotatedString` 里只留
 * 「与底样式不同的那些区间」——粗体、颜色、链接、表情占位。与 RN 版
 * 「`<Text style={bodyStyle}>` 外层 + 嵌套 `<Text>` 覆盖」是同一个分工。
 *
 * @property text 组装好的富文本。链接/引用/@/防剧透都以 annotation 的形式钉在区间上,
 *   tag 见 [BBCodeAnnotation]。
 * @property smilies `text` 里出现过的表情占位,渲染层据此建 `inlineContent` 表。
 *   同一个表情在一段里出现多次只登记一条(id 相同)。
 * @property textAlign 外层 `[align]` 给的对齐;没有就是 null(跟随默认)。
 */
@Immutable
data class TextSegment(
  val text: AnnotatedString,
  val smilies: ImmutableList<SmileyPlacement>,
  val fontSize: TextUnit,
  val lineHeight: TextUnit,
  val color: Color,
  val textAlign: TextAlign? = null,
) : RenderSegment

/**
 * 表情内联占位。宽高不在这里定死——高度跟着「表情大小」设置走(见
 * [com.chasel.ng2n.ui.theme.TextScale]),这里只带**原始比例**,渲染层乘一下。
 *
 * @property id `AnnotatedString` 里 inlineContent 的 key,取文件名(远程的取 URL)
 * @property url 已经拼好的地址:随包的是 `file:///android_asset/smilies/…`,
 *   没随包的是 CDN 地址
 * @property aspect 宽 / 高;拿不到原始尺寸时是 1(按正方形占位,与 RN 版同)
 */
@Immutable
data class SmileyPlacement(val id: String, val url: String, val aspect: Float)

/**
 * `[quote]` 与 `Reply to` 回复头共用的引用卡片——两者都是「这一楼在回谁」,
 * 差别只在服务端有没有给容器,视觉上没道理分成两样。
 *
 * @property chain 认得出 `[pid]` 引用时才有值;调用方(票 13)再决定画不画
 *   「查看对话链(N 层)」那一行——手打的 `[quote]` 追不了链,画了也是死入口。
 */
@Immutable
data class QuoteSegment(
  val body: FloorRenderModel,
  val chain: QuoteRef? = null,
) : RenderSegment

/** `[img]` / `[noimg]`。地址已拼好,交给票 12 的 `PostImage`。 */
@Immutable
data class ImageSegment(val url: String, val thumbnailUrl: String?) : RenderSegment

/** 独占一行的 `======` 分割线。 */
@Immutable
data object DividerSegment : RenderSegment

/** `[h]` 与 `===标题===`:网页版是一条带下划线的小标题。 */
@Immutable
data class HeadingSegment(val body: FloorRenderModel) : RenderSegment

/** `[align=center]` / `[l]` / `[r]`:对齐要同时作用在容器和文字上,只给一个都不够。 */
@Immutable
data class AlignSegment(val align: Align, val body: FloorRenderModel) : RenderSegment

/** `[collapse]` / `[collapse=标题]`。默认收起,和网页版一致。 */
@Immutable
data class CollapseSegment(val title: String, val body: FloorRenderModel) : RenderSegment

/**
 * `[lessernuke]` / `[hip]` / `[item]`。
 *
 * @property notice 只有 lessernuke 有:官方 `ubbcode.lesserNuke` 按标签末尾那位数字
 *   挑的提示语。其余 variant 是 null,画成普通的一块。
 */
@Immutable
data class BoxSegment(
  val variant: BoxVariant,
  val notice: String?,
  val body: FloorRenderModel,
) : RenderSegment

/** `[list]` / `[list=1]`。`items` 已经由解析器按 `[*]` 切好。 */
@Immutable
data class ListSegment(
  val ordered: Boolean,
  val items: ImmutableList<FloorRenderModel>,
) : RenderSegment

/** `[table]`。简化排版见 [Table.kt]:固定列宽 + 整表横向滚动,`rowspan` 忽略。 */
@Immutable
data class TableSegment(val rows: ImmutableList<TableRowModel>) : RenderSegment

@Immutable
data class TableRowModel(
  val cells: ImmutableList<TableCellModel>,
  /** 行末补齐用的空格子数,免得最后一格右边缺一条竖线。 */
  val paddingCells: Int,
)

@Immutable
data class TableCellModel(val width: Dp, val body: FloorRenderModel)

/**
 * `[dice]` 的结果卡。
 *
 * @property outcome 点数要靠楼层的 authorId/tid/pid 才算得出来(票 10 的
 *   `resolveDice`);调用方没给就退回显示表达式,与 RN 版同。
 */
@Immutable
data class DiceSegment(val expression: String, val outcome: DiceOutcome?) : RenderSegment

/** `[flash]` / `[flash=video]` / `[flash=audio]`:点了交给系统播放器或浏览器。 */
@Immutable
data class MediaSegment(
  val url: String,
  /** 「视频」/「音频」/「动画」 */
  val label: String,
  val fileName: String,
) : RenderSegment

/** `[attach]`:附件本体,点了外跳下载。 */
@Immutable
data class AttachSegment(val url: String, val fileName: String) : RenderSegment

/** `[album]`:默认收起成一条「共 N 张」,展开后按正文图片的样式竖排。 */
@Immutable
data class AlbumSegment(val images: ImmutableList<ImageSegment>) : RenderSegment

/**
 * 「行内标签裹着块级内容」的展开结果(`[b][img]…[/b]` 那类)。
 *
 * RN 那边是渲染时递归并把这一层的文字样式往下带;这里已经在建模期把样式压进了
 * 内层的 [TextSegment],所以剩下的只是一个「把 children 竖着排」的容器。
 */
@Immutable
data class GroupSegment(val body: FloorRenderModel) : RenderSegment

/**
 * 富文本里的 annotation tag。渲染层按 tag 反查点到了什么。
 *
 * 用 `pushStringAnnotation` 而不是 `LinkAnnotation`:站内引用(uid/tid/pid/@)点了是
 * **导航**不是开 URL,统一走一套回调,票 13 接屏时只认这几个 tag。
 */
object BBCodeAnnotation {
  /** `[url]`;annotation 是 href。 */
  const val LINK: String = "ng2n:link"

  /** `[uid]`;annotation 是 uid。 */
  const val USER: String = "ng2n:uid"

  /** `[tid]`;annotation 是 tid。 */
  const val TOPIC: String = "ng2n:tid"

  /** `[pid]`;annotation 是逗号分隔的原始参数(`pid,tid,page`)。 */
  const val FLOOR: String = "ng2n:pid"

  /** `[@用户名]`;annotation 是用户名。 */
  const val MENTION: String = "ng2n:mention"

  /**
   * `[color=white]` 防剧透段;annotation 是这一段在本楼里的序号。
   * 点一下把这一段翻出来看(见 `BBCodeContent`)。
   */
  const val SPOILER: String = "ng2n:spoiler"
}

/**
 * 骰子结果(RN 侧 `src/core/local/dice.ts` 的 `DiceOutcome`)。
 *
 * **TODO(票 10)**:票 10 落地 `core/local/Dice.kt`(含 LCG 复算与共享随机流)后,
 * 把这三个类型删掉改 import 那一份。渲染器只消费结果,复算不在票 11 范围内。
 */
@Immutable
data class DiceOutcome(
  /** AST 里的原始表达式,原样回显(网页版的 `ROLL : <表达式>`) */
  val expression: String,
  val terms: ImmutableList<DiceTerm>,
  /** 超出 NGA 的上限时没有点数,网页版此处显示 `OUT OF LIMIT` / `ERROR` */
  val sum: Int? = null,
)

@Immutable
sealed interface DiceTerm {
  val value: Int

  /** 掷出来的一颗:`d100(37)`。 */
  data class Roll(val faces: Int, override val value: Int) : DiceTerm

  /** 表达式里的常数项:`2d6+3` 的那个 3。 */
  data class Constant(override val value: Int) : DiceTerm
}

/** `d100(37)+3` 这样的展开串(RN 侧 `formatDiceTerms`)。 */
fun formatDiceTerms(terms: List<DiceTerm>): String = terms.joinToString("+") { term ->
  when (term) {
    is DiceTerm.Roll -> "d${term.faces}(${term.value})"
    is DiceTerm.Constant -> term.value.toString()
  }
}
