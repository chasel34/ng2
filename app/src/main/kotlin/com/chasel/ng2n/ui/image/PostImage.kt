package com.chasel.ng2n.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chasel.ng2n.core.local.INITIAL_IMAGE_ASPECT
import com.chasel.ng2n.core.local.ImagePlan
import com.chasel.ng2n.core.local.ImagePolicy
import com.chasel.ng2n.core.local.ImageSize
import com.chasel.ng2n.core.local.SMALL_IMAGE_WIDTH
import com.chasel.ng2n.di.ImageModule
import kotlin.math.max
import kotlinx.coroutines.flow.combine

/**
 * 正文里的 `[img]`(RN 侧原件 `src/ui/bbcode/content-image.tsx`)。
 *
 * 服务端不给图片尺寸,所以先按 4:3 占位,加载完拿到真实尺寸再改比例;量到的尺寸进
 * [com.chasel.ng2n.core.local.ImageSizeCache](内存 512 条 + 磁盘),同一张图再次上屏
 * (列表回收、翻页回来、下次启动)**首帧就是对的比例**,不再「先 4:3 再跳一下」。
 *
 * 首见图片的比例修正是**一帧内的整体重排**:RN 侧曾给图片框单挂布局动画,
 * 结果框自己滑 180ms、下方兄弟内容却瞬移,2026-08-15 录屏逐帧实测那就是「进详情闪一下」
 * 的主体。这里同样**不给尺寸变化挂任何动画**。
 *
 * 「仅 Wi-Fi 下加载图片」在计费网络下把图收成一条占位,点一下照样展开;展开后拉哪一档
 * 清晰度由「图片加载策略」决定。长图按真实比例完整显示，不裁切或折叠。
 */
@Composable
fun PostImage(
  url: String,
  modifier: Modifier = Modifier,
  thumbnailUrl: String? = null,
  onClick: ((String) -> Unit)? = null,
) {
  val pipeline = rememberImagePipeline()
  val context = LocalContext.current
  val shape = RoundedCornerShape(10.dp)

  // 设置与网络状态合成一股:两者任一变化都要重新决定拉哪一档
  val gateFlow = remember(pipeline) {
    pipeline.settings.combine(pipeline.metered) { settings, metered -> settings to metered }
  }
  val gate by gateFlow.collectAsStateWithLifecycle(
    initialValue = pipeline.settings.value to pipeline.metered.value,
  )
  val (settings, metered) = gate

  // 状态跟着地址一起记:列表回收时组件实例会被换一张图接着用,只存尺寸/失败标志的话,
  // 新的那张会顶着上一张的比例(或者上一张的「加载失败」)画
  var loaded by remember { mutableStateOf<Pair<String, ImageSize>?>(null) }
  var failedUrl by remember { mutableStateOf<String?>(null) }
  var revealed by remember(url) { mutableStateOf(false) }

  val plan = ImagePolicy.resolve(
    url = url,
    thumbnailUrl = thumbnailUrl,
    quality = settings.imageQuality,
    wifiOnly = settings.wifiOnlyImages,
    metered = metered,
    revealed = revealed,
  )

  if (plan is ImagePlan.Locked) {
    PlaceholderRow(
      text = "移动网络 · 点击显示图片",
      modifier = modifier.clickable { revealed = true },
    )
    return
  }

  val source = (plan as ImagePlan.Show).url

  if (failedUrl == source) {
    PlaceholderRow(text = "图片加载失败", modifier = modifier)
    return
  }

  // 本次挂载量到的优先,其次是以前量过的——第一次见这张图才回落到占位比例。
  // 订阅 revision 是为了「别的组件先量到了同一张图」时也能跟着更新。
  val revision by pipeline.sizeCache.revision.collectAsStateWithLifecycle()
  val natural = remember(source, loaded, revision) {
    loaded?.takeIf { it.first == source }?.second ?: pipeline.sizeCache.sizeOf(source)
  }

  // 小图按原尺寸(px 当 dp)靠左摆;大图铺满卡宽、按真实比例给高，长图完整显示。
  val small = natural != null && natural.width <= SMALL_IMAGE_WIDTH
  val frameModifier = if (natural != null && small) {
    Modifier
      .width(natural.width.dp)
      .aspectRatio(natural.width.toFloat() / max(1, natural.height).toFloat())
  } else {
    Modifier
      .fillMaxWidth()
      .aspectRatio(
        if (natural == null) INITIAL_IMAGE_ASPECT
        else natural.width.toFloat() / max(1, natural.height).toFloat(),
      )
  }

  val background = MaterialTheme.colorScheme.surfaceVariant

  Box(
    modifier = modifier
      .then(if (onClick == null) Modifier else Modifier.clickable { onClick(url) })
      .then(frameModifier)
      .clip(shape)
      .background(background),
  ) {
    AsyncImage(
      model = ImageRequest.Builder(context)
        .data(source)
        // memory + disk 都开,与 RN 侧 `cachePolicy="memory-disk"` 同:只用磁盘的话
        // 列表回收后同一张图重新上屏要再读一次盘、再解一次码,来回滚就是反复付解码钱
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(ImageModule.CROSSFADE_MS)
        .build(),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
      onSuccess = { state ->
        val width = state.result.image.width
        val height = state.result.image.height
        if (width <= 0 || height <= 0) return@AsyncImage
        pipeline.sizeCache.remember(source, ImageSize(width, height))
        // 缓存命中时首帧比例已经是对的,再置一次状态只是白多一次重组
        if (natural?.width == width && natural.height == height) return@AsyncImage
        loaded = source to ImageSize(width, height)
      },
      onError = { failedUrl = source },
    )
  }
}

/** 折叠态与「加载失败」同一个形状,只是文案不同(RN 侧同)。 */
@Composable
private fun PlaceholderRow(text: String, modifier: Modifier = Modifier) {
  val border = MaterialTheme.colorScheme.outlineVariant
  val shape = RoundedCornerShape(10.dp)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(42.dp)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .drawBehind {
        drawRoundRect(
          color = border,
          cornerRadius = CornerRadius(10.dp.toPx()),
          style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
          ),
        )
      },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
