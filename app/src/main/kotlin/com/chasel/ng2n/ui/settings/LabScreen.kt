package com.chasel.ng2n.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.net.WebFallbackMode
import com.chasel.ng2n.data.settings.NetSettings
import com.chasel.ng2n.ui.common.rememberToaster
import com.chasel.ng2n.ui.rememberAppDeps
import kotlinx.coroutines.launch

private val FALLBACK_OPTIONS = listOf(
  SettingsOption(WebFallbackMode.DISABLED, "关闭", "原生接口失败就直接报错"),
  SettingsOption(WebFallbackMode.SECONDARY, "兜底(默认)", "原生接口全垮了才去反解网页版"),
  SettingsOption(WebFallbackMode.PRIMARY, "优先", "先反解网页版,失败再走原生接口"),
  SettingsOption(WebFallbackMode.ONLY, "只用网页", "排查用:完全不走原生接口"),
)

private fun labelOf(mode: WebFallbackMode): String =
  FALLBACK_OPTIONS.first { it.value == mode }.label

private const val EXPORT_LIMIT = 50

private const val RUN_LOG_EXPORT_LIMIT = 20

internal object LabKeys {
  const val S_LAB = "s-lab"
  const val FALLBACK = "fallback"
  const val WP_UA = "wp-ua"
  const val S_DIAG = "s-diag"
  const val COMBOS = "combos"
  const val EXPORT = "export"

  val all: List<String> = listOf(S_LAB, FALLBACK, WP_UA, S_DIAG, COMBOS, EXPORT) + SETTINGS_TAIL_KEY
}

@Composable
fun LabScreen(onBack: () -> Unit) {
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val toast = rememberToaster()

  val net: NetSettings by deps.settings.netSettings.collectAsStateWithLifecycle(NetSettings())
  var fallbackOpen by remember { mutableStateOf(false) }

  val version = remember(context) { versionLabel(context) }

  val combos = deps.ngaClient.successfulCombos()
  val comboSummary =
    if (combos.isEmpty()) "还没有成功的请求"
    else combos.joinToString(" · ") { (key, record) ->
      "$key: ${record.combo.format.wire} @ ${record.combo.host}"
    }

  fun shareRunLog() {
    val runLog = deps.diagnostics.runLog.take(RUN_LOG_EXPORT_LIMIT)
    val lines = buildList {
      add("ng2n $version · 本次运行")
      add("【当前组合】")
      add(if (combos.isEmpty()) "(还没有成功的请求)" else comboSummary)
      add("【最近 ${runLog.size} 个请求】")
      runLog.forEach { entry ->
        val query = entry.params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val target = if (query.isEmpty()) entry.path else "${entry.path}?$query"
        val time = runLogClock(entry.at)
        add("$time ${if (entry.ok) "成功" else "失败"} $target (${entry.attempts} 次尝试) ${entry.message}")
      }
    }
    share(context, "本次运行", lines.joinToString("\n"), toast)
  }

  fun exportLog() {
    scope.launch {
      val log = deps.diagnostics.currentLog()
      if (log.isEmpty()) {
        toast("还没有诊断日志——反封锁链整条失败过才会攒")
        return@launch
      }
      val recent = log.takeLast(EXPORT_LIMIT)
      val header = "ng2n $version · 诊断日志 ${recent.size}/${log.size} 条"
      share(context, "导出诊断日志", (listOf(header) + recent).joinToString("\n\n"), toast)
    }
  }

  SettingsShell(
    title = "实验室与诊断",
    onBack = onBack,
    overlays = {
      SettingsOptionDialog(
        open = fallbackOpen,
        title = "网页数据源兜底",
        options = FALLBACK_OPTIONS,
        value = net.webFallbackMode,
        hint = "原生接口被封时,从网页版 HTML 里反解出同样的数据。改的是它在反封锁链上的位置。",
        onCancel = { fallbackOpen = false },
        onConfirm = { mode ->
          fallbackOpen = false
          scope.launch { deps.settings.setWebFallbackMode(mode) }
        },
      )
    },
  ) {
    item(LabKeys.S_LAB) { SettingsSection("实验室") }

    item(LabKeys.FALLBACK) {
      SettingsNavRow(label = "网页数据源兜底", sub = labelOf(net.webFallbackMode)) { fallbackOpen = true }
    }
    item(LabKeys.WP_UA) {
      SettingsSwitchRow(
        label = "帖子接口使用 Windows Phone UA",
        sub = "实测更不容易被封;被封表现变了可以关掉试试",
        value = net.readPhpWindowsPhoneUa,
        onChange = { next -> scope.launch { deps.settings.setReadPhpWindowsPhoneUa(next) } },
      )
    }

    item(LabKeys.S_DIAG) { SettingsSection("诊断") }

    item(LabKeys.COMBOS) {
      SettingsNavRow(label = "本次运行的组合", sub = comboSummary) { shareRunLog() }
    }
    item(LabKeys.EXPORT) {
      SettingsNavRow(label = "导出诊断日志", sub = "最近 $EXPORT_LIMIT 条") { exportLog() }
    }
  }
}

private fun versionLabel(context: Context): String = runCatching {
  val info = context.packageManager.getPackageInfo(context.packageName, 0)
  val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
  "${info.versionName} ($code)"
}.getOrDefault("未知版本")

private fun share(context: Context, title: String, text: String, toast: (String) -> Unit) {
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, title)
    putExtra(Intent.EXTRA_TEXT, text)
  }
  runCatching { context.startActivity(Intent.createChooser(intent, title)) }
    .onFailure { toast("分享面板没打开") }
}
