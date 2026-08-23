# 54 — 场景 7 附件展开/收起瞬时跳变

**类型:** performance / acceptance blocker  
**优先级:** P1  
**状态:** open

## 现象

票 19 真机 release 验收中，在含 2 个图片附件的主题 `tid=47328470` 上执行 15 轮
附件展开/收起。折叠条与附件宫格直接互换，没有 spec §五场景 7 要求验收的连续展开/
收起动画；录屏表现为整块内容瞬时跳变。

同一 30 秒样本中，`dumpsys gfxinfo` 现代口径为 25/124 janky frames（20.16%）。
录屏为 VFR，29.87 秒仅记录 67 帧，画面变化集中在每次点击的单帧替换，无法形成可裁决
的动画速度曲线。附件图片已由登录态正常加载，排除失败页与网络占位。

## 复现

1. release 包 `com.chasel.ng2.n` 登录后打开 `ng2n://read.php?tid=47328470`。
2. 滚动到主楼底部的“点击显示附件(2)”。
3. 连续点击展开与“收起附件”。
4. 观察附件宫格瞬时出现/消失，正文下方内容同步跳位。

## 证据

- `acceptance/perf/s7-native-framestats-v2.txt`
- `acceptance/perf/s7-native-rec-analysis-v2.txt`
- 录屏（不进 git）：`/Users/cola/.claude/jobs/e7f2363b/tmp/perf/s7-native-attachments-v2.mp4`

## 验收期望

附件宫格展开/收起具有连续动画；120Hz 录屏逐帧速度曲线连续，动画内不得肉眼可见停格
或整块突现，且现代 janky 不劣于并装基线。
