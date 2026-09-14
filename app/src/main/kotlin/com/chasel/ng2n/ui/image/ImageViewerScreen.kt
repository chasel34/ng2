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

@Serializable
data class ImageViewerKey(
  val urls: List<String>,
  val index: Int = 0,
  val thumbnailUrls: List<String> = emptyList(),
) : NavKey {
  fun thumbnailAt(position: Int): String? =
    thumbnailUrls.getOrNull(position)?.takeIf { it.isNotEmpty() && it != urls.getOrNull(position) }
}

@Composable
fun ImageViewerScreen(
  key: ImageViewerKey,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  imageDescription: (Int) -> String? = { null },
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

  val forcedOriginal = remember { mutableListOf<Int>().toMutableStateList() }
  var menuOpen by remember { mutableStateOf(false) }
  var batchRunning by remember { mutableStateOf(false) }

  val zoom = remember { ZoomState() }
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
    imageDescription(index)?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.fg2, fontSize = 13.sp) }
    HorizontalPager(
      state = pagerState,
      beyondViewportPageCount = 1,
      userScrollEnabled = !zoomed,
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
        .apply {
          if (viewport.width > 0) {
            size(coil3.size.Dimension(viewport.width), coil3.size.Dimension.Undefined)
          }
        }
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
      CircularProgressIndicator(color = LocalNg2nColors.current.primary)
    }
  }
}

data class ViewerMenuItem(
  val label: String,
  val gapBefore: Boolean = false,
  val onClick: () -> Unit,
)

private val VIEWER_BAR_PADDING = 4.dp

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
