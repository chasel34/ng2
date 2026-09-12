# ADR-0001：正文使用 BBCode → AST → 原生组件

状态：沿用；2026-09-12 按 Kotlin 实现更新。原 RN 阶段的渲染决策由 ADR-0003 延续到 Compose。

解析与清洗放在 [core/bbcode](../../app/src/main/kotlin/com/chasel/ng2n/core/bbcode/)，AST 由 [ui/bbcode](../../app/src/main/kotlin/com/chasel/ng2n/ui/bbcode/) 映射为 Compose 组件。楼层流不嵌入 WebView，避免额外的内存、滚动和主题适配成本。

代价是每个标签都需原生实现。当前降级约定：表格忽略 rowspan、整表横向滚动；投票只读；视频和音频显示媒体卡片、点击外跳。极端排版可使用「网页版打开」。登录与整页网页兜底仍可使用 WebView。

曾考虑表格或投票使用局部 WebView，最终选择统一原生正文渲染。
