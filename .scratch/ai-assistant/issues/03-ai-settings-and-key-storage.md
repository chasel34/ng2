# 03 — AI 设置页与 Key 存储

**What to build:** 设置里新增「AI 助手」页，对应设计稿 Settings 的「模型服务」与「开销控制」两段。用户可以输入 DeepSeek API Key，保存后以遮罩形式显示并标注「加密保存在本机」，对话框支持显示/隐藏与取消/保存；Key 用 Keystore 加密单独存储，不与论坛账号档案混放，不进入诊断日志。服务商固定 DeepSeek 官方并说明其他服务商后续提供；默认模型展示 deepseek-flash 及其能力说明。单次分析额度提供四档选择（短问答 / 默认 / 长楼与个人分析 / 更高），每日额度提供开关与上限，关闭时显示「未设每日上限」。「今日用量」段本票只做零态，明细与记账由票 09 接入。「对话历史」段由票 07 接入。

**Blocked by:** None — can start immediately

**Status:** implemented

- [x] Key 保存后重启应用仍可读取；密文落盘、密钥别名带版本；解密失败时页面提示重新输入而不是崩溃
- [x] 诊断日志脱敏单测：Key 与设置项值不会原样进入日志
- [x] 单次额度、每日额度开关与上限持久化在 DataStore，键名带版本
- [x] 设置页视觉沿用 Tokens 与设计稿结构；暗色下正常
- [x] 存储说明文档同步新增的 Key 存储与设置键

## Comments

### 2026-09-13 实现与验证

已实现设置 → AI 助手入口、模型服务与开销控制两段、今日用量零态。DeepSeek 官方及 deepseek-flash 固定展示，说明图片、工具调用与思考能力。Key 编辑支持显示／隐藏、取消／保存，只有成功落盘后关闭；已保存 Key 使用固定遮罩并注明「加密保存在本机」。

Key 使用独立 `ng2n-ai-keys` DataStore，键 `ai.deepseek.apiKey.v1`，复用 AES-256-GCM Keystore 实现并使用独立版本别名 `ng2n.ai.keys.v1`，不输出加解密日志、不进入论坛账号档案；解密失败提示重新输入，文件结构损坏转为不可读标记后允许替换，加密／写入失败不覆盖原配置。输入只放当前对话框内存，不进入可恢复状态存档。

单次额度四档全部可选并持久化。每日额度默认关闭，首次启用需输入正美元金额，按整数美分保存，开关与首次上限原子更新；关闭保留上限并显示「未设每日上限」。遵循需求与设计稿的待定说明，未将示例金额写成正式单次额度默认值。用量展示零态，不展示示例账单；历史与记账按票据范围留给 07／09。存储文档同步了版本键、错误恢复、日志边界与通用重置不清除 AI 配置的行为。

验收结果：

- Key 加密落盘及重新读取：真实文件关闭重开单测通过；模拟器真实 Android Keystore 的 DataStore／crypto 重建读取、删除测试密钥后提示不可读并重新输入通过。
- 脱敏：Key、Authorization 与全部新增 AI 设置键值的诊断格式化／摘要测试通过。
- DataStore：四档选择、每日开关／上限、关闭保留上限、非法金额拒绝与加密失败保留旧值测试通过。
- 视觉及交互：复用 SettingsShell、设置选择框、开关、DialogShell 与项目 Tokens；模拟器验证输入显隐、取消不写入、保存、额度选择、每日上限及关闭零态，明暗主题及暗色对话框截图已人工复核。
- 文档：`docs/storage.md` 已更新。

验证命令与结果：

```bash
ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.ai.settings.AiKeyKeystoreTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest
```

使用 JDK 17，最终 `BUILD SUCCESSFUL`。完整 JVM 测试 1131 项，1127 通过、4 项联网冒烟按开关跳过、0 失败／错误；本票新增 4 项单测均通过。Pixel 8 AVD / Android 17 上 2 项设备测试均通过。`git diff --check` 通过。未调用真实模型或论坛写接口。本票没有 release 构建验收要求，因此未执行 `:app:assembleRelease`。未执行 git commit，保留工作区原有改动。

