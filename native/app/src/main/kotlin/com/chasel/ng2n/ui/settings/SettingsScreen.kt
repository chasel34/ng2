package com.chasel.ng2n.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chasel.ng2n.core.net.NGA_HOSTS
import com.chasel.ng2n.data.cache.cacheTotalBytes
import com.chasel.ng2n.data.cache.formatCacheSize
import com.chasel.ng2n.data.history.HISTORY_LIMIT
import com.chasel.ng2n.data.settings.AppSettings
import com.chasel.ng2n.data.settings.DEFAULT_SETTINGS
import com.chasel.ng2n.data.settings.ImageQuality
import com.chasel.ng2n.data.settings.ThemeMode
import com.chasel.ng2n.data.settings.ThemeStyle
import com.chasel.ng2n.ui.common.ConfirmDialog
import com.chasel.ng2n.ui.common.rememberToaster
import com.chasel.ng2n.ui.rememberAppDeps
import kotlinx.coroutines.launch

/** uiautomator / 票 18 找设置屏的锚点。 */
const val SETTINGS_SCREEN_TAG: String = "ng2n-settings-screen"

/**
 * 「主题风格」对话框的三档(设计稿 `dialog:'theme'`)。第三档不是配色,是夜间模式本身
 * —— 它的副标题原文就写着「跟随夜间模式开关」。
 */
private enum class ThemeChoice { INK, PLAIN, NIGHT }

private val THEME_OPTIONS = listOf(
  SettingsOption(ThemeChoice.INK, ThemeStyle.INK.label, "顶栏墨绿 + 奶油背景"),
  SettingsOption(ThemeChoice.PLAIN, ThemeStyle.PLAIN.label, "白底 + 深绿强调"),
  SettingsOption(ThemeChoice.NIGHT, "夜间近黑", "跟随夜间模式开关"),
)

private val HOST_OPTIONS = NGA_HOSTS.map { SettingsOption(it, it.removePrefix("https://")) }

private val QUALITY_OPTIONS = listOf(
  SettingsOption(ImageQuality.ORIGINAL, ImageQuality.ORIGINAL.label, "最清楚,也最费流量"),
  SettingsOption(ImageQuality.SMART, ImageQuality.SMART.label, "按当前网络自动选"),
  SettingsOption(ImageQuality.THUMBNAIL, ImageQuality.THUMBNAIL.label, "省流量,点开大图才拉原图"),
)

