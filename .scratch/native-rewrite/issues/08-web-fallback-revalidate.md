# 08 — Web 反解移植与线上重验(M1)

**What to build:** 两步,顺序不能反:
① **先对线上重新抓包**:同一主题页并发抓 `read.php` 的 JSON(`__output=8`)与 HTML(无格式参数)响应,验证 `commonui.postArg.proc` 参数位置表(RN 版 `read-html.ts:52-68`,全网无第二份文档,最易被 NGA 改版打掉)是否仍有效;好/坏样本各留、脱敏后进 fixtures(坏字节样本**故意留着**——ADR-0002 第 9 条)。
② 移植 HTML 反解:引号感知括号深度匹配器 + 标签深度追踪;抓 `postArg.proc` / `userInfo.setAll` / `__PAGE`+`setDefault` 交叉校验 / `<!--msgcodestart-->` / `attach.load` / `loadAlertInfo` / `__ATTACH_BASE_VIEW`;输出与 `__output=8` 同构信封、标 `source='web'`;已知不可恢复字段清单照抄(投票内容、嵌套贴条/热回 from_client、第 1 页外匿名楼主标记)。

**Blocked by:** 06

**Status:** open

- [ ] 抓包重验结论写进本票 Comments(参数表变没变、变了怎么改)
- [ ] 金样本 + 新抓样本双份对拍通过
- [ ] `only` 模式失败改写为不可重试的语义有用例
