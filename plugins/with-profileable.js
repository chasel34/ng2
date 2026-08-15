const { withAndroidManifest } = require('expo/config-plugins');

/**
 * 给 <application> 加 `<profileable android:shell="true"/>`。
 *
 * release 包默认不吐 atrace 标记,Perfetto 里只能看到系统侧时间线;加上它之后
 * `adb shell perfetto` 能拿到 HWUI/React 的应用内切片(RenderThread 的
 * queueBuffer、Fabric 的 mountViews 等),120Hz 队列振荡这类问题全靠这些定位
 * (2026-08-15 帧流水线排查)。只对 shell(即 adb)开放,不影响安全性。
 */
module.exports = function withProfileable(config) {
  return withAndroidManifest(config, (result) => {
    const app = result.modResults.manifest.application?.[0];
    if (app !== undefined) {
      const existing = app.profileable?.[0]?.$?.['android:shell'];
      if (existing !== 'true') {
        app.profileable = [{ $: { 'android:shell': 'true' } }];
      }
    }
    return result;
  });
};
