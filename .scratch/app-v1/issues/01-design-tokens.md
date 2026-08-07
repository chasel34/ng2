# 01 — 设计 token 与主题基建

**What to build:** 打开 app,骨架页完整呈现设计稿的墨绿/奶油视觉;切系统深色模式或手动切换后,整套颜色无缝变为深色值。所有后续页面从同一 token 源取色/字号/圆角/阴影,不允许散落魔法值。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 浅/深两套 token 与设计稿 Design Token 表逐项一致(16 色 + 字号/行高 + 圆角/间距/阴影)
- [x] 主题跟随系统,亦可被后续设置页手动覆盖(接口预留)
- [x] 骨架页在浅/深色下与设计稿原型对应 token 目视一致

## Comments

### 实现摘要

- `src/ui/tokens.ts` —— 唯一 token 源。颜色取自设计稿 `:root`(浅)/ `.omdark`(深) 两个声明块,
  字号/圆角/间距/阴影取自「Design Token 表」的 `T.tokenColors` / `T.tokenType` / `T.tokenBox`。
  导出 `lightTheme` / `darkTheme` / `themes`,两套主题共享同一份字号/圆角/间距对象。
- `src/store/theme.ts` —— Zustand `useThemeMode`,模式为 `system | light | dark`,默认 `system`;
  `resolveColorScheme(mode, systemScheme)` 是纯函数,可单测。
  持久化按票面要求只留接口不落盘:`ThemeModeStorage` + `connectThemeModeStorage()`,22 接 MMKV 时实现它即可。
- `src/ui/theme.ts` —— `useTheme()` 合成「系统色 + 手动覆盖」;`createThemedStyles(factory)` 生成随主题走的样式表 hook,
  每套配色只 `StyleSheet.create` 一次并缓存。后续页面一律用这个写法,不要自己拼颜色。
- `src/app/_layout.tsx` —— Stack `contentStyle` 与 `expo-system-ui` 根背景都接上 `colors.bg`,消除切换时的白闪。
- `src/app/index.tsx` —— 骨架页兼 token 样板:顶栏/Tab/公告条/卡片各取一档颜色、字号、圆角、阴影。
  底部三枚「跟随系统 / 浅色 / 深色」切换是票面 What-to-build 里「或手动切换后」那一条的现场验收入口,真机上不用去改系统设置就能验深色。

测试:`src/ui/tokens.test.ts` 把设计稿三张表当 fixture 逐值比对(含「没有多余自造色」的断言),
`src/store/theme.test.ts` 覆盖模式合成与持久化接口的接入/断开。共 40 例,`pnpm typecheck` 干净。

### 设计稿缺项 / 歧义与我的决定

1. **token 表只列 16 色,但 `:root` 声明了 23 色。** 多出的 `--primary-d`、`--on-primary`、`--topbar`、
   `--on-topbar`、`--fab`、`--on-fab`、`--track` 在原型 markup 里都在用(顶栏深色下要压成近黑,不是 primary),
   全部收进 `ColorTokens` 并在测试里单列一组,标明它们不在 token 表内。
2. **`radius/sm 8–9` 是区间。** 取上界 9(token 表自己的色块用的就是 9px)。
   另外补了 `full: 999` 给圆形图标按钮——设计稿里是 46/44 见方配 23/22 圆角,共出现 44 次,属于成规模的既有模式。
   一度还加过 `xs: 4`,自查时发现 4 是「space 4·8·12·16·20」那行的间距值、不是圆角档,已删。
3. **设计稿实际用到的字号多于 token 表的 6 档。** 典型是公告条的 13.5px/1.5,六档里没有。
   骨架页按票面「不允许散落魔法值」用了最近的 `note`(12.5),没有为了像素级还原去写 13.5。
   **遗留**:04/05/07 铺真实页面时会再撞上这个缺口,届时要么补档进 `typography` 并回写设计稿,要么统一归到现有档位——建议前者,别在页面里散写。
4. **行高在设计稿是倍数,RN 要绝对像素**,已按 倍数 × 字号 换算:16×1.45=23.2、15.5×1.68=26.04、12.5×1.65≈20.63。
   设计稿没标行高的三档(title / tab / meta)不给 `lineHeight`,交给系统默认。
5. **阴影用 RN 0.86 的 `boxShadow` 字符串**(New Arch 唯一,Android 支持),与 `--shadow` / `--shadow-2` 字面一致,只把 `0` 补成 `0px`、`.13` 补成 `0.13`。
6. **公告条的 `campaign` 图标暂无替身**:图标字体还没接入,骨架页先用 accent 色条占位,代码里已注明,04 接入图标后替换。

### 遗留问题

- `connectThemeModeStorage` 的 `storage` 是模块级单例。22 接 MMKV 时必须在模块作用域或根组件挂载时调用一次,
  别塞进深层 effect,否则 Fast Refresh 之后持久化会静默失效(已在函数注释里写明)。
- 状态栏固定 `style="light"`:浅色下顶栏是墨绿、深色下是近黑,两种都需要浅色图标,所以不随主题变。
  若 22 之后出现浅底顶栏的页面,这里要改成跟着 `colors.topbar` 走。
- 骨架页只是样板,04(版块树+抽屉)会整体替换 `src/app/index.tsx`;`_layout.tsx` 的主题接线要保留。
