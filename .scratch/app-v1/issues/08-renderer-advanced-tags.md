# 08 — 渲染器进阶标签

**What to build:** 详情页正文覆盖剩余标签:collapse 折叠块(带标题展开收起)、list、table 简化实现(忽略 rowspan、colspan 拉通、整表横向滚动)、align/l/r/h、===标题===与分割线、album 相册、attach、dice 骰子(按 NGA 伪随机本地复算结果并展示)、noimg(按发帖日期拼路径前缀)、flash 视频/音频媒体卡片(点击外跳系统播放/浏览器)、投票只读渲染(题目/选项/票数,投票按钮 toast「本版本未开放」)、lessernuke/hip/item/stripbr、未知标签透传。

**Blocked by:** 07

**Status:** ready-for-agent

- [ ] 骰子复算结果与站上真实骰子帖一致(fixture 单测)
- [ ] vote 字段(~ 分隔 kv)解析单测,单选/多选/已结算三种形态渲染正确
- [ ] 表格在窄屏可横向滚动且不撑破楼层卡片
- [ ] 全部进阶标签有渲染快照或真机对照记录

## Comments
