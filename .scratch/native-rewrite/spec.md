# Spec — NG2 原生 Android 重写(android-native 分支)

**确认**:2026-08-22 所有者批准全部条款(19 问共识,过程见会话纪要)。
**当前状态**:立项落盘完毕(ADR-0003/0004、本 spec、19 张票、探查报告归档)。**所有者指示:建完票先不开工**——M0 等指令。

## 一、目标与边界

1. 全量重写为 Kotlin 原生 app,**不设尖兵闸门**;若原生仍卡,在原生基础上继续优化,**不回退 RN**(ADR-0003)。
2. 功能与信息结构 1:1;视觉尽量还原;交互细节允许 Android 原生惯例(如预测性返回)。
3. Android-only;RN 版冻结在本分支作移植参照。自用为主。
4. **冷启首屏 2.4–2.8s 不进本次验收**(是 NGA RTT + 反封锁链时间,证据见 research/perf-history.md §八);「cache-first 首屏」「后台通知推送」等进重写后 backlog。
5. 已知缺陷**不随迁**,移植时修掉并记账:P1-01 写操作禁入格式轮换/换账号、P1-02 收藏缓存按 uid 隔离、P1-03 Cookie 生命周期统一(自管 CookieJar)、P1-04 诊断日志脱敏、子版块属性白名单误报、P2-04 冷启同步 IO、P3-05 用户正则资源上限。
6. 参考 app anzong(`gov.anzong.androidnga`,GPL v2)**只抄思路不抄代码**。

## 二、技术栈

见 ADR-0004。要点:Kotlin 2.4.10 / AGP 9.3.1 / compileSdk 37 / target 36 / min 31 / Compose BOM 2026.08.00 / Nav3 1.1.6 / Hilt / **裸 OkHttp 5.5(无 Retrofit)** / kotlinx.serialization / Coil 3.5 / Room + DataStore / Macrobenchmark 1.4.1;MVVM + Flow;预测性返回开;逃生舱条款(局部可降级 View/RecyclerView)。

## 三、身份与仓库

- 分支 `android-native`,原生工程在 `native/`(`android/` 被 gitignore 占用)。
- applicationId `com.chasel.ng2.n`,显示名 **NG2N**,与 RN 版并行安装;重新登录,不迁本地数据。
- 并行期**不注册** NGA 域名深链(只留 `ng2n://` scheme),切换期结束再接管。
- release 沿用现 keystore(小米真机 `install -r` 保登录态的既有打法)。

## 四、移植铁律

- 协议半壁「**照抄不简化**」:反封锁链五段、接口粒度组合缓存(10min TTL、不持久化)、GBK 逐参数策略、清洗六步、「未登录=可重试」、假错误白名单、orderedEntries 对象/数组双兼容。`docs/API文档.md` 与 ADR-0002 是一等移植文档。
- 逆向算法怪癖(骰子共享随机流、匿名还原跳 hex[5]、彩色标题 TLV、附件目录固定 UTC+8、双重实体解码)——NGA 就这样,**别修**。
- 正文渲染零 WebView:BBCode→AST(29 节点)→Compose(ADR-0001 结论沿用,实现重写)。
- anzong 四原则进渲染设计:后台一次性预转换、渲染产物页级常驻、滚动路径零计算、文字先出图片后到。
- M1 的 web 反解开工前,先对线上重新抓包验证 `commonui.postArg.proc` 参数位置表(全网无第二份文档)。

## 五、验证与验收

- **金样本对拍**(票 05):TS 实现当 oracle,fixtures 全量复用,纯函数逐条对拍 JSON;策略链控制流回归手工移植。
- **功能验收**(票 18):Pixel_8 AVD,24 屏 checklist(由 research/inventory.md 生成)。模拟器永不裁性能。
- **性能验收**(票 19):小米 17(120Hz)真机、release 包(R8 + Baseline Profile)、每场景 30s 脚本化、与并装 RN release 版及 anzong 对拍。**十场景闸**:
  1. 冷启动:录屏逐帧无闪烁帧(无白/黑闪、无内容两跳突现);
  2. 冷启后首次进主题:起手冻结 ≤1 丢帧(RN 现状 ~31ms≈4 帧@120Hz);
  3. 主题列表快甩:janky ≤1%;
  4. 楼层流慢拖/快甩:不差于 RN 基线 0.1%/2.7%(**不回退条款**);
  5. 横滑翻页:速度曲线连续、松手丢帧 ≤1;
  6. 抽屉开合:与 anzong 逐帧对拍无可见差;
  7. 附件展开/收起动画;
  8. 打开大图/画廊开合与缩放;
  9. 各转场 latch2present 单峰、无 >2 vsync 连续丢帧;
  10. **伞条款**:遍历一切带动画交互(对话框、FAB、下拉刷新、页码条、tab 高亮……),任一处肉眼可见断续即不过闸。
- 判据与陷阱以 `docs/perf-playbook.md`(票 02 落盘)为准;NGA 限流冷却 ≥60s;debug 包与模拟器数据永不用于性能裁决。

## 六、里程碑与票据索引

| 里程碑 | 票 |
|---|---|
| M0 骨架与测量基建 | 01 工程骨架 · 02 测量基建 |
| M1 协议层 | 03 字符集 · 04 清洗/信封/错误 · 05 金样本管线 · 06 反封锁链 · 07 端点层 · 08 Web 反解与重验 |
| M2 阅读垂直切片 | 09 BBCode 解析器 · 10 本地算法 · 11 渲染器 · 12 图片 · 13 主题详情屏 |
| M3 全屏幕铺开 | 14 存储 · 15 登录与多账号 · 16 首页/版块 · 17 其余屏幕 |
| M4 双验收 | 18 功能验收 · 19 性能验收 |

## 七、移植风险 TOP5(探查结论)

1. 反封锁链语义细节(哪些错误可重试)——照抄,回归测试一起移植;
2. P1-01 写操作重放——移植时修,禁入轮换;
3. GBK 逐参数不是全局——四个环节漏一个就是「偶发乱码」级难查 bug;
4. OkHttp cookie jar 静默覆盖手设 Cookie 头——双通道认证照抄,自管 CookieJar;
5. 逆向算法无第二份文档——`read-html.ts` 参数位置表移植前重验。

## 八、研究归档

- `research/inventory.md` — RN 版现状全景(24 屏、25 端点、渲染/存储/动画全量盘点)
- `research/perf-history.md` — 六轮性能战役史、open 卡顿清单、测量方法论、「原生救得了/救不了」账本
- `research/anzong.md` — 参考 app 源码研读(顺滑机制清单、反面清单、GPL 边界)
- `research/stack-2026-08.md` — 技术栈逐项核实与 libs.versions.toml 草稿
