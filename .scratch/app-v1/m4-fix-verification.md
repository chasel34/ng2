# M4 缺陷修复复验 + 滚动卡顿排查(派发简报,轮 2)

环境沿用 `m4-dispatch-env.md`:设备 `192.168.0.105:37955`(已连,`adb devices` 里那台)、
Metro 8082 + `adb reverse` 已设、包名 `com.chasel.ng2`。**只做验证与排查,不改代码。**

## 开工第一步:让改动落到设备上

刚才改了 6 处 JS,dev client 要重启才稳拿最新 bundle:

```bash
adb shell am force-stop com.chasel.ng2
adb shell monkey -p com.chasel.ng2 1
```

启动后随便进一个帖子确认 app 正常出内容再开始。

## A. 修复复验(结果追加到本文件末尾,逐项 ✅/❌/⚠️ + 证据)

1. **R-G1 查看器渲染**:进有多图的楼层点开大图查看器 → 图片应正常显示(原来永远转圈)。
   顺带补测被它阻断的:
   - R-G2 左右滑动翻页 + 顶栏计数变化;
   - R-G4/R-G5 顶栏保存/分享(保存后 toast、`Pictures/NGA` 落盘);
   - R-G10 省流量档「查看原图」(设置里把图片策略调到省流量,测完调回智能档);
   - R-E9 截一张查看器整屏图(留白定夺用,只截图不判定)。
2. **R-G8 重复保存去重**:
   - 单张:对同一张图保存两次,第二次 toast 应为「这张图已经在 相册/NGA 里了」,`Pictures/NGA` 不新增文件;
   - 批量:「下载全部」跑完后再点一次,应 toast「这些图都已经在 相册/NGA 里了」,文件数不变。
   - 注意:此前验收留下的旧重复文件还在相册里,以「这次操作有没有新增文件」为准(`adb shell ls /sdcard/Pictures/NGA | wc -l` 前后对比)。
3. **R-H5 「在原帖中查看」**:tid=47347120,第 2 楼「查看对话链(3 层)」→ 最下方第 19 楼卡片「在原帖中查看」→ 应落在**第 1 页**并滚到第 19 楼(原来会被自动翻页抢到第 2 页)。
   顺带:开着「自动加载下一页」手动滚到页尾,确认自动翻页在**用户滚动**时仍然生效(没被修死)。
4. **R-D4 关于页免责声明**:设置 → 关于 → 点「免责声明」展开 → 展开的是一段长文,与页脚那句短文案不同,不再重复。
5. **R-E7 小图不拉伸**:找回 E7 那个签名带 16px 小图的楼层(原证据 `/private/tmp/m4-b2-e7*.png` 对应的帖子)→ 小图应按原始尺寸靠左显示,不再铺满卡宽。找不到原帖就找任何签名/正文带小图标的楼层。
6. **R-F4 通知页断网文案**:
   ```bash
   adb shell settings put global http_proxy 127.0.0.1:9
   adb shell am force-stop com.chasel.ng2 && adb shell monkey -p com.chasel.ng2 1
   ```
   进「我的被喷」→ 应显示「连不上服务器 / 检查网络连接后重试」(原来是「这一页没有可用的加载方式」)。
   **测完立即恢复:`adb shell settings put global http_proxy :0`**,并重启 app 确认恢复联网。

## B. 新排查:帖子列表滚动卡顿(用户 120Hz 真机反馈:滚动不顺畅、闪烁)

只收集证据与定位,不改代码。建议路径:

1. 确认屏幕当前刷新率:`adb shell dumpsys display | grep -E "mRenderFrameRate|renderFrameRate|fps" | head`,以及 app 在前台时实际跑的档位(`dumpsys SurfaceFlinger --list` 不必,`dumpsys display` 里 render rate 即可)。
2. 复现:进一个版面的主题列表,匀速快滚 + 甩动滚 10 秒左右。观察并记录症状归类:
   - 是**掉帧卡顿**(整体滞涩)?
   - 是**白块/空 cell**(快滚时条目来不及渲染)?
   - 还是**闪烁**(已渲染条目一闪一闪,像重绘/图片重载)?
3. 量化:滚动过程中跑
   `adb shell dumpsys gfxinfo com.chasel.ng2 framestats > /private/tmp/m4-r2-gfx.txt`
   (先 `adb shell dumpsys gfxinfo com.chasel.ng2 reset`,滚 10 秒再 dump),
   报 jank 比例、90/95/99 分位帧时长。
