VERDICT: FAIL

1. **P2 — Key 读取发生 IOException 后，成功重新保存也无法恢复当前页面状态。**

   位置：`app/src/main/kotlin/com/chasel/ng2n/data/ai/settings/AiKeyStore.kt:34–36`；关联 `app/src/main/kotlin/com/chasel/ng2n/ui/settings/AiSettingsViewModel.kt:45–50`。

   `catch` 发出 `Unreadable` 后结束整个 Flow，ViewModel 中唯一的 Key 状态收集协程随之结束。若首次读取遭遇暂时性 IO 错误，用户按页面提示重新输入，后续 `saveKey()` 即使成功落盘、关闭对话框，也不会重新订阅或更新 Key 状态；页面持续显示“无法读取已保存的 Key，请重新输入”，必须退出重进才能恢复。这与本票保存后显示遮罩及异常恢复行为不符。此问题针对 DataStore 读取 IOException；普通密文解密失败返回 null 的路径不会终止订阅。

   核实：在 `/tmp/ng2-03-review-probe/KeyRecoveryProbeTest.kt` 用首次读取抛 IOException、随后恢复正常的 DataStore 替身调用实际 `AiKeyStore`。原订阅仅收到 `[Unreadable]` 并完成；之后保存成功且 `readKey()` 返回新值，新订阅得到 `Saved`，原订阅仍已结束。独立 JVM 验证通过，确认问题可复现。现有测试只覆盖解密失败后替换，没有覆盖这一读取异常路径。

   修复建议：在用户成功保存或主动重试时重新建立 Key 状态订阅，或者使读取错误后的观察支持受控恢复；确保成功保存后当前页面显示遮罩和“加密保存在本机”。补充“首次读取 IOException → 恢复存储 → 原页面保存新 Key → 状态更新为 Saved”的回归测试，并保留取消传播。

评审范围为 `03-impl.md` 清单中的本票差异及未跟踪源码、测试、文档和验证材料。已对照 CLAUDE.md、票据验收项、koog-best-practices.md、cost-and-recovery.md 和 Settings 设计结构。未修改项目代码。本票没有新增依赖、core Android 引用或 NGA 请求；未发现其余需要修改的分层、READ/WRITE、注释、版本目录或文档同步问题。额度执行和用量记账按票据范围留给后续接入，不将其缺失计为本票问题。

实际验证（JDK 17，Android SDK，离线）：

- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest`：成功，首次执行命中已有任务结果。
- `ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:testDebugUnitTest --rerun :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.ai.settings.AiKeyKeystoreTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest`：成功；JVM 1131 项，1127 通过、4 跳过、0 失败/错误；Pixel 8 AVD / Android 17 的 2 项设备测试通过。
- 独立故障注入使用临时 Gradle init script 加载 `/tmp` 测试源，未向项目添加测试代码；验证后移除临时源集配置并重新运行正常单测。
- 已检查真实 Keystore 重建读取、密钥丢失后替换、密文落盘、日志脱敏、四档和每日额度持久化的测试实现与结果，复核明暗主题及暗色 Key 对话框截图。
- 本票已跟踪差异的 `git diff --check` 通过。未调用真实模型或论坛写接口。
