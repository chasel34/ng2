package com.chasel.ng2n.ui.image

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chasel.ng2n.core.local.ImagePolicy
import com.chasel.ng2n.data.ImageSaver
import com.chasel.ng2n.di.ImageModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 查看器的进场参数(RN 侧走模块级暂存 `stageImageViewer`,原生这边直接进 Nav3 的 key)。
 *
 * @param urls 本楼的全部**原图**地址,按正文 → 附件宫格的出现顺序
 * @param index 点开的那张在列表里的下标
 * @param thumbnailUrls 与 [urls] 一一对应的缩略图地址;站外图床没有这套约定时给空串。
 *   整体为空表示这批图都没有缩略图变体。
 *
 * RN 侧不敢把几十条 URL 塞进路由参数(expo-router 会把它们序列化进导航状态还得转义),
 * 所以绕了个模块级暂存。Nav3 的 back stack 本来就是一串 `@Serializable` 的 key,
 * 没有这个顾虑 —— 顺带把「深链直开拿不到暂存」那条兜底路径也消掉了。
 */
@Serializable
data class ImageViewerKey(
  val urls: List<String>,
  val index: Int = 0,
  val thumbnailUrls: List<String> = emptyList(),
) : NavKey {
  fun thumbnailAt(position: Int): String? =
    thumbnailUrls.getOrNull(position)?.takeIf { it.isNotEmpty() && it != urls.getOrNull(position) }
}

/**
 * 大图查看器。
 *
 * 顶栏照 RN 版:返回箭头、「2 / 3」计数、保存、分享、菜单;菜单五条
 * (保存到相册 / 复制图片地址 / 查看原图 / 在浏览器中打开 /(隔一档)下载全部)。
 *
 * 手势见 [detectViewerTransform] 与 [ZoomState]:双指缩放、双击 2.5×、
 * 同一 Pan 按缩放拆「拖页 / 拖图」两路、边界回弹。
 *
 * **对 RN 版的有意偏离**:翻页交给 `HorizontalPager`,不再自己算位移阈值/速度阈值与
 * 页边回弹。`research/inventory.md` §8 就是这么记的 ——「RN 的收尾弹簧
 * stiffness 500/damping 48 是对拍原生 ViewPager 逐帧调出来的,Kotlin 用 Pager 免费获得」。
 * 图自己的边界回弹(阻尼 0.55 / 220ms)仍是手写,那一条 Pager 给不了。
 */
