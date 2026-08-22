# 05 — 金样本管线(M1,Q16=A 的落地)

**What to build:** TS 实现当 oracle,机器保证 Kotlin 直译的语义等价:
- RN 侧一次性导出脚本(node/vitest 环境,放 `scripts/export-goldens.mjs` 或 .ts):把全部 fixtures 语料(3,259 行)喂给 TS 纯函数——BBCode `parseBBCode`、`sanitize`、envelope 解包、decode-body、dice、anonymous、title-style、attachments URL、deep-link、vote 解析——规范化 JSON(键排序、稳定序列化)写入 `native/app/src/test/resources/goldens/<domain>/<case>.json`(input + expected 成对)。幂等可重跑。
- Kotlin 侧表驱动对拍框架:每个 domain 一个参数化测试,逐条 input→Kotlin 实现→与 expected 深度比对,差异输出可读 diff。
- **策略链等控制流不走金样本**,关键回归手工移植(票 06)。

**Blocked by:** 01

**Status:** open

- [ ] 导出脚本进版本库,goldens 生成物有 README 说明再生方式
- [ ] Kotlin 框架对至少一个 domain(建议 sanitize)全量跑通
- [ ] 约定:后续每张 M1/M2 票的「金样本对拍通过」都指本管线
