# 25 — 大图查看器

**What to build:** 点正文/附件图片进全屏查看器:左右滑动多图翻页(计数 2/3)、双指缩放双击放大、保存到相册、系统分享、菜单(保存/复制地址/查看原图/浏览器打开)、批量下载本楼全部图片;缩略图先显示、原图渐进加载。

**Blocked by:** 07

**Status:** implemented

- [x] 缩放/翻页手势不冲突,边界回弹自然
- [x] 保存与批量下载落到相册可见,需要的权限流程完整
- [x] 顶栏与菜单与设计稿 1:1

## Comments

### 实现要点(2026-08-09)

查看器屏 `src/app/image-viewer.tsx`(transparentModal + fade 进场)+ 画布 `src/ui/image-gallery.tsx`(gesture-handler + reanimated,根布局补了 `GestureHandlerRootView`)。手势不冲突是结构性的:同一个 Pan 按缩放拆两条路——原始大小拖**页**、放大后拖**图**(钳在图的实际画幅内),要翻页得先双击/捏合还原,和系统相册同一套语义;页/图出界都乘 0.55 阻尼、松手 220ms 弹回。图片列表口径在 `src/ui/bbcode/floor-images.ts`(纯函数 8 例单测):正文 `[img]`/`[noimg]`(含嵌套)+ `[album]` + 图片附件,按屏上出现顺序去重;列表塞不进路由参数,走 `image-viewer-request.ts` 模块暂存再 push。渐进加载用 expo-image 的 `placeholder`(缩略图变体来自 07 票的 `thumbnailUrl`);「图片加载策略」省流量档下默认只拉缩略图,菜单「查看原图」逐张升级。保存/分享/批量下载在 `src/ui/image-files.ts`:先下到缓存中转(文件名 `imageFileName`,core 层新纯函数 + 7 例单测),再经 expo-media-library 写进「相册/NGA」(`requestPermissionsAsync(writeOnly, ['photo'])`,拒了给指路 toast);分享走 expo-sharing 分享文件本体,下载失败退化为复制地址。新依赖 expo-media-library / expo-file-system / expo-sharing / expo-clipboard(**要重出 dev build**),app.json 加了 media-library 插件(granularPermissions: photo)。

与设计稿的两处偏差:①「查看原图」不带「(2.4 MB)」——体积要 HEAD 一次才知道,不为一行文案多打一枪;②「下载全部(N 张)」是票面要求、设计稿菜单没画,按菜单分组语言放分隔空隙后;另外画布区全出血(设计稿 mock 有 16 内距,真图查看器留白只碍事),顶栏 save 图标是描边版(字体是静态 Material Icons,没有 FILL 轴)。

留给真机验收:双指捏合的焦点跟手、翻页与缩放的手感/回弹、省流量档的「查看原图」、Android 各版本的相册权限弹窗与「相册/NGA」落位、批量下载十几张的表现、`transparentModal` 进场是否闪黑。
