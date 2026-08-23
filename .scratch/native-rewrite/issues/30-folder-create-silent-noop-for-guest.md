# 30 — P2:游客态「新建收藏夹」是个死钮,点「创建」毫无反应

**Status:** resolved

**Severity:** P2(死控件 + 零反馈;同一个 app 里屏蔽规则页对同一情形是有引导的,不一致)

## 现象

游客态进「收藏夹管理」,屏幕中央写着「登录后才能管理云端收藏夹 / 去登录」——
但**右上角的「新建收藏夹」钮照常可点**,点开是完整的新建对话框(带「最多 20 个收藏夹」提示),
填名字点「创建」**什么都不发生**:对话框不关、不报错、没有提示条、没有 Toast,
也没有任何网络请求。用户只能自己点「取消」。

## 复现(模拟器 emulator-5554,com.chasel.ng2.n debug,游客态,2026-08-23)

1. 抽屉 →「收藏夹」→ 顶栏「收藏夹管理」(或抽屉直接进「收藏夹管理」)
2. 点右上角「新建收藏夹」
3. 输入 `probefolder`,点「创建」

**期望**:像屏蔽规则页那样给一句「登录后才能…」+「去登录」,或者干脆在游客态把这个钮藏了/禁用。
**实际**:静默无反应,连点两次也一样(截图 F2/F3/F4)。

## 根因(已定位到行)

`ui/lists/FavoriteFoldersScreen.kt:99-101`,四个写操作共用的善后函数:

```kotlin
fun run(done: String, action: suspend (String) -> Unit) {
  val currentUid = uid ?: return      // ← 游客态在这里直接 return,一声不吭
  busy = true
  …
}
```

`uid` 来自 `currentAccountOf(accountsState)?.uid`(同文件 :86),游客态是 null。
「新建 / 重命名 / 设默认 / 删除」四件事**全部**经过这个 `run`,所以四个都是同样的静默死路。

对照组:`ui/filters/FiltersScreen.kt:294` 同一情形是有话说的 ——

```kotlin
!signedIn -> showLoginPrompt(nav, "登录后才能写官方屏蔽词")
```

改法二选一(建议后者,和屏蔽规则页对齐):
- 游客态不画那个顶栏钮;或
- `run` 里 `uid == null` 时走 `showLoginPrompt(nav, "登录后才能管理云端收藏夹")`。

## 顺带

「收藏夹」屏(`FavoritesScreen`)游客态没有这个问题 —— 它没有写操作入口,
只有空态「登录后才能看云端收藏夹 / 去登录」,是对的。

## Comments

**完成摘要(B 段,2026-08-23)**

按票里建议的第二种改法(与屏蔽规则页对齐)。新增 `ui/common/SignedInGate.kt`:

```kotlin
sealed interface SignedInGate {
  data class Proceed(val uid: String) : SignedInGate
  data class NeedLogin(val message: String) : SignedInGate
}
fun signedInGate(uid: String?, message: String): SignedInGate
```

`ui/lists/FavoriteFoldersScreen.kt` 的 `run()` 里 `val currentUid = uid ?: return`
换成过这个闸门:游客态**先收对话框、再 `showLoginPrompt(nav, "登录后才能管理云端收藏夹")`**。
新建 / 重命名 / 设默认 / 删除四件事全走 `run()`,所以四个入口一次全好。

**关键决定**

- **没有藏顶栏那个钮**(票给的第一种改法)。理由:钮藏了,游客连「这 app 有收藏夹」
  都看不见,而屏中央的空态本来就在讲「登录后才能管理云端收藏夹 / 去登录」——
  两处话术一致、都递得出登录页,比少一个钮更说得通。这也是屏蔽规则页的既定范式。
- **先 `dialog = null` 再弹提示条**:snackbar 挂在屏级,对话框压在它上面,
  不收对话框的话用户看不到那句话 —— 那就等于把「静默」换成了「看起来还是静默」。
- 空态文案与游客提示语提成同一个 `private const val GUEST_PROMPT`,免得日后改一处漏一处。
- **闸门为什么不是一句 `if (uid == null)`**:门控本来只存在于 Compose 函数体里,
  JVM 单测够不着;提成有返回值的纯函数之后才钉得住「游客拿不到成功话术」这条(见票 31)。

**有意偏离 RN 版**

- RN 版 `src/app/favorites/folders.tsx` 游客态同样是静默的(这是 spec 说的「已知缺陷不随迁」
  那一类),这里按票修好,不照抄原行为。

**单测**

`app/src/test/kotlin/com/chasel/ng2n/ui/common/SignedInGateTest.kt` 5 条(与票 31 共用):
游客出 `NeedLogin` 且话术原样带出、登录态出 `Proceed` 且 uid 原样、两个入口各说各的话、
「过了闸门的游客不会看到成功话术」、判据是 `null` 而不是 `isNullOrEmpty`。

**没做 / 待所有者**

- 没开模拟器(派活要求),所以「点创建 → 出提示条 → 点去登录 → 到登录页」这条链
  **没有实机截图**。逻辑侧有单测,UI 侧是复用全 app 同一个 `showLoginPrompt`。

**票外发现**

- 无。