验证日志、测试 XML 和截图保存在 `.scratch/ai-assistant/reports/03-validation/`。

改动文件清单：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/settings/AiKeyStore.kt`
- `app/src/main/kotlin/com/chasel/ng2n/data/ai/settings/AiSettingsStore.kt`
- `app/src/main/kotlin/com/chasel/ng2n/di/AiSettingsModule.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/AiSettingsViewModel.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/AiSettingsScreen.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/SettingsEntries.kt`
- `app/src/main/kotlin/com/chasel/ng2n/ui/settings/SettingsScreen.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/settings/AiSettingsStorageTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/data/ai/settings/AiKeyKeystoreTest.kt`
- `app/src/androidTest/kotlin/com/chasel/ng2n/ui/settings/AiSettingsScreenTest.kt`
- `docs/storage.md`
- `.scratch/ai-assistant/issues/03-ai-settings-and-key-storage.md`
- `.scratch/ai-assistant/reports/03-impl.md`
- `.scratch/ai-assistant/reports/03-validation/ai-settings-dark.png`
- `.scratch/ai-assistant/reports/03-validation/ai-settings-key-dark.png`
- `.scratch/ai-assistant/reports/03-validation/ai-settings-light.png`
- `.scratch/ai-assistant/reports/03-validation/device-tests.xml`
- `.scratch/ai-assistant/reports/03-validation/storage-tests.xml`
- `.scratch/ai-assistant/reports/03-validation/validation.log`

### 2026-09-13 第一轮评审修复

接受 `03-review-1.md` 的 P2 问题，无异议项。原实现捕获读取 IOException 后结束状态 Flow，导致同一页面保存成功仍显示无法读取。现将存储观察放在保存版本信号的顺序收集内：IOException 只结束当前读取，外层订阅保持；成功落盘后更新版本并重新读取，原订阅收到 `Saved`，当前页面恢复遮罩及「加密保存在本机」。失败保存不更新版本，不触发重新读取；正常存储观察仍直接接收 DataStore 更新。

新增两项回归测试：首次读取 IOException 后原订阅仍存活，加密失败不触发恢复，随后成功保存使原订阅收到 Saved 且新 Key 可读取；读取 CancellationException 直接传播，不转换成 Unreadable。首版恢复实现曾被取消回归测试检出异常未传播，已修正并通过最终复测。存储文档同步说明受控恢复行为。

最终离线验证（JDK 17）：

```bash
ANDROID_HOME=/Users/cola/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.ai.settings.AiKeyKeystoreTest,com.chasel.ng2n.ui.settings.AiSettingsScreenTest
```

结果：`BUILD SUCCESSFUL`，debug 构建通过；JVM 1133 项，1129 通过、4 项联网冒烟按开关跳过，0 失败／错误；AI 存储测试共 6 项全部通过。Pixel 8 AVD / Android 17 的 Key Keystore 与设置界面设备测试 2 项全部通过。`git diff --check` 通过。本票没有 release 构建验收要求，本轮未运行 release 构建。未调用真实模型或论坛写接口，未执行 git commit。

本轮最新改动文件清单（此前完整实现清单继续有效）：

- `app/src/main/kotlin/com/chasel/ng2n/data/ai/settings/AiKeyStore.kt`
- `app/src/test/kotlin/com/chasel/ng2n/data/ai/settings/AiSettingsStorageTest.kt`
- `docs/storage.md`
- `.scratch/ai-assistant/issues/03-ai-settings-and-key-storage.md`
- `.scratch/ai-assistant/reports/03-impl.md`
- `.scratch/ai-assistant/reports/03-validation/review-1/validation.log`
- `.scratch/ai-assistant/reports/03-validation/review-1/storage-tests.xml`
- `.scratch/ai-assistant/reports/03-validation/review-1/device-tests.xml`
