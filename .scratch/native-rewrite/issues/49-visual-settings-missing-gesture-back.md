# 49 — P2:设置屏漏了「手势返回」开关

**Status:** invalid

**Severity:** P2(不是视觉差,是少了一项设置 —— 功能拿不到)

## 现象

原生设置「阅读」组的条目是:

```
自动加载下一页 / 仅 Wi-Fi 下加载图片 / 图片加载策略 / 显示签名档 / 阅读时常亮 / 字体和头像大小
```

**「手势返回 / 从左边缘右滑返回上一页」整条不存在**,直接从「显示签名档」跳到「阅读时常亮」。

RN 源码 HEAD 里它在:

- `src/app/settings/index.tsx:244` —— `label="手势返回"`,`value={settings.gestureBack}`,
  `onChange={(next) => setSetting('gestureBack', next)}`
- 位置就在 `阅读时常亮`(`:250`)**前面**,同属「阅读」组(`SettingsSection` 阅读 在 `:218`,
  通知 在 `:261`)
- `src/app/settings/index.tsx:55` 的注释:「『手势返回』『阅读时常亮』原先挂在实验室屏下」
  —— 两条一起搬到了主设置,原生只搬到了后一条

原生的「实验室与诊断」子屏里也没有它(那屏只有 网页数据源兜底 / Windows Phone UA /
本次运行的组合 / 导出诊断日志),**两处都没有**。

## 对照图

[`../acceptance/visual/16-settings-top.png`](../acceptance/visual/16-settings-top.png)、
[`../acceptance/visual/16-settings-p2.png`](../acceptance/visual/16-settings-p2.png)、
[`../acceptance/visual/16-settings-p3.png`](../acceptance/visual/16-settings-p3.png)、
[`../acceptance/visual/18-lab.png`](../acceptance/visual/18-lab.png)

(注:Expo 那台是三屏向导、原生是一屏到底 —— 那部分是 **Expo 包过时**,不是缺陷,
HEAD `src/ui/settings-shell.tsx:25-32` 写明向导已拆。分组名与其余条目原生与 HEAD 逐条相符。)

## 期望

「阅读」组里、「显示签名档」与「阅读时常亮」之间补一行开关:

- 标题「手势返回」,副标题「从左边缘右滑返回上一页」
- 读写 `gestureBack` 设置项
- **开关要真的接上导航的边缘返回手势**,不能只摆一个不生效的开关

## 疑似代码位置

`ui/settings/SettingsScreen.kt` / `ui/settings/SettingsEntries.kt`(行);
设置项本身在 `data/` 的 settings store;手势那一头在 `ui/nav/Navigator.kt` 与
`ui/drawer/DrawerGesture.kt` 附近。

**主控裁定(2026-08-23)**:不修。「手势返回」开关是所有者 2026-08-22 明确裁掉的(保留系统返回语义,commit d026c7b);Expo 包是 08-13 的旧版本还带着它。

---

## 主控复验(2026-08-23,`emulator-5554`,HEAD 8f07588)

**按裁定核对通过,票维持 invalid,不改状态。**

设备上 `uiautomator` 拉设置屏「阅读」组的条目序列:

```
自动加载下一页 / 仅 Wi-Fi 下加载图片 / 图片加载策略 / 显示签名档 / 阅读时常亮 / 字体和头像大小
```

「显示签名档」直接接「阅读时常亮」,**没有**「手势返回」——与所有者 2026-08-22 的裁定
(保留系统返回语义,commit d026c7b)一致。实验室与诊断子屏里同样没有。**不需要修。**
