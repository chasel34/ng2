# 11 — BBCode Compose 渲染器(M2)

**What to build:** AST→Compose,ADR-0001 结论沿用、实现重写。文本主体 `AnnotatedString` + `inlineContent`(表情 265 张 assets 随迁,内联= RN ImageSpan 的对应物;沿用 `scripts/fetch-smilies.mjs` 的产物,必要时补 Kotlin 侧生成表)。块级:引用、折叠(collapse)、代码、列表、表格(**定宽 108dp + 整表横滑**,不做内容测量——ADR-0001 降级照抄)、对齐、标题、分隔线、box/album;行内:链接、uid/pid/tid 引用、@提及、lessernuke 三档、防剧透 `[color=white]`(点击/选中可读的行为对照 RN 版);flash=video/audio 渲染媒体卡片外跳;图片/附件占位接票 12 的尺寸记忆表;签名档、贴条、热门回复折叠区的容器组件。
**设计原则(anzong 四条,GPL 思路可抄代码不可抄)**:解析+渲染模型构建(含 AnnotatedString 组装)全部后台一次完成,composition 只消费成品;渲染产物可按页常驻;滚动路径零计算;文字先出图片后到。渲染模型进不可变集合(kotlinx-collections-immutable),稳定性注解齐全。

**Blocked by:** 09

**Status:** open

- [ ] coverage 29 类型逐一与 RN 版并排截图核对(功能对照,非像素级)
- [ ] 超长楼层(最长 fixture)滚动无肉眼断续(初测;正式闸在票 19)
- [ ] 防剧透/折叠/表格横滑交互行为与 RN 版一致
