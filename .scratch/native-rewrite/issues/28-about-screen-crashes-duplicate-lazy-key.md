# 28 — P0:进「关于」屏必崩(LazyColumn key `disclaimer` 重复)

**Status:** open

**Severity:** P0(100% 复现的崩溃,整个 app 被打死;#24 关于屏完全进不去)

## 现象

抽屉 →「关于」→ **app 直接崩**,系统弹「NG2N keeps stopping」。
不是白屏、不是回退,是 `FATAL EXCEPTION: main`,进程没了。

```
java.lang.IllegalArgumentException: Key "disclaimer" was already used.
  If you are using LazyColumn/Row please make sure you provide a unique key for each item.
	at androidx.compose.ui.internal.InlineClassHelperKt.throwIllegalArgumentException(InlineClassHelper.kt:36)
	…
	at androidx.compose.foundation.lazy.LazyListKt…
	at androidx.compose.ui.platform.AndroidComposeView.dispatchDraw(AndroidComposeView.android.kt:2224)
```

崩在**首次布局/绘制**,所以关于屏一帧都没画出来。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. `adb shell am start -n com.chasel.ng2.n/com.chasel.ng2n.MainActivity`
2. 点顶栏抽屉钮
3. 点抽屉最后一项「关于」

复现率 **2/2**(14:58:13、14:58:48 两条 FATAL,栈完全一样)。
`adb logcat -b crash` 可直接看到。

**期望**:关于屏渲染版本号与依赖信息(票 18 checklist #24)。
**实际**:进程崩溃。

## 根因(已定位到行)

`ui/about/AboutScreen.kt` 里 `disclaimer` 这个字符串被当成 **两个** LazyColumn item 的 key:

- `AboutScreen.kt:136` —— 行表最后一项
  `AboutRow("disclaimer", Ng2nIcon.WARNING, "免责声明", detail = DISCLAIMER_DETAIL)`,
  这张表由 `AboutScreen.kt:200` 的 `items(rows.size, key = { rows[it].key })` 铺开;
- `AboutScreen.kt:275` —— 页脚那段居中免责文案
  `item("disclaimer") { Text(text = DISCLAIMER, …) }`。

两者在同一个 `LazyColumn` 里,Compose 一发现重复 key 就抛。

改法:给其中一个换 key(页脚那个改成 `disclaimer-footer` 之类)。
顺带**这一屏没有任何 UI 测试挡住这类问题** —— 关于屏是纯静态内容,一条
`composeTestRule.onNodeWithContentDescription(ABOUT_SCREEN_TAG).assertExists()`
的冒烟就能拦住,建议补上。

## 影响面

- checklist #24 两条(「只能从抽屉进」「版本号 / 依赖信息渲染」)全部验不了;
- 关于屏里还挂着「系统设置」「诊断日志」两个跳转入口,一并进不去。
