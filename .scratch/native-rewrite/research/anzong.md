# 参考 app 源码研读:NGA 开源客户端(anzong)

> 探查 agent 报告,2026-08-21。仓库 https://github.com/Justwen/NGA-CLIENT-VER-OPEN-SOURCE(浅克隆研读)。
> **License:GPL v2 —— 只能抄思路,一行代码不能搬**(分发 APK 即触发开源义务)。

## 基本盘

- **确认即「anzong」**:applicationId `gov.anzong.androidnga`,桌面名「NGA客户端开源版」——旧性能对拍记录里的参照 app 就是它。
- Java 256 文件/2.83 万行 + Kotlin 73 文件/6 千行(83% Java);老骨架 MVP+RxJava2+ButterKnife,新功能 Kotlin+Compose(抽屉/版块/搜索/消息)。
- minSdk 30 / targetSdk 35 / Java 17 / 仅 arm64;v4.2.2,2026-08-07 仍在提交。

## UI 体系

- 多 Activity+Fragment;MainActivity 只装 Compose 的 NavigationDrawerFragment(Material3 ModalNavigationDrawer + LazyVerticalGrid 版块九宫格)。
- **主题列表**:RecyclerView+LinearLayoutManager,行布局 7 view/3 层、**无头像无图片**,标题上色 SpannableStringBuilder。
- **楼层流**:外层 ViewPager(**NGA 每页 20 楼=一个 Fragment 一个 RecyclerView**);`setItemViewCacheSize(20)` → 整页 view 常驻,**页内滚动零回收零重绑**。楼层 item 3 层 ~15 view,无 CardView 无 elevation,纯色斑马背景。
- **Activity 转场全系统默认**——grep 不到 overridePendingTransition/ActivityOptions。它的「顺」完全不靠自定义转场。

## 楼层内容渲染:每楼一个 WebView

1. 数据到达时后台线程一次性 BBCode→HTML(io 线程网络→newThread 解析转换→主线程只收成品);**bind 时零计算**。
2. Adapter 持 20 格 `LocalWebView[]` 按 position 缓存;loadDataWithBaseURL 前做内容去重,rebind 不重渲染。
3. `setBlockNetworkImage(true)` 首帧文字优先,onPageFinished 再放图。
4. HTML 模板极简,CSS/JS 走 file:///android_asset;~250 张表情打进 assets,decode 时远程表情 URL 映射本地路径。
5. 引用/贴条/投票/附件/签名在转换期合成进同一份 HTML;附件图「点击显示」+缩略图。
6. WebView 内点击被 shouldOverrideUrlLoading 拦回原生(链接开 Activity、图片开 PhotoView 图集);纯文本楼层降级 TextView。

## 图片

Glide 4.11:内存缓存 memoryClass/3、ARGB_8888;头像 onCreateViewHolder 时定死宽高+circleCrop+静态占位、**非 WiFi onlyRetrieveFromCache**;正文图 WebView 自载(.thumb.jpg),原图只在大图页拉。

## 网络层

Retrofit2+OkHttp+RxJava2;域名 5 个用户手选(默认 bbs.nga.cn),**无自动轮换**;响应体一律按 GBK 读 String(网络层一处收敛);POST 带 charset=gbk 重编码;UA=真浏览器串+`X-User-Agent: Nga_Official`;cookie 只有 ngaPassportUid/Cid 两值;**请求被拒时自动换下一账号 cookie 重试**(它扛限流的手段,与我们 switch-account 同构);`read.php?__output=8`;fastjson 解析前也做字符串修补。

## 「顺」的机制清单(可抄的思路)

1. **滚动路径零计算**:转换在后台一次完成,bind 只贴现成品。
2. **整页常驻**:itemViewCacheSize(20)+20 格 WebView 数组+loadData 去重,页内零 inflate/measure/rebind。
3. WebView=独立合成层,滚动只是平移已光栅化内容。
4. **翻页=平移整页**:ViewPager 相邻页预创建预渲染。
5. 首帧文字优先、图片后到;附件点击才显。
6. 表情/CSS/JS 全本地 assets,渲染零外部依赖。
7. 列表行极轻(7 view 无图;楼层 15 view 无阴影)。
8. 头像固定尺寸+缓存优先,加载完成不重排;省流模式只读缓存。
9. 点击防抖;重活(拼引用文本)丢 io 线程再回主线程。

## 反面清单(不学)

1. GPL v2(代码零搬运)。
2. 每楼一个 WebView 的内存代价与行高跳动;largeHeap 还写错了位置(挂在 `<manifest>` 上无效)。**原生文本栈能拿到同样收益而没有这些代价**(ADR-0001 的路线被强化)。
3. 60+ 串行正则做 BBCode(源码里躺着一条因灾难性回溯被注释掉的正则)。
4. 全量 notifyDataSetChanged、O(n²) append 去重——靠每页 20 条遮丑。
5. 过时栈:ViewPager1/MVP+RxJava2/ButterKnife/ARouter(停维护)/fastjson 1.1.71(CVE 多)。
6. keystore 密码明文进版本库。
7. getItemId 返回 position 且未 setHasStableIds;夜间模式切换靠 finish+restart。
8. 上滑加载依赖 onScrollStateChanged+findLastCompletelyVisible 组合,快速 fling 有漏触发风险。

## 对 Kotlin 重写的借鉴要点

- 把「NGA 每页 20 楼」当资源管理单位:整页渲染产物常驻,页间 Pager 预载,滚动路径零计算。
- 渲染管道分层:网络线程(GBK 解码)→解析线程(BBCode→渲染模型,一次性,含表情本地映射/图集抽取)→UI 只绑定;载体用原生文本栈(AnnotatedString)替代 per-floor WebView。
- 表情进 assets;正文缩略图+原生大图查看器;头像固定尺寸+省流 cache-only。
- 多账号 cookie 轮换是现成的限流对策(我们的 switch-account 策略同构,保留)。
