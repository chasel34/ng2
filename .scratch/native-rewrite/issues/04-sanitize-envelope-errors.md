# 04 — 清洗管线 / 信封 / 错误模型(M1)

**What to build:** 三件套直译(research/inventory.md §3.7):
- **清洗六步**,顺序敏感:剥 `window.script_muti_get_var_store=` 前缀并切到首个 `</script>` → 切 `/*error fill content` 尾 → 去 `/*$js$*/` → 非法数字加引号 → **一趟带字符串状态的扫描**同时做「裸整数键加引号 + 字符串内裸控制字符转义」(防把正文里 `,123:` 形状改坏)→ 去尾分号外层括号。**不删 `alterinfo`**(保「已编辑」标记,与上游参考实现相反,是刻意的)。
- **信封解包** + `orderedEntries`:字符串整数键对象与**真数组**双兼容(`__output=11` 返回真数组,曾被误判成被封——ADR-0002 第 10 条)。
- **错误模型**:kind ∈ network/http/parse(≈被封)/server/unavailable;默认 `retryable = kind != server`;**「未登录」强制可重试**(传输身份失败非语义失败);假错误白名单(完毕/没找到/没有符合条件的结果/今天已经签到/找不到用户)短路成成功;HTTP 非 2xx 先解析 body、解不出才按状态码报错。
kotlinx.serialization 配置:`isLenient + ignoreUnknownKeys + coerceInputValues`,二象性字段用 `JsonTransformingSerializer` 归一。

**Blocked by:** 01, 03

**Status:** open

- [ ] sanitize/envelope 全部 fixtures 金样本对拍通过
- [ ] 错误分类与可重试语义:手工移植 TS 版对应测试逐条绿
- [ ] `alterinfo` 保留、真数组兼容各有专门用例