/**
 * 设置根屏(设计稿 `settings` 屏)—— `src/app/settings/index.tsx` 的移植。
 *
 * 五组:通用 / 阅读 / 通知 / 内容与存储 / 高级。分组的边界按「用户什么时候会想起它」划,
 * 不按数据存在哪:「手势返回」「阅读时常亮」跟反封锁没有关系,就是普通阅读偏好,归阅读组。
 *
 * **每一项改完立刻生效**:写 DataStore → `SettingsStore.settings` 这条 Flow 变 →
 * [Ng2nAppTheme] 与各屏读到的 [LocalAppSettings] 一起重组;域名与 Web 反解档位则由
 * 反封锁链**下一个请求现读**。没有「保存」按钮,也不重启 Activity。
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenAccounts: () -> Unit,
  onOpenFilters: () -> Unit,
  onOpenFontSize: () -> Unit,
  onOpenLab: () -> Unit,
) {
  val deps = rememberAppDeps()
  val scope = rememberCoroutineScope()
  val toast = rememberToaster()

  val settings: AppSettings by deps.settings.settings.collectAsStateWithLifecycle(DEFAULT_SETTINGS)
  val mode: ThemeMode by deps.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
  val accounts by deps.accounts.accounts.collectAsStateWithLifecycle(null)
  val rules by deps.settings.localFilterRules.collectAsStateWithLifecycle(emptyList())
  val history by deps.history.entries.collectAsStateWithLifecycle()
  val topics by deps.topicCache.topics.collectAsStateWithLifecycle()

  // 历史与缓存的两张表是懒加载的(P2-04:冷启动不同步读盘),进这一屏时把计数暖起来
  LaunchedEffect(Unit) {
    deps.history.warmUp()
    deps.topicCache.warmUp()
  }

  val dark = resolveDark(mode, isSystemInDarkTheme())
  val cacheBytes = remember(topics) { cacheTotalBytes(topics) }

  var hostOpen by remember { mutableStateOf(false) }
  var themeOpen by remember { mutableStateOf(false) }
  var qualityOpen by remember { mutableStateOf(false) }
  var clearHistoryOpen by remember { mutableStateOf(false) }
  var clearCacheOpen by remember { mutableStateOf(false) }
  var resetOpen by remember { mutableStateOf(false) }

  fun update(transform: (AppSettings) -> AppSettings) {
    scope.launch { deps.settings.updateSettings(transform) }
  }

  fun setMode(next: ThemeMode) {
    scope.launch { deps.settings.setThemeMode(next) }
  }

  SettingsShell(
    title = "设置",
    onBack = onBack,
    modifier = Modifier.semantics { contentDescription = SETTINGS_SCREEN_TAG },
    overlays = {
      SettingsOptionDialog(
        open = hostOpen,
        title = "NGA 域名",
        options = HOST_OPTIONS,
        value = settings.host,
        hint = "被封时换一个域名常常就通了;反封锁链本来也会自己轮换,这里定的是先试哪一个。",
        onCancel = { hostOpen = false },
        onConfirm = { host ->
          hostOpen = false
          update { it.copy(host = host) }
        },
      )

      SettingsOptionDialog(
        open = themeOpen,
        title = "主题风格",
        options = THEME_OPTIONS,
        value = if (dark) ThemeChoice.NIGHT else when (settings.themeStyle) {
          ThemeStyle.INK -> ThemeChoice.INK
          ThemeStyle.PLAIN -> ThemeChoice.PLAIN
        },
        onCancel = { themeOpen = false },
        onConfirm = { choice ->
          themeOpen = false
          if (choice == ThemeChoice.NIGHT) {
            setMode(ThemeMode.DARK)
          } else {
            update { it.copy(themeStyle = if (choice == ThemeChoice.INK) ThemeStyle.INK else ThemeStyle.PLAIN) }
            // 在夜间模式下选了一档浅色风格,那就是要退出夜间模式
            if (dark) setMode(ThemeMode.LIGHT)
          }
        },
      )

      SettingsOptionDialog(
        open = qualityOpen,
        title = "图片加载策略",
        options = QUALITY_OPTIONS,
        value = settings.imageQuality,
        hint = "这一档定的是清晰度;要不要在流量下自动拉图,由上面的「仅 Wi-Fi 下加载图片」管。",
        onCancel = { qualityOpen = false },
        onConfirm = { quality ->
          qualityOpen = false
          update { it.copy(imageQuality = quality) }
        },
      )

      // 阅读进度与浏览历史是同一张表,清进度就是清历史,得说清楚
      ConfirmDialog(
        open = clearHistoryOpen,
        title = "清空阅读进度记录",
        message = "将删除 ${history.size} 个主题的「上次读到第 N 楼」,浏览历史列表也会一起清空。",
        confirmLabel = "清空",
        destructive = true,
        onCancel = { clearHistoryOpen = false },
        onConfirm = {
          clearHistoryOpen = false
          scope.launch {
            deps.history.clear()
            toast("已清空阅读进度")
          }
        },
      )

      ConfirmDialog(
        open = clearCacheOpen,
        title = "清理缓存",
        message = "${topics.size} 个主题、共 ${formatCacheSize(cacheBytes)} 的离线数据将被删除。",
        confirmLabel = "清理",
        destructive = true,
        onCancel = { clearCacheOpen = false },
        onConfirm = {
          clearCacheOpen = false
          val freed = formatCacheSize(cacheBytes)
          scope.launch {
            deps.topicCache.clear()
            toast("已清理 $freed 缓存")
          }
        },
      )

      ConfirmDialog(
        open = resetOpen,
        title = "恢复默认设置",
        message = "全部开关、域名、字号与主题风格都会回到默认值。账号、收藏、缓存与屏蔽规则不受影响。",
        confirmLabel = "恢复",
        destructive = true,
        onCancel = { resetOpen = false },
        onConfirm = {
          resetOpen = false
          scope.launch {
            deps.settings.resetAll()
            toast("已恢复默认设置")
          }
        },
      )
    },
  ) {
    item("s-general") { SettingsSection("通用") }

    item("host") {
      SettingsNavRow(label = "NGA 域名", sub = settings.host) { hostOpen = true }
    }
    item("accounts") {
      val count = accounts?.accounts?.size ?: 0
      SettingsNavRow(
        label = "账号管理",
        sub = if (count == 0) "还没有登录账号" else "已登录 $count 个账号",
        onClick = onOpenAccounts,
      )
    }
    // 夜间模式与「跟随系统」是同一个档位的两面:开关记的是最终深浅,
    // 跟随系统打开时那个开关只是在显示系统现在是深还是浅
    item("night") {
      SettingsSwitchRow(
        label = "夜间模式",
        sub = if (mode == ThemeMode.SYSTEM) "当前跟随系统" else null,
        value = dark,
        onChange = { next -> setMode(if (next) ThemeMode.DARK else ThemeMode.LIGHT) },
      )
    }
    item("night-system") {
      SettingsSwitchRow(
        label = "夜间模式跟随系统",
        value = mode == ThemeMode.SYSTEM,
        onChange = { next ->
          setMode(if (next) ThemeMode.SYSTEM else if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        },
      )
    }
    item("theme-style") {
      SettingsNavRow(
        label = "主题风格",
        sub = if (dark) "夜间近黑" else settings.themeStyle.label,
      ) { themeOpen = true }
    }
    item("left-handed") {
      SettingsSwitchRow(
        label = "左手模式",
        sub = "FAB 与菜单移到左侧",
        value = settings.leftHanded,
        onChange = { next -> update { it.copy(leftHanded = next) } },
      )
    }
    item("solid-bg") {
      SettingsSwitchRow(
        label = "使用纯色背景",
        sub = "主题列表和详情页使用纯色背景",
        value = settings.solidBackground,
        onChange = { next -> update { it.copy(solidBackground = next) } },
      )
    }

    item("s-reading") { SettingsSection("阅读") }

    item("auto-next") {
      SettingsSwitchRow(
        label = "自动加载下一页",
        sub = "滚动到底部时自动翻页",
        value = settings.autoLoadNextPage,
        onChange = { next -> update { it.copy(autoLoadNextPage = next) } },
      )
    }
    item("wifi-only") {
      SettingsSwitchRow(
        label = "仅 Wi-Fi 下加载图片",
        sub = "移动网络显示「点击显示附件」",
        value = settings.wifiOnlyImages,
        onChange = { next -> update { it.copy(wifiOnlyImages = next) } },
      )
    }
    item("image-quality") {
      SettingsNavRow(label = "图片加载策略", sub = settings.imageQuality.label) { qualityOpen = true }
    }
    item("signature") {
      SettingsSwitchRow(
        label = "显示签名档",
        sub = "在楼层正文下面显示作者签名",
        value = settings.showSignature,
        onChange = { next -> update { it.copy(showSignature = next) } },
      )
    }
    item("gesture-back") {
      SettingsSwitchRow(
        label = "手势返回",
        sub = "从左边缘右滑返回上一页",
        value = settings.gestureBack,
        onChange = { next -> update { it.copy(gestureBack = next) } },
      )
    }
    item("keep-screen-on") {
      SettingsSwitchRow(
        label = "阅读时常亮",
        sub = "看帖子详情时屏幕不自动熄灭",
        value = settings.keepScreenOn,
        onChange = { next -> update { it.copy(keepScreenOn = next) } },
      )
    }
    item("font-size") {
      val a = settings.appearance
      SettingsNavRow(
        label = "字体和头像大小",
        sub = "列表字体 ${a.listFontSize.toInt()} · 头像 ${a.avatarScale.toInt()}% · 表情 ${a.smileyScale.toInt()}%",
        onClick = onOpenFontSize,
      )
    }

    item("s-notice") { SettingsSection("通知") }

    item("spray") {
      SettingsSwitchRow(
        label = "启用被喷提示",
        sub = "关掉后不再轮询通知,抽屉也不显示未读角标",
        value = settings.sprayNotice,
        onChange = { next -> update { it.copy(sprayNotice = next) } },
      )
    }
    item("notice-sound") {
      SettingsSwitchRow(
        label = "提示声音",
        value = settings.noticeSound,
        onChange = { next -> update { it.copy(noticeSound = next) } },
      )
    }

    item("s-storage") { SettingsSection("内容与存储") }

    item("filters") {
      SettingsNavRow(
        label = "屏蔽规则",
        sub = if (rules.isEmpty()) "还没有本地规则" else "本地 ${rules.size} 条",
        onClick = onOpenFilters,
      )
    }
    item("history") {
      SettingsNavRow(
        label = "阅读进度记录",
        sub = if (history.isEmpty()) "还没有记录(最多留最近 $HISTORY_LIMIT 个主题)"
        else "已记录 ${history.size} 个主题 · 点此清空",
      ) {
        if (history.isEmpty()) toast("还没有阅读进度可清") else clearHistoryOpen = true
      }
    }
    item("cache") {
      SettingsNavRow(
        label = "清理缓存",
        sub = if (topics.isEmpty()) "还没有缓存的帖子"
        else "${topics.size} 个主题 · 已占用 ${formatCacheSize(cacheBytes)}",
      ) {
        if (topics.isEmpty()) toast("还没有缓存可清") else clearCacheOpen = true
      }
    }

    item("s-advanced") { SettingsSection("高级") }

    item("lab") {
      SettingsNavRow(label = "实验室与诊断", sub = "网页兜底 · 请求组合 · 诊断日志", onClick = onOpenLab)
    }
    item("reset") {
      SettingsNavRow(label = "恢复默认设置", sub = "全部设置回默认值,不动账号与缓存") { resetOpen = true }
    }
  }
}
