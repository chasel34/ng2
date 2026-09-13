package com.chasel.ng2n.ui.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.ui.theme.LocalNg2nColors

@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  if (!state.visible) return
  val context = LocalContext.current
  val colors = LocalNg2nColors.current
  val title = when (state.phase) {
    UpdatePhase.CHECKING -> "正在检查更新"
    UpdatePhase.CURRENT -> "已是最新版本"
    UpdatePhase.FOUND -> "发现新版本 ${state.update?.manifest?.versionName}"
    UpdatePhase.DOWNLOADING -> "正在下载更新"
    UpdatePhase.VERIFYING -> "正在校验安装包"
    UpdatePhase.READY -> "更新已下载"
    UpdatePhase.ERROR -> "更新失败"
    UpdatePhase.DEVELOPMENT -> "开发版本"
  }
  AlertDialog(
    onDismissRequest = viewModel::dismiss,
    containerColor = colors.surface2,
    titleContentColor = colors.fg,
    textContentColor = colors.fg2,
    title = { Text(title) },
    text = {
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.phase) {
          UpdatePhase.CHECKING, UpdatePhase.VERIFYING -> LinearProgressIndicator()
          UpdatePhase.CURRENT -> Text("当前已安装最新的正式版本。")
          UpdatePhase.FOUND -> {
            Text("安装包 %.1f MB".format((state.update?.manifest?.size ?: 0) / 1_000_000.0))
            Text(state.update?.notes?.take(12000)?.ifBlank { "暂无更新说明" } ?: "暂无更新说明")
          }
          UpdatePhase.DOWNLOADING -> {
            LinearProgressIndicator(progress = { state.fraction })
            Text(if (state.waiting) "等待网络或系统下载服务…" else "已下载 ${(state.fraction * 100).toInt()}%")
            Text("关闭后仍会在后台继续下载，返回检查更新可查看进度。")
          }
          UpdatePhase.READY -> Text("${state.update?.manifest?.versionName} 已就绪。覆盖安装会保留本地数据，请勿卸载旧版。首次安装需允许 NG2 安装应用，授权后返回此处继续。")
          UpdatePhase.ERROR -> Text(state.error)
          UpdatePhase.DEVELOPMENT -> Text("开发包与正式包数据独立，请在正式版中检查更新。")
        }
      }
    },
    confirmButton = {
      when (state.phase) {
        UpdatePhase.FOUND -> TextButton(onClick = viewModel::download) { Text("下载更新") }
        UpdatePhase.READY -> TextButton(onClick = {
          runCatching {
            if (!context.packageManager.canRequestPackageInstalls()) {
              context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } else {
              viewModel.install { uri ->
                runCatching {
                  context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { viewModel.installError() }
              }
            }
          }.onFailure { viewModel.installError() }
        }) { Text("安装更新") }
        UpdatePhase.ERROR -> TextButton(onClick = { if (state.update != null) viewModel.download() else viewModel.check() }) { Text("重试") }
        UpdatePhase.DOWNLOADING -> TextButton(onClick = viewModel::cancel) { Text("取消下载") }
        else -> TextButton(onClick = viewModel::dismiss) { Text("关闭") }
      }
    },
    dismissButton = {
      if (state.phase in listOf(UpdatePhase.FOUND, UpdatePhase.READY, UpdatePhase.ERROR, UpdatePhase.DOWNLOADING)) {
        TextButton(onClick = viewModel::dismiss) { Text(if (state.phase == UpdatePhase.DOWNLOADING) "后台下载" else "稍后") }
      }
    },
  )
}
