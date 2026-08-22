# 性能问题史 + 领域文档现状(探查归档)

> 探查 agent 报告,2026-08-21,基线 `main` @ `497c766`。为原生重写立项服务;file:line 以当时工作树为准。

## 一、`docs/project-audit-2026-08-20.md` 结论要点

**定位偏差**:这是一份安全/正确性/工程治理审计,**不是性能审计**。基线 `0317aee`(2026-08-19),不覆盖 HEAD 的 `497c766`「转场卡顿一轮」。

总体:「有条件不通过,建议暂缓发布」。P0=0、P1=4、P2=10、P3=6。四个 P1 全部集中在写操作重试语义与多账号隔离:

| 编号 | 问题 |
|---|---|
| P1-01 | 非幂等写操作被全局反封锁链同账号/跨账号重放(点赞、收藏、建/删收藏夹、签到) |
| P1-02 | 收藏夹 Query 缓存没按 UID 隔离,切号后显示上一账号数据 |
| P1-03 | App 账号状态与 Android WebView CookieManager 生命周期不统一 |
| P1-04 | 诊断日志明文记录并可外发 `fav` 访问码/搜索词/屏蔽词表 |

性能相关(P2/P3 档):P2-04 冷启动路径同步持久化(`topic-cache.ts:33` 模块初始化 loadMeta 同步扫 SQLite;notifications 同理);P2-08 主题页职责过重(`topic/[tid].tsx` ~1796 行);P2-01 lint 门禁失效(79 errors,集中在 swipe-pager/image-gallery/topic 三个性能热点);P2-06 网络无统一超时预算(loading 悬挂=感知卡顿);P3-05 用户正则无资源上限(可卡 JS 线程)。

**关键落差**:审计对性能的结论是「没发现阻塞问题、性能意识良好」,而同期所有者体感「转场还在卡」——因为审计是静态审查,没做真机帧测量。**「RN 救不救得回来」的证据全在 `.scratch/perf-2026-08/`,不在审计里。**

## 二、CONTEXT.md 术语表

存在,107 行,纯术语表(6 域 26 条,每条带 Avoid 列表):版块/合集/子版块/版头/主题/楼层/贴条/热门回复/回复链/fav 码/账号/匿名还原/签到/通知/短消息/收藏夹/版块收藏/屏蔽规则/阅读进度/帖子缓存/热帖/彩色标题/骰子/反封锁链/Web 反解/网页兜底。**原生重写完全不受影响,原样沿用。** 术语表没有任何性能词条(latch2present、转场、分帧揭示等都不在)——性能词汇归 `docs/perf-playbook.md`,不进 CONTEXT.md。

## 三、既有 ADR 与重写的关系

- **ADR-0001(BBCode→AST→原生组件,不用 WebView)**:结论被强化(anzong 的 per-floor WebView 是反例),实现整体重写为 Kotlin。三条降级(table 简化/投票只读/flash 外跳)沿用;「不做编辑器」前提若将来加发帖则失效。
- **ADR-0002(反封锁链一等公民)**:主体与框架正交,**逐字带走**。十条现状修正全是血泪,其中三条 RN 特有结论在原生失效反而变成收益:`renewTransport` 在 RN 是空操作(OkHttpClient 模块级单例)→原生可真轮换;cookie jar 覆盖手设 Cookie 头→原生可自管 CookieJar;「禁止 clone/tee response」纪律作废。
- **CLAUDE.md 动画条款**(一律 Reanimated、禁 RN Animated 及其 120Hz 预采样论证):RN 内部矛盾,整条作废。

## 四、目前仍 open 的卡顿/体验问题

1. **冷启后首次进主题 ~31ms 起手冻结**(唯一量化残留,所有者体感仍报卡)。已排除:模块加载(import 预热无效已撤)、已挂载屏陪跑、TopicScreen chrome(chromeReady 分帧把非冷进帖从 33ms 干到无可见间隙,冷态纹丝不动)。当前根因假设:**Hermes 首次执行该屏 hooks 路径** + 导航分发 ~8ms。→ **原生必赢的一仗。**
2. SwipePager settle 尾 ~17ms commit gap:判定基本无感,主动不处理。
3. **偶发触摸完全失灵**(P1,画面正常但整窗口不响应触摸,force-stop 恢复,无确定复现):未定位。新代码库不背这个 bug,不进验收;「Android 触摸分发被父边界裁剪」模式记入 playbook。
4. 连续猛甩中段 drop 簇:FlashList→LegendList 后成簇消失,实际结案;孤立单帧 drop 属平台余量。
5. PSS 常驻 ~350MB:非泄漏,是 Glide 位图内存缓存(Native Heap);原生同一笔账;`onTrimMemory` 清缓存是白捡改进。
6. 主题列表快甩 p95 曾 19ms(中文长标题文本布局,平台成本):LegendList 后未复测。

## 五、六轮性能战役轮廓(2026-08-11 ~ 08-21)

