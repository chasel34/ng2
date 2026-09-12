# 转场卡顿排查与修复(2026-08-21)

Status: 部分解决——量化指标大幅改善,但用户体感仍报告卡顿,遗留项见文末

## 症状(用户报告)

冷启动会话里进版块、进帖子、切换帖子页,**转场末尾都卡一下**;非冷启动轻一些但也有。
对照 anzong(原生 RecyclerView)无此问题。第一轮修复后复测:进版块「只有一点点
细微的卡顿」,**进帖子还是很明显**。

## 测量方法(按可信度排序)

1. **录屏逐帧**(感知层的 ground truth):`screenrecord`(本机 ~120fps VFR)→
   pyav 按 pts 算帧间 dt + 下采样灰度差分。「停格」= 运动窗口内 dt 大洞;
   「内容突现」= diff 爆点;静止画面的出帧空洞**不算缺陷**。
2. **gfxinfo framestats**:数 vsync 丢没丢(IntendedVsync 间隔 >13ms = 丢帧),
   但分不出「动画窗口内」和「静止画面」,单看会误判。
3. **Perfetto**(sched + atrace view/gfx,release 包靠 with-profileable):
   看 UI/JS 线程谁在忙。release 包 JS 线程无 systrace 段,用 thread_state 的
   Running 区间推断。
4. **JS 探针**(临时 console.log + performance.now(),验完即删):
   press→组件首次渲染的延迟、hooks 段耗时、commit 完成点。

## 根因(共四个,按发现顺序)

1. **数据到达帧一次挂载太多**(第一轮):转场动画本身满帧,卡的是网络数据落地
   那一帧——版块屏 18~30ms(版头+chips+列表壳同帧)、帖子屏单楼 26~112ms、
   翻页整页 40ms(屏级 useProgressiveReveal 只前进,翻页后 revealed ≥ 新页楼数,
   分帧完全失效)。
2. **rAF 分帧本身会滑 vsync**:逐帧 setState 的 render→commit→mount 流水线在
   8.3ms 预算下即使单帧只干 4ms 也常错过 vsync;片切得更小反而滑得更多。
   → 分帧必须发生在**动画结束后的静止画面**上(contentReady 闸),此时滑帧不可感知。
3. **入场首帧太重(进帖子起手冻 33ms)**:探针拆账 press→首帧 ≈ 导航分发 ~8ms +
   TopicScreen 渲染/commit ~9ms + mount ~6ms + draw;没有任何已挂载屏幕陪跑重渲染。
   版块屏同链路只要 13ms,差值全在 TopicScreen 自己的 chrome 上。
4. **buildQuoteIndex 撞上内容流入**:数据到达后 96ms 把全部楼层 BBCode 重新解析
   (JS 一口气 20~40ms),掐断楼层分帧流入的 rAF 节奏,录屏可见流入中段 24/44ms 停顿。

## 修复(全部已实施)

- `ui/progressive.tsx`:`useProgressiveReveal` 增加 `resetKey`(数据整换时重新武装)
  与 `skip`(scrollToIndex 场景直挂、撤掉后不回退)。
- `app/topic/[tid].tsx`:
  - 楼层分帧下沉进 TopicPageView(resetKey=detail.page;主动页 initial 1、
    相邻页 initial 0)——翻页/预取到达/跳页骨架换真数据全部分帧;
  - `instantReveal`(=resume.pendingScroll)保住带楼号跳转的 scrollToIndex;
  - chromeReady 分帧:首帧只挂顶栏+loading,页码条/FAB(连 useFabAnimation
    抽成 TopicFab 子组件)/「上次读到」浮层第 2 帧再挂;
  - chainIndex 起跑从 96ms 推迟到 1500ms(CHAIN_INDEX_DELAY_MS)。
- `app/board/[id].tsx`:补 contentReady 闸(push 时长+32ms,与 topic 同源);
  到达帧只挂列表壳,版头行、chips 条各占一帧,行每帧 +2。
- `ui/bbcode/render.tsx` + `ui/floor-card.tsx`:BBCodeBody 段级分帧
  (>8 段的长楼层,initial 4 / step 4;只有楼层顶层正文传 progressiveKey)。
- 试过无效已撤:启动后 `import()` 预热 board/topic 路由模块——冷态首次进帖子的
  起手停顿纹丝不动(成本在 Hermes 首次执行该屏路径,不在模块加载),_layout 留了备考。

## 验证结果(小米 25113PN0EC / 120Hz / 本地 assembleRelease)

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 冷启动进版块 | 到达帧 25ms 双丢帧 | 动画窗口零丢帧 |
| 非冷进帖子 | 单帧 112.5ms(定格~170ms) | 录屏无可见间隙(最大 14ms) |
| 点页码切页 | 单帧 40.2ms | 相邻页预挂载好,切换 ~5ms |
| 横滑翻页到达 | 单帧 41.6ms | 6~7ms |
| 楼层流入 | 24~44ms 停顿 | 消失 |
| 冷启动首次进帖子起手 | 33~39ms 冻结 | **~31ms,未解决** |

## 遗留(open)

1. **冷启动后首次进帖子仍有一次 ~31ms 起手冻结**(每进程一次;framestats 偶尔
   在非冷首次进新帖也抓到 33ms 级 gap)。已排除:模块加载(预热无效)、已挂载
   屏幕陪跑(探针安静)、TopicScreen chrome(已分帧)。剩余嫌疑:Hermes 首次
   执行该屏 hooks 路径(3 个 useTopicDetail 订阅、useReadingProgress 等)+
   导航分发。**用户复测后体感仍在报卡**——下一步候选:把 query 订阅拆到首帧后的
   子组件、Perfetto 对 mqt_v_js 在冻结窗口内的 Running 区间再细拆、
  或对拍 anzong 同指标(其板块列表被 NGA 按 app 限流,当晚未能取得基线)。
2. SwipePager settle 尾 commit ~17ms gap(亚像素段,基本无感,没动 SwipePager)。
3. `input swipe` 注入的拖拽段每隔一帧丢 vsync(t=100~250ms 区间),与真手指
   行为不同(memory:短时长注入不能当证据),未当缺陷处理。

## 复现/验收脚本

会话 scratchpad 里的 `analyze_framestats.py`(framestats 时间轴)与
`analyze_rec.py`(录屏 pts+diff)——不在版本库,下次要用按上面「测量方法」重写,
两个都只有 ~60 行。测量纪律:冷启动循环之间 ≥60s 冷却(NGA 限流)、
屏幕保持常亮(变暗锁 60Hz)、测前确认 `dumpsys window` 焦点是目标 app。
