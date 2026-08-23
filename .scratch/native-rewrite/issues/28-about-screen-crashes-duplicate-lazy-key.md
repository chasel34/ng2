# 28 — P0:进「关于」屏必崩(LazyColumn key `disclaimer` 重复)

**Status:** resolved

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

## Comments

### 2026-08-23 · 修复(票 28)

**根因照票面**:`AboutScreen.kt` 里行表最后一行与页脚那段免责文案都用 `disclaimer`
当 key,同一个 `LazyColumn` 里重复 → Compose 在首次布局抛
`Key "disclaimer" was already used`,进程没了。

**改法**:页脚那一项改成 `disclaimer-footer`,并把关于屏的 key 全部摊进
`AboutKeys`(`ui/about/AboutScreen.kt`),`item(...)` / 行表都改用常量。

**顺带做的(主控要求的「同类隐患」走查)**

grep 了全部 `LazyColumn` / `LazyRow` / `LazyVerticalGrid` 的 `item(key=…)`、
`items(key=…)`,逐屏核对静态 key 与数据行 key 会不会撞:

1. **静态 key 摊成常量 + 单测查重**。新增 `ui/common/ScreenListKeys.kt`:
   - `ListKeys` —— 列表屏共用的非数据行 key(`sub`/`tail`/`footer`/`empty`/`hint`/
     `head`/`sub-boards`/`header`/`intro`/`footnote`、通知分组头 `groupHead()`);
   - `SCREEN_LAZY_KEYS` —— 逐屏的静态 key 清单(关于 / 设置 / 实验室 / 字号 /
     屏蔽规则 / 版块 / 历史 / 缓存 / 收藏夹管理 / 通知 / 主题详情);
   - `duplicateKeys()` —— 查重函数。
   设置那一族三屏还共用 `SettingsShell` 补在末尾的 `SETTINGS_TAIL_KEY`,清单里都带上了。
   **只收「同一张表里有两个及以上静态 key」的屏** —— 只有一个静态 key 的屏
   (搜索/收藏/热帖/精华的 `footer`、子版块的 `footnote`、回复链的 `intro`)自己撞不了,
   数据行 key 是 tid/pid(数字)也撞不上字符串。
2. **数据行 key 的三处真隐患,一并堵了**(都是「服务端给重了就崩」,不是写错字面量):
   - **云端屏蔽词**(`FiltersScreen`):`items(key = { list.words[it] })` 拿关键词原文当 key。
     官方屏蔽表是空格分隔的一行文本,同一个词加两遍在网页版那边合法;而且用户把
     `hint` 加成屏蔽词就会与状态行的 `hint` 撞。→ 数据行 key 一律带前缀
     (`FiltersKeys.word/user/rule`),并在铺进列表前 `distinctBlockWords` /
     `distinctBlockUsers` 去重(只影响显示,写回云端的仍是仓库里那张原表)。
   - **通知屏**:行 key 是通知稳定 id,`get_all` 三个容器装的是同一批通知的不同视图
     (分类看类型码不看容器),同一条可以出现两次 → `dedupeNotifications()` 按 id 去重。
   - **首页宫格**:格子 key 是 `cell/<组>/<版块>`,服务端版块树里同组出现同一个版块
     (或收藏表里同 id 出现两条)就会重 → `buildHomeRows` / `buildFavoriteRows`
     返回前 `distinctBy { it.key }`。
   这三处 RN 版都会崩不了(LegendList 遇到重复 key 只打警告),所以在 RN 侧从没响过。
3. 其余 `items(key=…)` 核过:主题列表 tid(`mergeTopicPages` / `aggregateHotTopics`
   已按 tid 去重)、楼层 pid、「我的帖子」pid、子版块 `kind/id`、收藏夹 id —— 无隐患。

**单测**:`app/src/test/kotlin/com/chasel/ng2n/ui/common/ScreenListKeysTest.kt`(8 条)。
把 `AboutKeys.FOOTER` 改回 `"disclaimer"` 时第一条用例当场红 —— 就是这次崩溃的回归闸。

**没做的**:票里建议的关于屏 Compose UI 冒烟测试。模块的 `androidTest` 里只有
espresso,**没有 compose-ui-test / Robolectric 依赖**,而且 instrumentation 测试得有设备
(本票明确不用模拟器)。为一条冒烟引一套 UI 测试框架超出本票范围,那一屏的真机走查
归票 18 的 checklist #24。改成「key 清单查重」的
JVM 单测,拦的是同一类崩溃且不引新依赖。要补 UI 冒烟请另开票。

**票外发现**:`ui/topic/FilterBridge.kt` 与 `data/filters/FilterRepository.kt` 各有一份
存储↔判定形态的映射(`toMatchRule`/`toStoredRule` vs `toCore`/`toStored`),内容一样。
本票不动(票外重构)。
