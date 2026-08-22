package com.chasel.ng2n.ui.topic

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.chasel.ng2n.core.api.FloorAttachment
import com.chasel.ng2n.core.api.FloorClient
import com.chasel.ng2n.core.api.FloorUser
import com.chasel.ng2n.core.api.TopicSource
import com.chasel.ng2n.core.local.QuoteRef
import com.chasel.ng2n.core.local.Vote
import com.chasel.ng2n.ui.bbcode.CommentEntry
import com.chasel.ng2n.ui.bbcode.FloorRenderModel
import com.chasel.ng2n.ui.bbcode.ViewerImage
import com.chasel.ng2n.ui.theme.Ng2nColors
import kotlinx.collections.immutable.ImmutableList

/**
 * **一页楼层的渲染成品**(anzong 四原则的落点,`research/anzong.md`「顺的机制清单」①②)。
 *
 * anzong 把「NGA 每页 20 楼」当资源管理单位:数据一到就在后台线程把整页转成成品,
 * bind 时零计算,整页 view 常驻、页内滚动零回收零重绑。这里做同一件事,载体换成
 * 原生文本栈:
 *
 * - **后台一次性**:`parseBBCode` + 骰子复算 + 投票解析 + 贴条压平 + 图片收集 +
 *   `RenderModelBuilder.build`(AnnotatedString 组装)全在 `Dispatchers.Default` 上;
 * - **页级常驻**:整份 [PageRenderModel] 由 ViewModel 按页码持有,离开屏幕才释放;
 * - **滚动路径零计算**:下面每个字段都是「可以直接喂给 composable 的东西」,
 *   楼层卡里没有解析、没有字符串拼接、没有 URL 计算、没有正则;
 * - **屏蔽规则不在滚动路径上**(修 P3-05):命中与否由 [TopicViewModel] 在后台算成
 *   一张 `pid → FilterRule` 表,楼层卡只做一次查表。
 *
 * 全部 `@Immutable` + 不可变集合 —— Compose 的跳过判断因此是一次引用比较。
 */
@Immutable
data class PageRenderModel(
  /** 服务端回的 `__PAGE`(不是请求的页码,超范围时服务端会钳到末页) */
  val page: Int,
  val subject: String,
  val boardName: String?,
  val totalRows: Long,
  val rowsPerPage: Int,
  val totalPages: Int,
  /** 附件图片基址(`__GLOBAL._ATTACH_BASE_VIEW`),签名弹窗这类屏级组件也要用 */
  val attachBase: String,
  /** 这一页从哪条路拿到的(ADR-0002),`WEB` / `CACHE` 要出降级提示条 */
  val source: TopicSource,
  val floors: ImmutableList<FloorRenderItem>,
  /** 热门回复(服务端只在主楼里标),独立成折叠区 */
  val hotReplies: ImmutableList<FloorRenderItem>,
  /** 楼主名(只有主楼在场的那页拿得到);登记浏览历史要用 */
  val starterName: String?,
)

/** 一个楼层卡片要画的全部东西。 */
@Immutable
data class FloorRenderItem(
  val pid: Long,
  /** 楼层号,0 是主楼 */
  val lou: Long,
  /**
   * 赞踩接口要的 pid:**主楼必须传 0**(API 文档 §6),回复楼层用真 pid。
   * 本地标记也按这个值做 key,卡片与菜单读写的才是同一份状态。
   */
  val recommendPid: Long,
  /** 到 `TopicDetail.users` 里查作者用的 key(菜单里的「屏蔽此人」等要用) */
  val authorKey: String,
  val user: FloorUser?,
  val displayName: String,
  /** 匿名用户用官方给它配的那个颜色画名字;实名用主题色 */
  val nameColor: Color?,
  val isStarter: Boolean,
  val anonymous: Boolean,
  val muted: Boolean,
  val nuked: Boolean,
  val avatarUrl: String?,
  /** 头像占位底色(按用户 key 稳定取一档) */
  val avatarColor: Color,
  /** 头像占位的首字 */
  val avatarInitial: String,
  /** 「级别」,拿不到时是 `—`(与 RN 版同) */
  val levelText: String,
  /** 「威望」,已按 `formatReputation` 排过版 */
  val reputationText: String,
  val postCount: Long,
  val postedAtText: String,
  /** `alterinfo` 非空 = 被编辑过(API 文档 §3) */
  val edited: Boolean,
  val client: FloorClient,
  /** 服务端给的赞数;屏上显示的是它 + 本会话增量 */
  val score: Long,
  /** 回复楼层自带的标题(主楼的标题就是主题标题,顶栏已经有了,所以主楼这里是 null) */
  val subject: String?,
  /**
   * 正文 BBCode **原文**。
   *
   * 留着它只有一个用处:关键词屏蔽规则按原文匹配(RN 侧 `useFloorFilter` 收的就是
   * `floor.content`)。判定在数据层一次做完(修 P3-05),楼层卡不碰它。
   */
  val content: String,
  val body: FloorRenderModel,
  /** 签名档成品;「显示签名档」关着、或者这人没设签名时是 null */
  val signature: FloorRenderModel?,
  /** 贴条区(每条已压成一行纯文本) */
  val comments: ImmutableList<CommentEntry>,
  /** 投票只读模型;这一楼没有投票时是 null */
  val vote: Vote?,
  /** 附件宫格里的图片 */
  val attachmentImages: ImmutableList<FloorAttachment>,
  /** 附件里的非图片(压缩包、种子……),按「文件名 · 大小」单列 */
  val attachmentFiles: ImmutableList<FloorAttachment>,
  /** 本楼全部图片(正文 + 附件),点开查看器时当翻页列表 */
  val images: ImmutableList<ViewerImage>,
  /** 本楼正文里的引用(建回复链索引用) */
  val quoteRefs: ImmutableList<QuoteRef>,
  /** 点头像能进资料的 uid;匿名用户没有真身,是 null */
  val profileUid: Long?,
) {
  /** 附件总数(折叠条上的那个 N)。 */
  val attachmentCount: Int get() = attachmentImages.size + attachmentFiles.size
}

/**
 * 建模的样式输入。**它变了整页模型就得重建**(颜色、字号都被烤进了
 * `AnnotatedString`),所以它同时是页级缓存的 key 的一部分。
 *
 * 重建在后台,不在滚动路径上:换夜间模式 / 拖字号滑块会重建,拖列表不会。
 */
@Immutable
data class TopicRenderStyle(
  val colors: Ng2nColors,
  val bodyFontSize: Float,
  val bodyLineHeight: Float,
  val showSignature: Boolean,
)
