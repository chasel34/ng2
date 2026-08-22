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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 网页数据源兜底的四档(ADR-0002 / API 文档 §0.8)。设计稿这行画的是开关,
 * 但落地的是四档档位,所以改成选项行 —— 按同屏其它选项行的形状延伸。
 */
private val FALLBACK_OPTIONS = listOf(
  SettingsOption(WebFallbackMode.DISABLED, "关闭", "原生接口失败就直接报错"),
  SettingsOption(WebFallbackMode.SECONDARY, "兜底(默认)", "原生接口全垮了才去反解网页版"),
  SettingsOption(WebFallbackMode.PRIMARY, "优先", "先反解网页版,失败再走原生接口"),
  SettingsOption(WebFallbackMode.ONLY, "只用网页", "排查用:完全不走原生接口"),
)

private fun labelOf(mode: WebFallbackMode): String =
  FALLBACK_OPTIONS.first { it.value == mode }.label

/** 一次分享出去的诊断条数上限。日志一条就是多行,整份几百条分享面板会塞不下。 */
private const val EXPORT_LIMIT = 50

/** 「本次运行」里分享出去的请求条数。 */
private const val RUN_LOG_EXPORT_LIMIT = 20

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

/**
 * 实验室与诊断(设置的二级页)—— `src/app/settings/lab.tsx` 的移植。
 *
 * 这一页只收「排查时才会用到」的四条:两档改反封锁链行为的开关,两个把内存里的
 * 链路状态倒出来的入口。
 *
 * ## 脱敏(P1-04,已在存储层收口)
 *
 * 导出的两份文本都来自 `data/diagnostics` —— 那一层写日志前就把白名单之外的参数
 * 换成了 `<redacted>`(`fav` 访问码、搜索词、整张屏蔽词表都在挡下之列)。
 * **这一屏不做二次拼装**:凡是要发出去的字符串都取已脱敏的那一份,
 * 免得脱敏点分散到 UI 里(一处漏一次就重新泄露一次)。
 */
@Composable
fun LabScreen(onBack: () -> Unit) {
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val toast = rememberToaster()

  val net: NetSettings by deps.settings.netSettings.collectAsStateWithLifecycle(NetSettings())
  var fallbackOpen by remember { mutableStateOf(false) }

  val version = remember(context) { versionLabel(context) }

  // 组合表活在内存里(故意不持久化),每次进这一屏现读
  val combos = deps.ngaClient.successfulCombos()
  val comboSummary =
    if (combos.isEmpty()) "还没有成功的请求"
    else combos.joinToString(" · ") { (key, record) ->
      "$key: ${record.combo.format.wire} @ ${record.combo.host}"
    }

  /**
   * 「本次运行的组合」(RN 版 2026-08-13「版块全空」排查加的)。
   *
   * 反封锁链把每个接口钉在「上次试通的格式 × 域名」上,这个状态只活在内存里,
   * 出问题时最想知道的就是它。顺带把本次运行的请求落点也分享出去:成功的请求同样在里面,
   * 「链自认为成功但拿回来 0 条」只有在这儿才看得出来。
   */
  fun shareRunLog() {
    val runLog = deps.diagnostics.runLog.take(RUN_LOG_EXPORT_LIMIT)
    val lines = buildList {
      add("ng2n $version · 本次运行")
      add("【当前组合】")
      add(if (combos.isEmpty()) "(还没有成功的请求)" else comboSummary)
      add("【最近 ${runLog.size} 个请求】")
      runLog.forEach { entry ->
        // entry.params 在 `DiagnosticLogStore` 入库时就已经脱敏过了
        val query = entry.params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val target = if (query.isEmpty()) entry.path else "${entry.path}?$query"
        val time = CLOCK.format(Instant.ofEpochMilli(entry.at))
        add("$time ${if (entry.ok) "成功" else "失败"} $target (${entry.attempts} 次尝试) ${entry.message}")
      }
    }
    share(context, "本次运行", lines.joinToString("\n"), toast)
  }

  /** 导出诊断日志。走系统分享面板(选「保存到文件」也走得通),省一个依赖。 */
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
    item("s-lab") { SettingsSection("实验室") }

    item("fallback") {
      SettingsNavRow(label = "网页数据源兜底", sub = labelOf(net.webFallbackMode)) { fallbackOpen = true }
    }
    item("wp-ua") {
      SettingsSwitchRow(
        label = "帖子接口使用 Windows Phone UA",
        sub = "实测更不容易被封;被封表现变了可以关掉试试",
        value = net.readPhpWindowsPhoneUa,
        onChange = { next -> scope.launch { deps.settings.setReadPhpWindowsPhoneUa(next) } },
      )
    }

    item("s-diag") { SettingsSection("诊断") }

    item("combos") {
      SettingsNavRow(label = "本次运行的组合", sub = comboSummary) { shareRunLog() }
    }
    item("export") {
      SettingsNavRow(label = "导出诊断日志", sub = "最近 $EXPORT_LIMIT 条") { exportLog() }
    }
  }
}

/** 版本号从 `PackageManager` 现读,不写死 —— 「用户报的版本」与「装的那一版」对不上最难查。 */
private fun versionLabel(context: Context): String = runCatching {
  val info = context.packageManager.getPackageInfo(context.packageName, 0)
  val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
  "${info.versionName} ($code)"
}.getOrDefault("未知版本")

/** 系统分享面板。没有分享目标时(某些精简 ROM)给一句提示,别静默失败。 */
private fun share(context: Context, title: String, text: String, toast: (String) -> Unit) {
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, title)
    putExtra(Intent.EXTRA_TEXT, text)
  }
  runCatching { context.startActivity(Intent.createChooser(intent, title)) }
    .onFailure { toast("分享面板没打开") }
}
