# 设计参考

主原型：[NGA客户端.dc.html](project/NGA客户端.dc.html)，辅助脚本：[support.js](project/support.js)。这些 HTML/CSS/JS 文件用于说明视觉与信息结构，不参与 Android 构建。

当前实现使用 Jetpack Compose。修改界面时参考原型的布局、颜色和内容层级，并结合当前源码与已确认的产品调整；交互允许采用 Android 原生惯例。原生重写的范围见 [ADR-0003](../docs/adr/0003-full-native-android-rewrite.md)。

图标与背景的候选素材及生成记录见 [assets/images/concepts](../assets/images/concepts/README.md)。
