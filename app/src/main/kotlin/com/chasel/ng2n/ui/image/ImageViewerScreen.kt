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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.chasel.ng2n.ui.common.TopBar
import com.chasel.ng2n.ui.common.TopBarTitle
import com.chasel.ng2n.ui.common.TopBarTitleVariant
import com.chasel.ng2n.ui.theme.LocalNg2nColors
import com.chasel.ng2n.ui.theme.Ng2nColors
import com.chasel.ng2n.ui.theme.Typo
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
 *
 * **配色(票 44)**:页面底 `bg`、顶栏 `topbar` + `onTopbar`,跟主题风格与夜间档走。
 * 这一屏一度是「纯黑看图态」,但 RN 侧 HEAD 从来不是那样
 * (`src/app/image-viewer.tsx` 的 `root.backgroundColor: theme.colors.bg`),
 * 纯黑是偏离不是设计。顶栏也从这屏自己那份换成全 app 那套 [TopBar]:
 * 高度 54(原来 52)、安全区与底色都由它统一撑。
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

  val colors = LocalNg2nColors.current

  if (key.urls.isEmpty()) {
    ViewerRoot(colors = colors, modifier = modifier) {
      TopBar(paddingHorizontal = VIEWER_BAR_PADDING) {
        IconSlot(size = 46.dp, onClick = onBack, description = "返回") {
          BackIcon(colors.onTopbar, 24.dp)
        }
        TopBarTitle(text = "图片", variant = TopBarTitleVariant.SUB)
      }
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
          text = "没有可查看的图片",
          textAlign = TextAlign.Center,
          style = TextStyle(
            fontSize = Typo.notice.size,
            lineHeight = Typo.notice.lineHeight,
            color = colors.fg2,
          ),
        )
      }
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

  ViewerRoot(colors = colors, modifier = modifier) {
    ViewerTopBar(
      colors = colors,
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
    )
    HorizontalPager(
      state = pagerState,
      // 只挂当前页与两侧邻页,几十张的楼不至于一进来全拉原图(RN 侧 ±1 判断同)
      beyondViewportPageCount = 1,
      // 放大之后单指拖的是图不是页:这条与手势里的分流是同一件事的两半
      userScrollEnabled = !zoomed,
      // 顶栏在流里(RN 侧同),图占的是顶栏**下面**那块 —— Column 里给非加权子项的
      // 竖向约束是无界的,这里必须 weight 而不是 fillMaxSize,否则量不出高度
      modifier = Modifier.weight(1f).fillMaxWidth(),
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
  var imageAspect by remember(url) { mutableStateOf(0f) }
  var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
  LaunchedEffect(zoom, imageAspect, viewport) {
    zoom?.let {
      it.containerSize = viewport
      it.aspect = imageAspect
      it.reset(animated = false)
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .clipToBounds()
      .onSizeChanged { viewport = it }
      .then(
        if (zoom == null) Modifier else Modifier
          .pointerInput(zoom) {
            detectTapGestures(onDoubleTap = { tap -> scope.launch { zoom.toggleDoubleTap(tap) } })
          }
          .pointerInput(zoom) {
            detectViewerTransform(
              isZoomed = { zoom.zoomed },
              canPanVertically = { zoom.boundsFor(1f).y > 0f },
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
      val base = widthFitScale(viewport.width.toFloat(), viewport.height.toFloat(), imageAspect)
      scaleX = base * (zoom?.scale?.value ?: 1f)
      scaleY = scaleX
      translationX = zoom?.offsetX?.value ?: 0f
      translationY = zoom?.offsetY?.value
        ?: panBounds(viewport.width.toFloat(), viewport.height.toFloat(), imageAspect, 1f).y
    }

    // 原图在路上时先糊着看缩略图(通常已有磁盘/内存缓存)
    if (placeholderUrl != null && loading) {
      AsyncImage(
        model = placeholderUrl,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
          if (loading && imageAspect == 0f && state.result.image.height > 0) {
            imageAspect = state.result.image.width.toFloat() / state.result.image.height
          }
        },
        modifier = Modifier
          .fillMaxSize()
          .then(layer),
      )
    }

    AsyncImage(
      model = ImageRequest.Builder(context)
        .data(url)
        // 解码也按视口宽度取样，避免把长图先缩成细条再放大导致文字模糊。
        .apply {
          if (viewport.width > 0) {
            size(coil3.size.Dimension(viewport.width), coil3.size.Dimension.Undefined)
          }
        }
        /*
         * 这里**故意关掉内存缓存**,和正文图/头像那几处不一样。
         *
         * 查看器按屏宽解码，长图可能高于一屏。Coil 的内存缓存是整个 ImageLoader 共用的一个池,
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
        if (height > 0) imageAspect = state.result.image.width.toFloat() / height.toFloat()
      },
      onError = { loading = false },
      modifier = Modifier
        .fillMaxSize()
        .then(layer),
    )

    if (loading && placeholderUrl == null) {
      // RN 侧 `image-gallery.tsx` 给的 spinnerColor 就是 primary,不是白
      CircularProgressIndicator(color = LocalNg2nColors.current.primary)
    }
  }
}

data class ViewerMenuItem(
  val label: String,
  val gapBefore: Boolean = false,
  val onClick: () -> Unit,
)

/** 设计稿给这一屏的顶栏内距(RN 侧 `paddingHorizontal={4}`)。 */
private val VIEWER_BAR_PADDING = 4.dp

/**
 * 这一屏的根:主题底 + 竖排(顶栏在流里,图占下面那块)。
 *
 * RN 侧 `image-viewer.tsx` 的 `root` 就是这样 —— 顶栏不是浮在图上的遮罩,
 * 它把可视区往下压一截。原生这边原来是「整屏黑 + 顶栏 align TopCenter 浮着」,
 * 底色与可视区两处都跟基准对不上(票 44)。
 */
@Composable
private fun ViewerRoot(
  colors: Ng2nColors,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(colors.bg),
    content = content,
  )
}

/**
 * 查看器顶栏。壳走全 app 那套 [TopBar](底色 `topbar`、高 54、自己撑安全区),
 * 里头的图标仍是本屏手画的那四枚(字形归票 40)。
 */
@Composable
private fun ViewerTopBar(
  colors: Ng2nColors,
  counter: String,
  onBack: () -> Unit,
  onSave: () -> Unit,
  onShare: () -> Unit,
  menuItems: List<ViewerMenuItem>,
  menuOpen: Boolean = false,
  onMenuOpenChange: (Boolean) -> Unit = {},
) {
  TopBar(paddingHorizontal = VIEWER_BAR_PADDING) {
    IconSlot(size = 46.dp, onClick = onBack, description = "返回") {
      BackIcon(colors.onTopbar, 24.dp)
    }
    // 设计稿:计数 18/500、左距 8、字距 .5,顶栏前景色
    Text(
      text = counter,
      color = colors.onTopbar,
      fontSize = 18.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.5.sp,
      modifier = Modifier.padding(start = 8.dp),
    )
    Spacer(Modifier.weight(1f))
    if (menuItems.isNotEmpty()) {
      IconSlot(size = 46.dp, onClick = onSave, description = "保存到相册") {
        SaveIcon(colors.onTopbar, 23.dp)
      }
      IconSlot(size = 46.dp, onClick = onShare, description = "分享") {
        ShareIcon(colors.onTopbar, 23.dp)
      }
      Box {
        IconSlot(size = 44.dp, onClick = { onMenuOpenChange(true) }, description = "更多") {
          MoreIcon(colors.onTopbar, 22.dp)
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
