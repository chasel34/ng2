# 12 — 图片管线与查看器(M2)

**What to build:** Coil 3 挂**同一个 OkHttpClient**(coil-network-okhttp;帖内图自动带 Cookie/UA,附件域名要登录态的场景才不豆腐)。策略照抄 RN 版:正文图/头像 memory+disk、查看器场景 disk 优先;`imageQuality` 三档(original/smart/thumbnail,默认 smart:Wi-Fi 原图蜂窝缩略)+ `wifiOnlyImages`(默认 true);网络计费状态单例订阅。**图片尺寸记忆表**(服务端不给像素尺寸):内存 512 条 + 磁盘持久化(1s 防抖),防 4:3 占位→真实比例跳动;超高图纵横比 0.6 封顶(护列表行高估算)。查看器:双指缩放、双击 2.5×、翻页(同一 Pan 按缩放拆「拖页/拖图钳边界」两路)、边界回弹、保存到相册 `NGA`(URL 稳定推文件名防堆积)、系统分享、复制地址、查看原图(按 index 覆盖)、浏览器打开、批量下载;transparentModal+fade 的呈现语义。**白捡改进**:`onTrimMemory` 清 Coil 内存缓存(RN 版没做,19MB/350MB 回收率是证据)。

**Blocked by:** 01

**Status:** open

- [ ] 与 RN 版同帖对照:占位/缩略/原图切换、查看器手势语义一致
- [ ] 尺寸记忆表命中时首帧即正确比例(录屏验证无跳动)
- [ ] onTrimMemory 实测回收量记录进 Comments