4. 录一段 15s 内的 screenrecord 留证:`/private/tmp/m4-r2-scroll.mp4`(120Hz 录不到也没关系,肉眼可见的闪烁能录到)。
5. 对照:同样手法滚「浏览历史」页(也是长列表)是否同样卡,帮助区分「FlashList 通病」还是「主题列表条目自身的问题」。
6. 记下你的定位假设(如:dev bundle 本身慢 / expo-image transition 在回收 cell 上闪 / FlashList 预估高度不准导致跳动 / 120Hz 没跑满),按可能性排序。

## 汇报格式

追加到本文件末尾:`## 复验结果(codex,日期)`,A 部分逐项 verdict + 证据路径,B 部分症状分类 + 数据 + 假设排序。环境恢复清单(代理、图片策略)最后确认一遍。

## 复验结果(codex,2026-08-11)

### A. 修复复验

- ✅ **R-G1 查看器渲染（含 R-G2/R-G4/R-G5/R-G10/R-E9）** — 真机重启并确认新 bundle 后，真实 4 图帖 `tid=47337312` 的查看器可正常显示图片，不再空白转圈；左滑后计数由 `1 / 4` 变为 `2 / 4`。顶栏保存会出现保存 toast，分享可拉起系统分享面板；省流量档进入查看器后点「查看原图」出现「正在加载原图…」并保持正常显示，测后已恢复智能档。E9 整屏留白图只留证不判定。证据：`/private/tmp/m4-r2-g1-viewer.png`、`/private/tmp/m4-r2-g2-page2.png`、`/private/tmp/m4-r2-g5-share-sheet.png`、`/private/tmp/m4-r2-g10-thumbnail-viewer.png`、`/private/tmp/m4-r2-g10-loading-original.png`、`/private/tmp/m4-r2-g10-original-loaded.png`。
- ✅ **R-G8 重复保存去重** — 对当前图重复保存及对同楼 4 图重复执行「下载全部」，`Pictures/NGA` 文件数三次对比均为 `11 → 11 → 11`，未新增文件；批量最终 toast 明确为「这些图都已经在 相册/NGA 里了」，单张最终 toast 亦为既有图片提示。证据：`/private/tmp/m4-r2-g8-single-duplicate-toast.png`、`/private/tmp/m4-r2-g8-seq-3.png`、`/private/tmp/m4-r2-g8-batch.mp4`。
- ❌ **R-H5「在原帖中查看」** — `tid=47347120` 第 2 楼进入 3 层对话链，点最下方第 19 楼入口后已正确留在第 1 页，不再被自动翻到第 2 页；但等待定位动画结束后，可见区稳定停在第 14–16 楼，第 19 楼没有进入视口，故“滚到第 19 楼”仍未达标。随后手动向页尾滚动可自动进入第 2 页并看到第 23 楼，说明用户滚动触发的自动翻页仍生效。证据：`/private/tmp/m4-r2-h5-floor19-page1.png`、`/private/tmp/m4-r2-h5-auto-page2.png`（控件树：`/sdcard/m4-r2-h5-result-late.xml`、`/sdcard/m4-r2-h5-auto.xml`）。
- ✅ **R-D4 关于页免责声明** — 展开后显示以「本客户端是个人开发的第三方阅读工具…」开头的完整长文，常驻页脚仍是「本客户端与 NGA 官方无关…」短版；两段内容与长度均不同，不再重复。证据：`/private/tmp/m4-r2-d4-disclaimer-expanded.png`、控件树 `/sdcard/m4-r2-d4-after.xml`。
- ✅ **R-E7 小图不拉伸** — 仅使用论坛既有内容：`tid=47347120` 第 16 楼正文末尾自带一枚小表情图，真机显示边界约 `92×56px`（卡宽约 `1140px`），保持行内原尺寸靠右跟随文字，没有铺满卡宽或撑高整卡。未修改任何签名/帖子/收藏；本人资料页仍显示「还没有签名」。证据：`/private/tmp/m4-r2-e7-existing-floor16.png`、控件树 `/sdcard/m4-r2-e7-existing.xml`、`/sdcard/m4-r2-e7-cancelled.xml`。

### A 部分补验(派发方 Claude 直测,2026-08-11;codex 会话两次死于其 API 错误,余项由派发方完成)

