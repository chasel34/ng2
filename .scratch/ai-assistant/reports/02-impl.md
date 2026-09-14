# 02 — 抽取主题原始读取层实现报告

Status: implemented

### 2026-09-13 实现与验证

- 已将 `TopicRepository`、`TopicPageParams`、快照保存接口和缓存下载状态从 ui/topic 移到 data/topic。原始主题页、pid 定位读取、热门回复、已加载页查询、刷新与整帖缓存下载共用同一个 Hilt 单例，后续读取工具可直接注入该仓库。
- UI 新增 `TopicPageLoader`，保留正文渲染、引用预览请求去重与失败处理。主题详情通过它渲染，回复链直接读取 data 仓库；书签入口继续经 TopicKey / TopicViewModel 使用相同读取路径，书签摘要及跳楼行为不变。快照与 dispatcher 装配移到 di/TopicModule。
- 逐项验收：现有调用方已切换；data 无 UI 包依赖、core 无 Android 导入；读取层与现有回归测试通过；Room 表、迁移、持久缓存淘汰策略及 NGA READ 链没有修改。代码对照确认原始读取方法与渲染方法逻辑未改变：内存缓存仍为 40 条、2 分钟新鲜期、命中不调整淘汰顺序，前台快照延迟 320ms，整帖下载默认间隔 800ms。
- 已核对 design/ai-assistant/README.md 与主题、回复链相关设计。票 02 明确为“不引入任何 AI 代码”的预备重构，没有对应新增界面；保留现有展示，设计稿的 AI 面板、入口和来源预览按其所属后续票实施。
- 将原测试按读取层与 UI 渲染职责拆分，新增 5 项：pid 定位及 fav 参数与缓存复用、已加载页排序及共享对象、热门回复正文/作者/坐标、40 条缓存淘汰边界、前台延迟快照及过滤视图不写整页缓存。同步 README、技术方案与 Koog 方案的当前读取层说明。

验证结果：

- 使用本机已有 JDK 17、Android SDK，清空 `NGA_INTEGRATION` / `NGA_WRITE_SMOKE`，执行 `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：BUILD SUCCESSFUL。共 1127 项测试，1123 通过、4 项联网冒烟跳过，0 失败、0 错误。
- 读取仓库 14/14、UI 页面加载 3/3、TopicViewModel 33/33、仓库线程调度 9/9、缓存策略 15/15、缓存往返 7/7、READ 缓存策略 11/11、书签仓库 11/11 通过。
- 初次构建遇到沙箱 Gradle 锁文件权限及未配置 SDK 路径；使用获准的缓存访问与命令级 SDK 环境变量解决。测试拆分时缺少 TopicFixtures 导入导致编译失败，补齐后完整复跑通过。
- `git diff --check`、data/core 分层扫描、读取和渲染方法迁移前后对照、Room/缓存策略/READ 链未改动检查均通过。日志与统计见 `reports/02-validation/`。
- 票 02 未要求 release，未执行 assembleRelease；未运行设备或真实联网测试。未执行 git commit，保留工作区原有其他改动。

改动文件清单：

- `README.md`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicRepository.kt`（移除，拆为以下读取仓库及页面加载器）
- `app/src/main/kotlin/com/chasel/ng2n/data/topic/TopicRepository.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicPageLoader.kt`
- `app/src/main/kotlin/com/chasel/ng2n/di/TopicModule.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicDeps.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/TopicViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/topic/ChainViewModel.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicRepositoryTest.kt`（移除，按职责拆分）
- `app/src/test/kotlin/com/chasel/ng2n/data/topic/TopicRepositoryTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicPageLoaderTest.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicFixtures.kt`
- `app/src/test/kotlin/com/chasel/ng2n/ui/topic/TopicDepsFakes.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/RepositoryDispatcherTest.kt`
- `.scratch/ai-assistant/technical-design.md`
- `.scratch/ai-assistant/koog-best-practices.md`
- `.scratch/ai-assistant/issues/02-extract-topic-read-layer.md`
- `.scratch/ai-assistant/reports/02-impl.md`
- `.scratch/ai-assistant/reports/02-validation/offline-build-and-tests.log`
- `.scratch/ai-assistant/reports/02-validation/summary.json`
