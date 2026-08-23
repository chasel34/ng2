# 55 — 场景 8 画廊缩放后可把图片整体移出视口

**类型:** performance / interaction / acceptance blocker  
**优先级:** P1  
**状态:** open

## 现象

票 19 真机 release 验收中，打开 `tid=47328470` 的第 2 个附件进入大图查看器，双击
放大后执行一次单指平移，图片可整体移出视口，只剩黑色画布与系统状态/导航栏。黑屏会
持续存在；连续脚本中的后续双击没有可靠复位，用户无法继续查看图片。

这不是图片加载失败：同一图片进入查看器及手工复位后均能完整显示；问题只在缩放后的
平移边界。30 秒脚本的 gfxinfo 现代 janky 为 10/1784（0.56%），但 C1 画面证据优先于
帧率统计，内容完全移出视口直接不过闸。

## 复现

1. release 包 `com.chasel.ng2.n` 登录后打开 `ng2n://read.php?tid=47328470`。
2. 展开“点击显示附件(2)”，点第 2 张图。
3. 在查看器中央双击放大。
4. 从 `(610,1600)` 向 `(300,1200)` 平移约 500 ms。
5. 观察图片整体移出视口并停留在黑屏。

## 证据

- `acceptance/perf/s8-native-framestats.txt`
- `acceptance/perf/s8-native-rec-analysis.txt`
- `acceptance/perf/s8-native-post.png`
- 录屏（不进 git）：`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s8-native-gallery.mp4`

## 验收期望

缩放平移必须约束在图片边界，任意时刻至少保留有效图片内容；双击复位稳定可用，开合与
缩放过程无肉眼可见停格。
