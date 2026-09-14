VERDICT: FAIL

评审范围：04 票及 `04-impl.md` 两份改动清单中的 git diff、未跟踪新文件；未修改仓库源码、测试或构建配置。上一轮三项问题已修复，本轮不重复列为缺陷。

## 需要修改的问题

1. **[P2] 用户滚回回答底部后，后续流式内容不再自动跟随。**
   - 位置：`app/src/main/kotlin/com/chasel/ng2n/ui/ai/TopicAiSheet.kt:79`–`80`。
   - 问题：`stick` 只在 `isScrollInProgress` 变为 true 时根据起始位置更新。用户先查看较早内容，再从远离底部的位置滑回底部时，`stick` 被设为 false；抵达底部及松手都不会重新计算。后续 `state.turns` 和 `scroll.maxValue` 更新因此一直跳过自动滚动，用户已经回到底部，仍必须不断手动滑动才能看到新回答。
   - 实际复现：Pixel 8 / Android 17 模拟器上，以完整 `TopicAiSheetContent` 渲染 50 行回答。初始距底部 0 px；下滑查看旧内容后为 1030 px；上滑回到底部后为 0 px；追加 12 行模拟流式文字后变成 **984 px**，没有跟随。临时 Compose 测试 `reachingBottomResumesFollowingNewText` 在最终断言失败：`expected:<0.0> but was:<984.0>`。整个过程没有调用模型。
   - 修复建议：按用户滚动过程中的实际位置及结束位置维护跟随状态，回到底部时恢复跟随；区分用户滚动与程序滚动，避免新增内容使 `maxValue` 增大时误关跟随。补充“查看旧内容 → 滚回底部 → 继续收到流式文字”的回归测试，同时确认查看旧内容时不会被新文字强制拉回底部。

## 核实记录

- 已重新阅读 `CLAUDE.md`、04 票、实现清单、`koog-best-practices.md` 与 `cost-and-recovery.md`，核对技术方案、ADR 和用户文档中的引用、渲染、读取范围及取消契约。
- 上一轮修复已核实：筛选入口保留完整参数，单楼按 pid 定位且缺失时不请求模型；同页码的筛选页参与合并；当前页首图按楼层原始顺序选取；围栏及行内代码保留文字，来源标记仍校验。相关新增 JVM 与 Compose 回归通过。
- 本票 core 文件没有 Android 导入；论坛读取沿用仓库及显式 READ 接口，未新增 WRITE；无新增依赖版本或 R8 规则；检查了注释约定、主题 token/阴影及文档同步。持久化、论坛工具和费用账本属于后续票，不作为本票遗漏报告。
- 使用 JDK 17、已安装的 Android SDK，移除 `NGA_INTEGRATION`、`NGA_WRITE_SMOKE` 后执行 `./gradlew --offline :app:testDebugUnitTest --rerun :app:assembleDebug`：成功；单测实际重新执行，XML 汇总 **1156 项，0 失败，0 错误，4 跳过**。
- 本轮运行现有 `AiMarkdownTest`、`TopicAiSheetTest`：**4 项通过**。同次运行最初因临时探针未加入 Kotlin 源集而额外产生一个类加载错误；修正系统临时目录中的装配后单独重跑探针，得到上述真实滚动断言失败，类加载错误不作为产品问题。
- 临时测试与 Gradle init 脚本仅放在系统临时目录，通过 `-I` 加入测试源集；没有改动仓库文件。失败证据位于 `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8(AVD) - 17-_app-.xml`，对应 logcat 记录了 1030 → 0 → 984 px 三个距离。
- 清单中 36 个文件均存在，限定清单的 `git diff --check` 通过。
- 未进行真机、真实 NGA 或付费模型验证，未重跑 Release；票中的真机人工验收仍未完成。
