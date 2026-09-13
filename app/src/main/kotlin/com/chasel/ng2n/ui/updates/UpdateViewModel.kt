package com.chasel.ng2n.ui.updates

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chasel.ng2n.BuildConfig
import com.chasel.ng2n.core.updates.ReleaseUpdate
import com.chasel.ng2n.data.updates.GithubReleaseClient
import com.chasel.ng2n.data.updates.SavedDownload
import com.chasel.ng2n.data.updates.UpdateDownloadStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class UpdatePhase { CHECKING, CURRENT, FOUND, DOWNLOADING, VERIFYING, READY, ERROR, DEVELOPMENT }

data class UpdateState(
  val visible: Boolean = false,
  val phase: UpdatePhase = UpdatePhase.CHECKING,
  val update: ReleaseUpdate? = null,
  val fraction: Float = 0f,
  val waiting: Boolean = false,
  val error: String = "",
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
  private val github: GithubReleaseClient,
  private val downloads: UpdateDownloadStore,
) : ViewModel() {
  private val mutable = MutableStateFlow(UpdateState())
  val state = mutable.asStateFlow()
  private var job: Job? = null
  private var saved: SavedDownload? = null

  fun dismiss() { mutable.value = mutable.value.copy(visible = false) }

  fun open() {
    mutable.value = mutable.value.copy(visible = true)
    if (job?.isActive == true) return
    if (mutable.value.phase == UpdatePhase.READY && saved != null) return
    check()
  }

  fun check() = work {
    mutable.value = UpdateState(visible = true)
    if (BuildConfig.APPLICATION_ID != "com.chasel.ng2") {
      mutable.value = mutable.value.copy(phase = UpdatePhase.DEVELOPMENT)
      return@work
    }
    saved = downloads.restore()
    saved?.let {
      if (it.update.manifest.versionCode > BuildConfig.VERSION_CODE) {
        monitor(it)
        return@work
      }
      downloads.cancel(it)
      saved = null
    }
    val update = github.check(BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID)
    mutable.value = mutable.value.copy(phase = if (update == null) UpdatePhase.CURRENT else UpdatePhase.FOUND, update = update)
  }

  fun download() = work {
    val update = mutable.value.update ?: return@work
    mutable.value = mutable.value.copy(phase = UpdatePhase.DOWNLOADING, fraction = 0f, waiting = true)
    val task = downloads.start(update)
    saved = task
    monitor(task)
  }

  fun cancel() {
    job?.cancel()
    work {
      saved?.let { downloads.cancel(it) }
      saved = null
      mutable.value = mutable.value.copy(phase = UpdatePhase.FOUND)
    }
  }

  fun install(onVerified: (Uri) -> Unit) = work {
    val task = saved ?: return@work
    mutable.value = mutable.value.copy(phase = UpdatePhase.VERIFYING)
    val uri = downloads.verifiedUri(task)
    mutable.value = mutable.value.copy(phase = UpdatePhase.READY)
    onVerified(uri)
  }

  fun installError() {
    mutable.value = mutable.value.copy(phase = UpdatePhase.ERROR, error = "无法打开安装界面，请重试")
  }

  private suspend fun monitor(task: SavedDownload) {
    mutable.value = mutable.value.copy(phase = UpdatePhase.DOWNLOADING, update = task.update)
    while (true) {
      val progress = downloads.progress(task)
      mutable.value = mutable.value.copy(
        fraction = (progress.bytes.toFloat() / progress.total).coerceIn(0f, 1f), waiting = progress.waiting,
      )
      if (progress.complete) break
      delay(500)
    }
    mutable.value = mutable.value.copy(phase = UpdatePhase.VERIFYING)
    downloads.verifiedUri(task)
    mutable.value = mutable.value.copy(phase = UpdatePhase.READY)
  }

  private fun work(block: suspend () -> Unit) {
    if (job?.isActive == true) return
    job = viewModelScope.launch {
      try { block() } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) {
        mutable.value = mutable.value.copy(phase = UpdatePhase.ERROR, error = error.message ?: "更新失败，请重试")
      }
    }
  }
}
