# 30 — P2:游客态「新建收藏夹」是个死钮,点「创建」毫无反应

**Status:** open

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