@Composable
fun ImageViewerScreen(
  key: ImageViewerKey,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val pipeline = rememberImagePipeline()
  val scope = rememberCoroutineScope()

  if (key.urls.isEmpty()) {
    Box(
      modifier = modifier
        .fillMaxSize()
        .background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      ViewerTopBar(
        counter = "图片",
        onBack = onBack,
        onSave = {},
        onShare = {},
        menuItems = emptyList(),
        modifier = Modifier.align(Alignment.TopCenter),
      )
      Text("没有可查看的图片", color = Color.White.copy(alpha = 0.7f))
    }
    return
  }

  val pagerState = rememberPagerState(
    initialPage = key.index.coerceIn(0, key.urls.lastIndex),
    pageCount = { key.urls.size },
  )
  val settings by pipeline.settings.collectAsStateWithLifecycle()
  val metered by pipeline.metered.collectAsStateWithLifecycle()

  // 「查看原图」点过的页(省流量档时查看器默认也只拉缩略图)
  val forcedOriginal = remember { mutableListOf<Int>().toMutableStateList() }
  var menuOpen by remember { mutableStateOf(false) }
  // 批量下载一次只跑一趟;跑着的时候再点只提示
  var batchRunning by remember { mutableStateOf(false) }

  val zoom = remember { ZoomState() }
  // 换页兜底重置缩放(翻页只能在原始大小下发生,这是防御而不是路径)
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect { zoom.reset(animated = false) }
  }

  val index = pagerState.currentPage
  val currentUrl = key.urls[index.coerceIn(0, key.urls.lastIndex)]
  val zoomed by remember { derivedStateOf { zoom.zoomed } }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    HorizontalPager(
      state = pagerState,
      // 只挂当前页与两侧邻页,几十张的楼不至于一进来全拉原图(RN 侧 ±1 判断同)
      beyondViewportPageCount = 1,
      // 放大之后单指拖的是图不是页:这条与手势里的分流是同一件事的两半
      userScrollEnabled = !zoomed,
      modifier = Modifier.fillMaxSize(),
    ) { page ->
      val plan = ImagePolicy.resolveViewer(
        url = key.urls[page],
        thumbnailUrl = key.thumbnailAt(page),
        quality = settings.imageQuality,
        metered = metered,
        forceOriginal = forcedOriginal.contains(page),
      )
      ViewerPage(
        url = plan.url,
        placeholderUrl = plan.placeholderUrl,
        zoom = if (page == index) zoom else null,
        scope = scope,
      )
    }

    ViewerTopBar(
      counter = "${index + 1} / ${key.urls.size}",
      onBack = onBack,
      onSave = { scope.launch { saveCurrent(context, pipeline.saver, currentUrl) } },
      onShare = { scope.launch { shareCurrent(context, pipeline.saver, currentUrl) } },
      menuOpen = menuOpen,
      onMenuOpenChange = { menuOpen = it },
      menuItems = listOf(
        ViewerMenuItem("保存到相册") {
          scope.launch { saveCurrent(context, pipeline.saver, currentUrl) }
        },
        ViewerMenuItem("复制图片地址") {
          val clipboard = context.getSystemService(ClipboardManager::class.java)
          clipboard?.setPrimaryClip(ClipData.newPlainText("图片地址", currentUrl))
          toast(context, "图片地址已复制")
        },
        ViewerMenuItem("查看原图") {
          val already = ImagePolicy.resolveViewer(
            url = currentUrl,
            thumbnailUrl = key.thumbnailAt(index),
            quality = settings.imageQuality,
            metered = metered,
            forceOriginal = forcedOriginal.contains(index),
          ).url == currentUrl
          if (already) {
            toast(context, "当前已是原图")
          } else {
            toast(context, "正在加载原图…")
            forcedOriginal.add(index)
          }
        },
        ViewerMenuItem("在浏览器中打开") {
          runCatching {
            context.startActivity(
              Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
          }.onFailure { toast(context, "没有能打开链接的应用") }
        },
        ViewerMenuItem("下载全部(${key.urls.size} 张)", gapBefore = true) {
          if (batchRunning) {
            toast(context, "已经在下载了,等这一批跑完")
          } else {
            batchRunning = true
            toast(context, "开始下载 ${key.urls.size} 张图片…")
            scope.launch {
              runCatching { pipeline.saver.saveAllToAlbum(key.urls) }
                .onSuccess { result -> toast(context, batchMessage(result)) }
                .onFailure { toast(context, failureMessage(it)) }
              batchRunning = false
            }
          }
        },
      ),
      modifier = Modifier.align(Alignment.TopCenter),
    )
  }
}

/** 顶栏保存钮与菜单「保存到相册」共用(RN 侧 `doDownload` 的 toast 文案)。 */
private suspend fun saveCurrent(context: Context, saver: ImageSaver, url: String) {
  toast(context, "正在保存…")
  runCatching { saver.saveToAlbum(url) }
    .onSuccess { outcome ->
      toast(
        context,
        if (outcome == ImageSaver.SaveOutcome.DUPLICATE) "这张图已经在 相册/NGA 里了"
        else "已保存到 相册/NGA",
      )
    }
    .onFailure { toast(context, failureMessage(it)) }
}

/** 系统分享分享图片文件本体;失败(下载不动)退回复制地址,总不能什么都不给。 */
private suspend fun shareCurrent(context: Context, saver: ImageSaver, url: String) {
  runCatching { context.startActivity(saver.shareIntent(url)) }
    .onFailure {
      context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("图片地址", url))
      toast(context, "图片没下载下来,已改为复制图片地址")
    }
}

private fun batchMessage(result: ImageSaver.BatchSaveResult): String {
  if (result.saved == 0 && result.failed == 0 && result.skipped > 0) {
    return "这些图都已经在 相册/NGA 里了"
  }
  val parts = mutableListOf("已保存 ${result.saved} 张到 相册/NGA")
  if (result.skipped > 0) parts += "${result.skipped} 张已在相册"
  if (result.failed > 0) parts += "${result.failed} 张失败"
  return parts.joinToString(",")
}

private fun failureMessage(cause: Throwable): String =
  cause.message?.takeIf { it.isNotBlank() } ?: "操作失败,稍后再试"

