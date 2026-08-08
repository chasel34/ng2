# 19 — Web 反解 + 网页兜底页

**What to build:** 反封锁链后半段:read.php 请求不带格式参数拿网页 HTML,从内联 JS(postArg.proc / userInfo.setAll / __PAGE / msgcode 错误标记)反解出与正常接口同构的数据,详情页无感继续原生渲染,顶部出现「原生解析失败,已切换为网页数据源」提示条(可重试原生);反解也失败时进网页版 WebView 兜底页(带「用 APP 阅读这一页」回切按钮与网页菜单)。Web 反解档位可配(Disabled/Secondary/Primary/Only)。

**Blocked by:** 07, 18

**Status:** implemented

- [x] 用真实网页 HTML fixture 的反解单测:楼层元数据/用户/分页/错误四类都覆盖
- [x] 反解数据走同一渲染管线,楼层显示与正常接口一致
- [ ] 兜底页与提示条与设计稿 1:1,回切动作生效(要真机)

## Comments

### 落地方式

- **反解器** `src/core/net/web/read-html.ts`(+ 通用扫描器 `html-scan.ts`):把网页 HTML 反解成
  与 `__output=8` 同构的信封,`core/api/topic-detail` 一行不用改。
- **策略** `src/core/net/strategies/web-fallback.ts`:实现 `FetchStrategy`,请求仍走
  `runAttempt`(新增 `parse` 选项,自带解析器时不再限制格式档位)。
- **档位** 存 `src/store/net-settings.ts` 的 `webFallbackMode`,默认 `secondary`;
  22 票把它接进设置页的「实验室 · 网页数据源兜底」(设计稿那一行是个开关,四档要改成选项)。
  档位是用户设置而链的顺序建 fetcher 时定死,所以 `web-fallback` 在链上**放了两条**
  (前置位 + 正常位),各自按档位决定跑不跑。
- **提示条** 在详情页(`data.source === 'web'` 时出),不在网页兜底页上——设计稿把
  `fallbackBar` 画在 webview 屏,但票面要的是「反解数据渲染时」的提示,两处只留了详情页这一处。
- **网页兜底页** `src/app/web.tsx`(路由 `/web?url=&title=`),错误页与顶栏地球钮都指向它。

### 反解覆盖到的字段 / 已知缺口

对拍用例 `src/core/api/topic-detail.web.test.ts`:同一主题同一页的网页 HTML 与 `__output=8`
两份样本,楼层身份/正文/标题/时间/发帖设备/附件、热门回复、用户表逐条相等。缺口只有三处:

1. **投票(`vote`)拿不到** —— 网页版交给另一段 JS 渲染,`proc` 实参里没有。带投票的楼在反解
   数据下会少一块(设计稿那句「这楼里有投票模块」说的正是这个)。
2. **贴条与热门回复的 `from_client` 恒为 null** —— 网页版只给楼层,不给嵌套的那些。
3. **匿名楼主在第 2 页及以后认不出「楼主」标记** —— `#anony_` 串只在用户表里出现,主楼不
   在场时没有别的线索(JSON 路线每页都带 `__T.author`)。

分页另有一处近似:「只看某人」这类过滤视图下 `setDefault` 给的仍是全帖回复数,那时总楼数退回
「页数 × 每页」(偏大但页码条不会少一页)。

### 留待真机验收

- 提示条/兜底页与设计稿 1:1(间距、圆角、底部回切按钮位置),以及回切后详情页会重打原生接口。
- 兜底页的登录态:WebView 读的是**原生 cookie 仓库**,多账号时那是最后一次登录的账号,
  不一定是 app 里当前切到的那个(RN WebView 没有写 cookie 的接口)。
- 「只看某人 / 只看该楼」(`authorid`/`pid`)在反解档下的表现,fixture 里没有这两种样本。
- 网页菜单的「复制网址」与「网页字号」仍是「本版本未开放」:前者要 expo-clipboard(没装,
  顶栏菜单的「复制链接」同样待办),后者归 22 票的字号调节。
