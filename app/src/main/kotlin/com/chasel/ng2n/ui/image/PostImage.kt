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

  val gateFlow = remember(pipeline) {
    pipeline.settings.combine(pipeline.metered) { settings, metered -> settings to metered }
  }
  val gate by gateFlow.collectAsStateWithLifecycle(
    initialValue = pipeline.settings.value to pipeline.metered.value,
  )
  val (settings, metered) = gate

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

  val revision by pipeline.sizeCache.revision.collectAsStateWithLifecycle()
  val natural = remember(source, loaded, revision) {
    loaded?.takeIf { it.first == source }?.second ?: pipeline.sizeCache.sizeOf(source)
  }

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
        if (natural?.width == width && natural.height == height) return@AsyncImage
        loaded = source to ImageSize(width, height)
      },
      onError = { failedUrl = source },
    )
  }
}

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