private fun toast(context: Context, text: String) {
  Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/**
 * 一页。
 *
 * `zoom == null` 表示这是邻页:不挂变换、也不吃手势 —— 与 RN 侧
 * `zoomStyle={i === index ? zoomStyle : undefined}` 同。
 */
@Composable
private fun ViewerPage(
  url: String,
  placeholderUrl: String?,
  zoom: ZoomState?,
  scope: CoroutineScope,
) {
  val context = LocalContext.current
  var loading by remember(url) { mutableStateOf(true) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp)
      .onSizeChanged { zoom?.containerSize = it }
      .then(
        if (zoom == null) Modifier else Modifier
          .pointerInput(zoom) {
            detectTapGestures(onDoubleTap = { tap -> scope.launch { zoom.toggleDoubleTap(tap) } })
          }
          .pointerInput(zoom) {
            detectViewerTransform(
              isZoomed = { zoom.zoomed },
              onStart = {},
              onGesture = { centroid, pan, zoomChange ->
                scope.launch { zoom.onGestureUpdate(centroid, pan, zoomChange) }
              },
              onEnd = { scope.launch { zoom.settle() } },
            )
          },
      ),
    contentAlignment = Alignment.Center,
  ) {
    val layer = Modifier.graphicsLayer {
      if (zoom == null) return@graphicsLayer
      scaleX = zoom.scale.value
      scaleY = zoom.scale.value
      translationX = zoom.offsetX.value
      translationY = zoom.offsetY.value
    }

    // 原图在路上时先糊着看缩略图(通常已有磁盘/内存缓存)
    if (placeholderUrl != null && loading) {
      AsyncImage(
        model = placeholderUrl,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .then(layer),
      )
    }

    AsyncImage(
      model = ImageRequest.Builder(context)
        .data(url)
        /*
         * 这里**故意关掉内存缓存**,和正文图/头像那几处不一样。
         *
         * 查看器画的是整屏原图:Fit 到全屏后一张解码位图就是屏幕像素级
         * (1080×2400×4B ≈ 10MB)。Coil 的内存缓存是整个 ImageLoader 共用的一个池,
         * 放进去几张就能把它挤空 —— 被挤掉的正是头像和缩略图,也就是我们刚决定
         * 要留在内存里的东西(RN 侧同一处决定,`image-gallery.tsx:294-313`,
         * 那边写的是 Glide 的 LruResourceCache,机理一样)。
         *
         * 换来的好处又很小:同时只挂当前页与两侧邻页,活着的三张本来就被视图持有;
         * 真正靠内存缓存省的只有「翻出 ±1 窗口再翻回来」那一次,而那一次已经有
         * 缩略图占位先糊着看,底下只是一次本地磁盘读。
         *
         * 再加上走查 P2 记的「看完 20 帖 PSS 174→289MB 不回落」,更不该往这个池子里
         * 塞整屏位图。
         */
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(ImageModule.CROSSFADE_MS)
        .build(),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      onSuccess = { state ->
        loading = false
        val height = state.result.image.height
        if (height > 0) zoom?.aspect = state.result.image.width.toFloat() / height.toFloat()
      },
      onError = { loading = false },
      modifier = Modifier
        .fillMaxSize()
        .then(layer),
    )

    if (loading && placeholderUrl == null) {
      CircularProgressIndicator(color = Color.White)
    }
  }
}

data class ViewerMenuItem(
  val label: String,
  val gapBefore: Boolean = false,
  val onClick: () -> Unit,
)

@Composable
private fun ViewerTopBar(
  counter: String,
  onBack: () -> Unit,
  onSave: () -> Unit,
  onShare: () -> Unit,
  menuItems: List<ViewerMenuItem>,
  modifier: Modifier = Modifier,
  menuOpen: Boolean = false,
  onMenuOpenChange: (Boolean) -> Unit = {},
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.statusBars)
      .height(52.dp)
      .padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconSlot(size = 46.dp, onClick = onBack, description = "返回") { BackIcon(Color.White, 24.dp) }
    Text(
      text = counter,
      color = Color.White,
      fontSize = 18.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(start = 8.dp),
    )
    Spacer(Modifier.weight(1f))
    if (menuItems.isNotEmpty()) {
      IconSlot(size = 46.dp, onClick = onSave, description = "保存到相册") {
        SaveIcon(Color.White, 23.dp)
      }
      IconSlot(size = 46.dp, onClick = onShare, description = "分享") {
        ShareIcon(Color.White, 23.dp)
      }
      Box {
        IconSlot(size = 44.dp, onClick = { onMenuOpenChange(true) }, description = "更多") {
          MoreIcon(Color.White, 22.dp)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
          for (item in menuItems) {
            if (item.gapBefore) HorizontalDivider()
            DropdownMenuItem(
              text = { Text(item.label) },
              onClick = {
                onMenuOpenChange(false)
                item.onClick()
              },
            )
          }
        }
      }
    }
  }
}

/**
 * 顶栏的一个点按区。`contentDescription` 不只是无障碍:模拟器手验靠 uiautomator
 * 按 content-desc 找钮(票 12 验收①),没有它就只能盲点坐标。
 */
@Composable
private fun IconSlot(
  size: Dp,
  onClick: () -> Unit,
  description: String,
  content: @Composable () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(size)
      .clickable(onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}
