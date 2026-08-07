# 正文渲染走「BBCode → AST → 原生组件」,不用 WebView

两个参考项目分别代表两条路线:MNGA(原生 Span 树)与 NGA-CLIENT(BBCode→HTML→WebView)。我们选全原生:core 层的 TS 解析器把 BBCode 解析成 AST,UI 层把 AST 映射为 React Native 组件。理由:①设计稿的楼层是原生卡片流,列表内嵌 WebView 做不到 1:1 还原且内存/滚动体验差;②本项目不做编辑器,不需要"发前预览"复用渲染管线的好处;③深浅色直接吃 design token,无需维护两套 CSS。

代价与缓解:每个标签都要写原生渲染。已明确降级:`[table]` 简化实现(忽略 rowspan、整表横向滚动);投票只读渲染、不做投票操作;`[flash=video/audio]` 渲染为媒体卡片点击外跳,不内联播放。极端排版帖用「网页版打开」逃生。

Considered: 混合方案(table/vote 用楼层内 WebView 岛)被用户否决,统一纯原生。