1. 模拟器阶段(08-11/12)打空:janky frames 在 AVD 被 GL 管道淹没,A/B 无差异——产出是方法论纠错。
2. 真机基线(08-13/14):慢拖 janky 0.1%、快甩 2.7%,**滚动这仗其实早赢了**。
3. 120Hz 四轮(08-15,`02e2ebb`):转场重叠、latch2present 双峰→单峰(队列深度振荡=「有帧率没手感」)、push 首帧分帧、拖拽期 drop。主力战役,大部分赢。
4. 列表引擎 FlashList→LegendList(08-15):drop 簇消失,所有者确认「流畅很多」。
5. 全量迁 Reanimated(08-15,`935c72e`):RN Animated 60fps 预采样问题根除,升格 CLAUDE.md 铁律。
6. 横滑翻页持有权改造(08-19)+ 转场一轮(08-21):翻页速度曲线与 anzong 同形;转场六场景五个清零,剩冷启 31ms。

**注意**:`d2aaca4` 删掉了 `docs/performance-diagnosis-2026-08-15.md`(417 行,latch2present 判据、队列振荡机制、LegendList 对拍的唯一原始记录)。取回:`git show d2aaca4^:docs/performance-diagnosis-2026-08-15.md`。票 02 恢复进仓库。

## 六、测量方法论(票 02 落盘为 docs/perf-playbook.md 的素材)

**判据体系(按可信度)**:① 录屏逐帧=感知层 ground truth(pyav 帧间 dt + 灰度差分;「运动窗口内 dt 大洞」才算停格,静止画面出帧空洞不算缺陷);② gfxinfo framestats(IntendedVsync 间隔 >13ms=丢帧,但分不出动画窗口内外);③ Perfetto(release 无 JS systrace 段,用 thread_state Running 区间推断);④ 临时 JS/代码探针。

**核心判据**:latch2present 单峰=队列深度恒定=无感;双峰(9-10ms 与 17-18ms)=深度 1↔2 振荡=「有帧率没手感」。app 每帧 CPU = framestats HandleInputStart→SwapBuffers 之和。`present_type='Dropped Frame'` 数丢帧(不是 name 列)。

**作废判据**:模拟器 Janky frames(GL 管道淹没);gfxinfo GPU 直方图(队列满时虚标);`Number High input latency`(adb input 注入天然旧时间戳,恒满);自制像素同色率空白检测。

**实锤陷阱**:屏幕闲置变暗后 HyperOS 锁 60Hz(判定前须在滚动中确认 `frameRateOverride {uid 120}`);`SurfaceFlinger --timestats` 反复 enable/clear 会卡死;framestats 列要按表头定位(新版插了 FrameTimelineVsyncId);测量前确认前台焦点;`adb shell input swipe` ≤80ms 的起步段不可当证据;**NGA 冷启限流,循环之间 ≥60s 冷却**;验收一律本地 assembleRelease(同 keystore install -r 保登录态);模拟器面板 120 但 `mActiveRenderFrameRate=60`,验不了 120Hz。

**基建缺口**:性能脚本(analyze_framestats.py、analyze_rec.py 等,各 ~60 行)全部只活在历史会话 scratchpad,仓库里零性能脚本。留在仓库的只有四样:`plugins/with-profileable.js`(release 可测量性,**审计 P3-01 建议限制到 dev/preview——与性能纪律冲突,原生版保留 profileable**)、`plugins/with-high-refresh-rate.js`(onCreate/onResume 双请求,厂商后台恢复会重投刷新率)、`src/ui/progressive.tsx`、CLAUDE.md 动画条款。

## 七、原生救得了 / 救不了(验收预期账本的证据)

**救不了**(全部有实测证据):首屏 2.4–2.8s 是网络+链轮换(app 每帧 CPU 同期只有 2.2–2.5ms);服务端坏字节(414 版块 GBK 里混 UTF-8、`0xac`/`0x80` 两种编码都解不出,解法是 `__output=11` 第三序列化器——纯协议层);NGA 限流(静置 75s 恢复);图片解码内存(Android 8+ 位图住 native heap,Coil/Glide 同池);服务端不给图片像素尺寸(原生一样等解码回调、一样要尺寸记忆表);中文长标题 StaticLayout 测量成本(天花板由信息密度决定,不由框架决定);120Hz 缓冲队列零余量(anzong 单峰说明原生把每帧成本压低就不振荡,但队列机制与 8.33ms 预算是平台行为);「原生 pager 才顺滑」已在 RN 内被证伪一次(pager-swipe 票 04:真正机制是翻页瞬间没有 React 工作,不是容器魔法)。

**救得了**(有量化证据的只有一项+衍生):冷启首次进屏 31ms Hermes 冷路径;整套分帧揭示补丁可拆;buildQuoteIndex 20-40ms 阻塞可下后台线程;renewTransport 真实现。

**一句话**:滚动、翻页、drop 簇在 RN 里已经打赢——重写把它们重新置为待验证(不回退条款的由来);重写的确定收益是冷启冻结与整套 JS 单线程补丁的拆除。
