package com.chasel.ng2n.core.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImageSize(val width: Int, val height: Int)

interface ImageSizeStore {
  suspend fun load(): List<Pair<String, ImageSize>>

  suspend fun save(entries: List<Pair<String, ImageSize>>)
}

const val IMAGE_SIZE_LIMIT: Int = 512

const val IMAGE_SIZE_SAVE_DEBOUNCE_MS: Long = 1000L

const val CONTENT_IMAGE_MIN_ASPECT: Float = 0.6f

const val INITIAL_IMAGE_ASPECT: Float = 4f / 3f

const val SMALL_IMAGE_WIDTH: Int = 200

fun isLongImage(size: ImageSize): Boolean {
  if (size.width <= 0 || size.height <= 0) return false
  return size.width.toFloat() / size.height.toFloat() < CONTENT_IMAGE_MIN_ASPECT
}

class ImageSizeCache(
  private val scope: CoroutineScope,
  private val store: ImageSizeStore? = null,
  private val debounceMillis: Long = IMAGE_SIZE_SAVE_DEBOUNCE_MS,
  private val limit: Int = IMAGE_SIZE_LIMIT,
) {
  private val sizes = LinkedHashMap<String, ImageSize>()
  private val _revision = MutableStateFlow(0L)
  private var saveJob: Job? = null

  val revision: StateFlow<Long> = _revision.asStateFlow()

  fun warmUpAsync() {
    if (store == null) return
    scope.launch { warmUp() }
  }

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

  fun sizeOf(uri: String): ImageSize? = synchronized(sizes) { sizes[uri] }

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

  private fun scheduleSave() {
    val target = store ?: return
    if (saveJob?.isActive == true) return
    saveJob = scope.launch {
      delay(debounceMillis)
      target.save(snapshot())
    }
  }

  fun snapshot(): List<Pair<String, ImageSize>> =
    synchronized(sizes) { sizes.entries.map { it.key to it.value } }

  fun clear() {
    saveJob?.cancel()
    saveJob = null
    synchronized(sizes) { sizes.clear() }
    _revision.value = 0L
  }
}
