# 02 — core/net 基建

**What to build:** core 层可以向 NGA 发出任一读接口请求并拿回干净的结构化数据:自动带 UA 身份头与公共参数、空值参数剔除、GBK/GB18030 回落解码、响应清洗(剥前缀/截错误尾/修非法数字/删坏字段/整数 key 加引号/控制字符转义)、错误模型区分真错误与假错误白名单。fetcher 从第一天就是策略链形态(本票仅实现单策略),为反封锁链(ADR-0002)留好槽位。纪律:禁止 clone response。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 用真实抓包样本作 fixture 的单测通过:清洗 8 步、GBK 解码、两种认证方式、假错误白名单(完毕/没找到/没有符合条件的结果/今天已经签到/找不到用户)
- [ ] HTTP 非 2xx 时仍先解析 body 再报错
- [ ] `.env.local` 的测试 cookie 能真实请求通知接口拿到合法 data(集成冒烟,可跳过)

## Comments
