# M4 验收派发 · 环境说明(给 codex)

你是本次 M4 真机验收的执行者。**只做验收,不改代码**;结论写进
`.scratch/app-v1/m4-acceptance-results.md` 对应批次节。验收清单全文在
`.scratch/app-v1/m4-acceptance.md`,逐屏静态审计背景在 `m4-screen-audit.md`,
断网方法学在 `m3-acceptance.md`(方法学节)。

## 环境(已由派发方准备好)

- 手机已通过 Wi-Fi adb 配对并连接:serial **`192.168.0.105:34283`**,所有 adb 命令加 `-s` 指定。
- 新 dev build APK(EAS build 201faf7f)已安装(覆盖安装,应用数据保留,账号 lemon43/67296151 应仍在登录态)。
- Metro 已在 Mac 上跑着,端口 **8082**;`adb reverse tcp:8082 tcp:8082` 已设置。
  若 adb 断线重连,记得重新执行 reverse。dev client 打开后若停在启动页,
  在 dev menu 里选 `http://localhost:8082`。
- app 包名可用 `adb shell pm list packages | grep -i ng` 确认(dev variant)。

## 打法约定(控 token)

- 优先 `adb shell uiautomator dump` + `grep` 读控件树判断布局/文案,截图只在必须看观感时用
  (`adb -s <serial> exec-out screencap -p > /tmp/x.png`)。
- 动效类用 `adb shell screenrecord`(≤30s)拉回逐帧看,别反复整屏截图。
- 多点触控(捏合缩放)adb 做不到就标 ⚠️ 需人工,不要硬凑。
- 断网场景照 m3-acceptance.md 的方法学:`settings put global http_proxy` 指向不通端口 + `am force-stop` 清连接池;**别关手机 Wi-Fi**(会断掉 adb)。验完记得 `settings put global http_proxy :0` 还原。
- 每项给一行结论;❌ 的多写一两句现象与复现路径。

## 判定基准

设计稿:`.scratch/app-v1/` 下的 spec 与 screen-audit 记录;动效常量在 `src/ui/motion.ts`。
拿不准观感的标 ⚠️ 留给人定夺,不要自行放宽。
