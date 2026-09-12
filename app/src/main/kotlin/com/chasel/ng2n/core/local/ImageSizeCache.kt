package com.chasel.ng2n.core.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 正文图片的真实尺寸记忆表(票 12;RN 侧原件 `src/ui/bbcode/image-size.ts`)。
 *
 * 服务端不给图片宽高(API 文档 §3 的 `attachs` 只有 `size`/`type`,是字节数不是像素),
 * 所以只能等图片解码完才知道。问题是同一张图在一次浏览里会被反复挂载——列表回收后
 * 滚回来、翻页再翻回来、引用块里又引一遍——每次都要从 4:3 占位跳到真实比例,
 * 行高跟着变、内容跳位。记下来之后第二次起首帧就按真实比例画,不再跳。
 *
 * 磁盘持久化不是锦上添花:「首次进入帖子的比例跳变」大头在**下次启动重看同一批图**,
 * 不落盘每次冷启动都要重跳一遍。
 *
 * 纯 Kotlin(只依赖 coroutines),存储实现从 [ImageSizeStore] 注入 —— JVM 单测直接跑。
 */
data class ImageSize(val width: Int, val height: Int)

/** 记忆表的持久层。实现见 `data/FileImageSizeStore`(filesDir 下一个 JSON 文件)。 */
interface ImageSizeStore {
  /** 启动时读出上次存的全部条目;坏数据返回空表即可。 */
  suspend fun load(): List<Pair<String, ImageSize>>

  /** 全量覆盖写入(条目数被上限钳住,整包也就几十 KB,不值得做增量)。 */
  suspend fun save(entries: List<Pair<String, ImageSize>>)
}

/**
 * 上限。一条 entry 只有两个数,几百条也就几 KB;设上限只是防「一路翻几百页」
 * 这种极端情况下无限涨。到顶了丢最早的一条。
 */
const val IMAGE_SIZE_LIMIT: Int = 512

/** 图片是成批加载的,攒一秒一起写,别每张图都撞一次存储。 */
const val IMAGE_SIZE_SAVE_DEBOUNCE_MS: Long = 1000L

/**
 * 正文大图的显示比例封顶(width / height)。
 *
 * 竖长图(手机长截图)按真实比例展开会把楼层撑成一屏一张,所以铺满卡宽 W 之后
 * 高度封在 W / 0.6 ≈ 1.67W —— 比这更瘦的图会被裁掉一截。这条同时护着列表的行高估算。
 */
const val CONTENT_IMAGE_MIN_ASPECT: Float = 0.6f

/** 拿到真实尺寸之前的占位比例。取 4:3,比 16:9 更接近论坛里手机截图的常见比例。 */
const val INITIAL_IMAGE_ASPECT: Float = 4f / 3f

/**
 * 不足这个宽度(原始像素)的算小图:按原尺寸摆,不铺满卡宽——
 * 签名里 16px 的站标拉到整卡宽会糊成一片色块(RN 侧 M4 验收缺陷 E7)。
 */
const val SMALL_IMAGE_WIDTH: Int = 200

/**
 * 这张图会不会被封顶裁掉一截(=「长图」,要给用户提示的那种)。
 *
 * 判据直接绑在实际裁切点上——被裁的一张不漏,没被裁的一张不冤(角标写着
 * 「点击查看完整」,画在没裁的图上就是骗人)。RN 侧 `isLongImage` 的注释里
 * 记着为什么不用原案的「1:3」或「1.6 屏高」:那两条都比封顶本身更晚触发。
 */
fun isLongImage(size: ImageSize): Boolean {
  if (size.width <= 0 || size.height <= 0) return false
  return size.width.toFloat() / size.height.toFloat() < CONTENT_IMAGE_MIN_ASPECT
}