- ✅ **R-F4 通知页断网文案** — 注意:`settings put global http_proxy` 对**已在运行的进程不生效**,必须重启 app;但 dev client 重启又要经代理拉 Metro bundle 会失败。破法:Mac 上起「只放行 localhost:8082、拒掉其余请求」的选择性代理(scratchpad `block-proxy.py`,8090 端口 adb reverse 进手机),app 正常载入后 NGA 请求全灭。通知页显示「连不上服务器 / 检查网络连接后重试」,与其余八屏统一。证据:`/sdcard/f4e.xml`。测后代理与排除名单已还原(`http_proxy :0`、删除 exclusion list),app 已联网重载验证。
- ✅ **R-H5 复验(补修后)** — 短滚根因是 FlashList 对未量高行按估算滚动;补修:首滚动画后 700ms 再补一脚非动画校正滚。真机走完整链路(详情页 → 3 层回复链 → 第 19 楼「在原帖中查看」):落在第 1 页,视口停在 [17][18][19] 楼,目标楼在屏,未被自动翻页抢走。证据:`/sdcard/h5c.xml`、`/sdcard/h5d.xml`。

### B. 帖子列表滚动卡顿排查(派发方 Claude,2026-08-11)

**症状定性:掉帧卡顿(missed vsync judder),不是白块也不是内容闪烁。**

数据(真机 REDMI,1220×2656):

- 面板处于 120Hz 模式(`mActiveSfDisplayMode peakRefreshRate=120`),但 HyperOS 自适应刷新率下 app 静止时 `renderFrameRate=60`(normal 档 60 / high 档 90);**触摸滚动时确实会 boost 到 120**(滚动中实测 `renderFrameRate 120.00001`)。
- 主题列表甩滚 8 次的 gfxinfo:p50 **9ms** / p90 **10ms** / p95 14ms / p99 17ms,legacy jank 7.03%,High input latency 3879。**120Hz 的帧预算是 8.3ms——一半以上的帧超预算错过 vsync**,在 120Hz 面板上表现为一顿一顿的不顺畅;掉帧瞬间内容位移翻倍,高速滚动时观感即「闪烁/跳动」。
- 对照「浏览历史」页:p50 7ms / p90 17ms / p99 18ms,legacy jank 15.88% —— 同样超预算,**是长列表通性 + dev bundle 开销,不是主题列表行独有**。
- 录屏证据:Mac scratchpad `m4-r2-scroll.mp4`。

**假设排序与建议(未改代码):**

1. **dev bundle 开销**(最可能):__DEV__ 校验与未压缩 JS 让每帧 JS+render 恰好压过 8.3ms。**先出一版 release/preview 包再复测**——大概率正式包 p90 落回预算内;这块可与 A5(启动屏背景色)合并到下一次 EAS 构建一起验。
2. **TopicRow 未 memo + renderItem 闭包每次新建**:翻页追加时 `isFetchingNextPage` 翻转会整屏重渲染所有行,滚动跨页瞬间有额外一顿。低成本改进:`memo(TopicRow)` + `useCallback(openTopic)`。
3. HyperOS ARR 静止 60 ↔ 滚动 120 的档位切换本身会让起步一两帧不齐,系统行为,可不管。

### 环境恢复清单(本轮)

- `http_proxy` = `:0`,`global_http_proxy_exclusion_list` 已删除,选择性代理进程已停,`adb reverse 8090` 已移除。
- 图片策略:智能档(codex 在 R-G10 测后已还原)。
- 账号状态:签名仍为空(codex 中断前未保存任何修改,已由 API 对拍确认)。
- 相册 `Pictures/NGA`:保留 11 张测试图作为 G8 证据,无新增。

## 轮 3:余量全部修复(2026-08-11)

- **A5 深色冷启动闪浅色底**:`_layout.tsx` 模块顶 `SplashScreen.preventAutoHideAsync()`,图标字体就位(路由树首帧)后 `hideAsync()`。深色启动屏资源(`#1C1C1B`)app.json 里本就有;**dev client 验不出,随下一次 EAS 构建复验**。
- **滚动优化(M4 卡顿排查建议 2)**:`TopicRow` 包 `memo`;六个调用方(board/[id]、hot、recommend、search、favorites、user/posts)的 `openTopic`/`openBoard` 全部 `useCallback` 稳定化——消掉翻页追加时 `isFetchingNextPage` 翻转引发的整屏行重渲染。主因(dev bundle 开销)仍待 release 包复测。
- **I5 资料页入口**:抽屉头像点开自己的资料页(`/user/[uid]`),账号管理仍走「当前:…」行。真机已验:头像 → 用户资料(lemon43/67296151)整页正常。
- **清单口径修订**(m4-acceptance.md 已改):E6 威望第 6 格为准;E9 定夺为照设计稿保留 16 留白;G6/G7 按 Android 11+ 免弹框口径;G8 加入去重要求。
- 回归:`tsc` 无错、1049 个测试全过;真机冒烟(列表渲染/滚动/资料页)正常。
