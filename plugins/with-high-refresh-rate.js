const { withMainActivity } = require('expo/config-plugins');

const CALL = 'window.decorView.post { preferHighestRefreshRate() }';

const IMPLEMENTATION = `
  override fun onResume() {
    super.onResume()
    // 厂商系统从后台恢复时会重新做窗口刷新率投票，因此前台恢复时再声明一次。
    preferHighestRefreshRate()
  }

  private fun preferHighestRefreshRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

    @Suppress("DEPRECATION")
    val targetDisplay =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
      else windowManager.defaultDisplay
    val activeDisplay = targetDisplay ?: return
    val currentMode = activeDisplay.mode
    val bestMode = activeDisplay.supportedModes
      .filter {
        it.physicalWidth == currentMode.physicalWidth &&
          it.physicalHeight == currentMode.physicalHeight
      }
      .maxByOrNull { it.refreshRate }
      ?: return

    val attributes = window.attributes
    attributes.preferredDisplayModeId = bestMode.modeId
    attributes.preferredRefreshRate = bestMode.refreshRate
    window.attributes = attributes
  }

`;

/**
 * 让 React Native 窗口请求当前分辨率下的最高刷新率。
 *
 * `android/` 是 Expo 生成目录，不进 Git；把改动做成 config plugin，之后每次
 * `expo prebuild` 都能稳定重建。插件只做带标记的幂等文本插入。
 */
module.exports = function withHighRefreshRate(config) {
  return withMainActivity(config, (result) => {
    let source = result.modResults.contents;
    if (!source.includes(CALL)) {
      source = source.replace(
        '    super.onCreate(null)',
        `    super.onCreate(null)\n    // 120Hz 设备上避免窗口被系统按 60Hz 内容源处理。\n    ${CALL}`,
      );
    }
    if (!source.includes('private fun preferHighestRefreshRate()')) {
      source = source.replace(
        '  /**\n   * Returns the name of the main component registered from JavaScript.',
        `${IMPLEMENTATION}  /**\n   * Returns the name of the main component registered from JavaScript.`,
      );
    }
    result.modResults.contents = source;
    return result;
  });
};