/**
 * 记忆表本体。
 *
 * - 读走同步接口 [sizeOf]:楼层列表估算行高、组件首帧取比例都在合成/量算路径上,
 *   不能等一个挂起函数。
 * - 变更走 [revision] 这个 [StateFlow]:内容本身是 512 条的可变表,每次写都复制一份 Map
 *   发出去太贵;订阅方只需要知道「有新尺寸了,重读一遍」。
 * - 淘汰按**插入序**(不是访问序)丢最早的一条,重复记同一张不占新坑也不挪位置——
 *   与 RN 侧 `Map` 的行为逐条对齐。
 */
class ImageSizeCache(
  private val scope: CoroutineScope,
  private val store: ImageSizeStore? = null,
  private val debounceMillis: Long = IMAGE_SIZE_SAVE_DEBOUNCE_MS,
  private val limit: Int = IMAGE_SIZE_LIMIT,
) {
  private val sizes = LinkedHashMap<String, ImageSize>()
  private val _revision = MutableStateFlow(0L)
  private var saveJob: Job? = null

  /** 每记下一条新尺寸就 +1。UI 订阅它重读 [sizeOf]。 */
  val revision: StateFlow<Long> = _revision.asStateFlow()

  /**
   * 回灌上次落盘的条目。本会话已量到的优先(它们更新)。
   * 启动时调用一次(异步 —— 冷启同步 IO 是不随迁的已知缺陷 P2-04);
   * 失败(文件损坏)由 [ImageSizeStore] 自己吞掉返回空表。
   */
  fun warmUpAsync() {
    if (store == null) return
    scope.launch { warmUp() }
  }

  /** 见 [warmUpAsync];单测直接用这个挂起版。 */
  suspend fun warmUp() {
    val loaded = store?.load() ?: return
    var added = false
    synchronized(sizes) {
      for ((uri, size) in loaded) {
        if (!sizes.containsKey(uri) && sizes.size < limit && size.width > 0 && size.height > 0) {
          sizes[uri] = size
          added = true
        }
      }
    }
    if (added) _revision.value += 1
  }

  /**
   * 查一张图的真实尺寸,没见过就是 null(调用方回落到 [INITIAL_IMAGE_ASPECT] 占位比例)。
   *
   * key 是**实际加载的那个地址**而不是原图地址:省流量档拉的是缩略图,像素尺寸
   * 跟原图不是一回事,混在一起会让「小图按原尺寸摆」的判断认错。
   */
  fun sizeOf(uri: String): ImageSize? = synchronized(sizes) { sizes[uri] }

  /** 记下一张图的真实像素尺寸。宽高有一边是 0(解码失败)的不记。 */
  fun remember(uri: String, size: ImageSize) {
    if (size.width <= 0 || size.height <= 0) return
    val changed = synchronized(sizes) {
      val previous = sizes[uri]
      if (previous == size) return@synchronized false
      if (previous == null && sizes.size >= limit) {
        val oldest = sizes.keys.firstOrNull()
        if (oldest != null) sizes.remove(oldest)
      }
      sizes[uri] = size
      true
    }
    if (!changed) return
    _revision.value += 1
    scheduleSave()
  }

  /**
   * 攒一秒写一次。窗口开着的时候再记不重排窗口(与 RN 的 `if (saveTimer !== undefined) return`
   * 逐句对齐):这是「批窗口」不是「静默期防抖」,连续加载几十张图也只写一次,
   * 而不是一直被推迟到全部加载完。
   */
  private fun scheduleSave() {
    val target = store ?: return
    if (saveJob?.isActive == true) return
    saveJob = scope.launch {
      delay(debounceMillis)
      target.save(snapshot())
    }
  }

  /** 当前全部条目(插入序)。持久层与测试用。 */
  fun snapshot(): List<Pair<String, ImageSize>> =
    synchronized(sizes) { sizes.entries.map { it.key to it.value } }

  /** 只给测试用:清空,免得用例之间互相影响。 */
  fun clear() {
    saveJob?.cancel()
    saveJob = null
    synchronized(sizes) { sizes.clear() }
    _revision.value = 0L
  }
}
