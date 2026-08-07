# NGA 论坛 API 调用文档

> 综合 MNGA（iOS，走 XML/`lite=xml`）与 NGA-CLIENT-VER-OPEN-SOURCE（Android，走 JSON/`__output=8`/`lite=js`）两个开源客户端的源码整理。
> Android 侧以 **Justwen fork v4.2.2**（https://github.com/Justwen/NGA-CLIENT-VER-OPEN-SOURCE ，活跃维护至 2026-08）为准；文中标注「v4」的条目为其相对停更的 ymback v3.7.6 的变化。
> 两个客户端调用的是**同一套 NGA 网页版 PHP 端点**（没有官方开放 API）：`thread.php` / `read.php` / `post.php` / `forum.php` / `nuke.php` / `app_api.php`。
> 源码出处见 `docs/research/` 下各报告（含具体文件与行号；v4 差异见 `nga-android-v4-delta-report.md`）。

---

## 0. 全局约定

### 0.1 域名

| 域名 | 说明 |
|---|---|
| `https://bbs.nga.cn` | 两边的默认域名 |
| `https://ngabbs.com` | 备选 |
| `https://bbs.ngacn.cc` | 备选 |
| `https://nga.178.com` | 旧域名（MNGA 会自动重写为默认域名；Android 仍在列表里） |
| `https://nga.donews.com` | 备选（仅 Android 列出） |

资源域名：
- 版块图标：`https://img4.nga.cn/ngabbs/nga_classic/f/app/<fid>.png`；合集图标 `https://img4.nga.178.com/proxy/cache_attach/ficon/<stid>v.png`
- 附件/正文图片：**域名优先从 `read.php` 响应的 `data.__GLOBAL._ATTACH_BASE_VIEW` 字段动态获取**（取 `/` 分隔的第一段；Android v4 2026-08 新增，硬编码域名仅兜底——旧硬编码 `img.nga.178.com` 已失效正是老版碎图的原因）。静态规则：`https://img.nga.cn/attachments/<path>`（旧域名 `imgN.nga.178.com` / `.ngacn.cc` / `.ngabbs.com` 统一规范化为 `img.nga.cn`；路径以 `/ngabbs/` 开头的用 `img4.nga.cn`）
- 发帖附件上传：由 `post.php` 动态返回（MNGA 方式，推荐）；Android 硬编码 `https://img8.nga.cn/attach.php?`

### 0.2 认证（两种等价方式）

登录凭证是两个值：**uid** 和 **token**，来自 WebView 登录后的 Cookie：

- `ngaPassportUid` → uid
- `ngaPassportCid` → token（会话凭证）
- （可选）`ngaPassportUrlencodedUname` → 用户名，需 **GBK 字符集 URLDecode 两次**

**登录页**（两边一致，WebView 打开）：

```
https://<host>/nuke.php?__lib=login&__act=account&login
```

MNGA 每 0.5s 轮询 WebView cookie store 直到抓到两个 Cookie；Android v4 在页面加载回调里从 `CookieManager` 解析（`LoginViewModel`）。均不支持 QQ/微博第三方登录。若遇登录限制，先在 PC 网页端登录一次再试。

凭证附加到请求的两种方式（**任选其一即可**）：

| 方式 | 用法 | 使用者 |
|---|---|---|
| POST form 字段 | body 里带 `access_uid=<uid>&access_token=<token>`（multipart 时作为 text part） | MNGA |
| Cookie header | `Cookie: ngaPassportUid=<uid>; ngaPassportCid=<cid>` | Android |

游客访问：不带凭证直接请求，出错时服务端返回 `{"error":{"0":"未登录"}}`。

**账号密码登录**（❌ 已死亡：Android v4 登录重构时删除了全部调用代码，仅剩 WebView 登录；下述流程只作历史记录，不要复刻）：
1. `GET https://bbs.ngacn.cc/login_check_code.php?id=_<random>/`（需 Referer `.../nuke.php?__lib=login&__act=login_ui`）拿验证码 PNG，`id` 值即后续 `rid`
2. `POST https://bbs.ngacn.cc/nuke.php`（multipart）：`name`/`type=name`/`password`/`rid`/`captcha`(大写)/`__lib=login`/`__act=login`/`__output=1`/`__inchst=UTF-8`/`raw=3`/`qrkey=`

### 0.3 User-Agent（服务端校验，必须伪装）

| UA | 说明 |
|---|---|
| `Nga_Official/80024(Android12)` | 官方安卓客户端 UA（ymback v3.7.6 写死 `Nga_Official/80023`） |
| 系统 WebView UA + `X-User-Agent: Nga_Official` | **Android v4 的现行做法**：`User-Agent` 默认取 `WebSettings.getDefaultUserAgent()`（可自定义，SP key `USER_AGENT`），客户端身份改由辅助头 `X-User-Agent: Nga_Official` 声明。证明伪装不必写死在 UA 里 |
| `NGA_skull/7.3.1(iPhone17,1;iOS 26.0)` | 官方 iOS 客户端 UA |
| `NGA_WP_JW/(;WINDOWS)` | **Windows Phone UA，MNGA 对 `read.php` 强制使用，实测更不容易被封** |
| Chrome 桌面 UA | 网页兜底用 |

MNGA 同时设置三个 header：`User-Agent`、`X-User-Agent`、`Referer`（Referer 设为任意以 base url 开头的地址即可；`nuke.php?__lib=ucp` **必须带 Referer** 否则拒绝）。Android v4 由 OkHttp 拦截器统一注入 `Cookie` + `User-Agent` + `X-User-Agent`。

### 0.4 请求方式与公共参数

**MNGA 的做法（推荐照抄）：所有请求一律 POST，业务参数放 URL query string，body 只放认证字段。** Android 混用 GET/POST，效果相同。

每个请求自动附加：

| 参数 | 值 | 含义 |
|---|---|---|
| `__inchst` | `UTF8` | 声明输入/输出用 UTF-8（MNGA 全局带；Android 不带，全程 GBK） |
| 格式参数 | 见下表 | 控制返回格式 |

**空值参数必须从 query 中删除**（MNGA 全局行为，大量逻辑依赖它：bool false 编码成空串即"不传"、`fid`/`stid` 二选一等）。

**返回格式参数**（同一接口可选不同格式）：

| 参数 | 返回 | 说明 |
|---|---|---|
| `lite=xml`（≡ `__output=9`） | XML | MNGA 对 `thread/read/post/forum.php` 的首选 |
| `__output=10` | 紧凑 XML | 与 `lite=xml` 等价的备用格式，**被封时交替尝试可绕过** |
| `__output=8` | 紧凑 JSON | `nuke.php`/`app_api.php` 通用；Android 对所有接口的主选 |
| `__output=11` | 详细 JSON | |
| `lite=js` | JS 变量赋值包裹的 JSON | Android 常用，带 `window.script_muti_get_var_store=` 前缀 |
| `lite=htmljs` | HTML 内嵌 JS | 贴条接口用 |
| `noprefix` | — | 去掉 JS 前缀（**不总生效，解析必须容错**） |
| 不带任何格式参数 | 网页 HTML | MNGA 的 Web 兜底路径 |
| `raw=3` | 原始输出 | 部分 nuke.php 接口带 |
| `v2` | 新版数据结构 | `read.php` 用 |

### 0.5 编码（第一大坑）

- **响应**：优先看 `Content-Type` 声明的 charset；未声明时按 **GB18030/GBK** 解码。MNGA 带 `__inchst=UTF8` 请求 UTF-8 输出并回落 GB18030；Android 一律 GBK。
- **POST 表单体**（Android 路线）：GBK urlencode，`Content-Type: application/x-www-form-urlencoded;charset=GBK`。
- **参数编码不统一，必须逐接口对照**：`thread.php` 的 `key` 是 **UTF-8** urlencode，但同一接口的 `author` 是 **GBK**；`forum.php` 的 `key` 是 GBK；登录接口 `__inchst=UTF-8`。
- **提交正文的转义**（MNGA `escape_for_submit`，不做会被拒或乱码）：以下字符必须转成 **UTF-16 码元的十进制 HTML 实体** `&#NNNNN;`：
  - 码点 > `0xFFFF`（emoji 等，转成代理对两个实体）
  - `0x200D`（ZWJ）、`0xFE00`–`0xFE0F`（变体选择符）、`0x2600`–`0x27BF`
  - 例：`"😂"` → `&#55357;&#56834;`
- **读取正文的反转义**：做**两轮** HTML 实体解码（NGA 双重转义）+ UTF-16 代理对实体还原。

### 0.6 响应清洗（JSON 路线必做）

NGA 返回的"JSON"不合法，解析前必须清洗（Android `ArticleConvertFactory` 的 8 步 + MNGA 的 2 步合并）：

```
1. 剥前缀        replace("window.script_muti_get_var_store=", "")
2. 截断错误尾巴   indexOf("/*error fill content") > 0 时截断
3. 去注释        replace("/*$js$*/", "")
4. 修非法数字     "content":+123 → "content":"+123"；"content":0123 → "content":"0123"（subject/author 同理）
5. 删坏字段      "alterinfo":"[xxx] " 整段删（部分页面打不开的原因）
6. 整数 key 加引号  正则 ([{,}]\s*)(\d+)(:) → $1"$2"$3
7. 字符串内裸控制字符转义
```

清洗后：顶层结构 `{"data": {...}, "error": {...}, "time": N}`，`data` 与 `error` 互斥。**`data` 内部大量用字符串数字键（`"0"`,`"1"`…）当数组**，自动 JSON 映射库全部失效，只能手工遍历。

### 0.7 错误处理

- **HTTP 非 2xx 时 body 仍可能有有效错误信息**：先解析 body，body 为空才用状态码报错。
- XML 错误位置：`/root/__MESSAGE`（子节点 0=code、1=info）、`/root/error`、`/root/error_code`。
- JSON 错误位置：顶层 `error` 对象（`{"error":{"0":"信息"}}` 或 `{"error":{"code":403,"0":"信息"}}`）。
- **"假错误"白名单**（出现视为成功）：`完毕`、`没找到`、`没有符合条件的结果`、`今天已经签到`、`找不到用户`。
- 很多写操作的成功判定靠响应文本包含 `操作成功` / `发贴完毕` / `成功`。

### 0.8 反封锁（MNGA 的核心机制，建议复刻）

NGA 会封第三方客户端（表现为 XML/JSON 解析失败）。MNGA 的对策：

1. **重试组合** = 格式参数（`lite=xml` ↔ `__output=10`）× 域名（官方 ↔ 自建反代）的笛卡尔积；只有解析错误/HTTP 状态错误才触发重试；成功组合按 key 缓存，下次优先。
2. 每次重试前重建 HTTP client（并可发 `HEAD thread.php` 预热）。
3. **Web HTML 兜底**（`read.php` 专用四档策略 Disabled/Secondary/Primary/Only）：请求同一 URL 但不带格式参数拿网页 HTML，从内联 JS 反解数据：`commonui.postArg.proc(...)`（楼层元数据）、`commonui.userInfo.setAll(...)`（用户）、`var __PAGE`（分页）、`<!--msgcodestart-->`（错误），再合成 XML 复用下游解析。
4. `read.php` 用 Windows Phone UA。
5. 最后兜底：读本地缓存 / 提示用浏览器打开。Android 的对策更简单：换下一个账号的 Cookie 重试一次，再失败就内置 WebView 打开原页。

---

## 1. 版块

### 1.1 版块分类树

```
POST app_api.php?__lib=home&__act=category        （JSON）
```

响应 `data`：分类对象数组，每个含 `_id`/`name`/`groups.*.forums.*`；版块对象含 `id`/`fid`/`stid`/`name`/`info`/`topped_topic`。**stid 优先于 fid**。
（Android 走 GET，响应是标准 `{code,msg,result[].groups[].forums[]}` JSON——全站唯一格式正常的接口。Android v4 用法：内置 `assets/board_list.json` 起底，进版面时经此接口在线增量更新，24 小时最多一次；`board_list.json` 里每个版面还可带 `head` 字段 = 版头帖 tid。）

### 1.2 版块搜索

```
POST forum.php?key=<关键词>                        （XML；Android: GET + __output=8，key 用 GBK urlencode）
```

XML 响应 `/root/item`：`fid` `stid` `name` `info` `topped_topic`。

### 1.3 云端收藏版块（列表/增删）

```
POST nuke.php?__lib=forum_favor2&__act=forum_favor
form: action=get                                   → 列表，版块数组在 data["0"]
form: action=add|del, fid=<fid或stid>              → 增删
```

### 1.4 子版块订阅 / 屏蔽

```
POST nuke.php?__lib=user_option&__act=set&{del|add}=<子版块filter_id>
form: fid=<父版块fid>, type=1, info=add_to_block_tids
```

⚠️ **参数名即操作且语义反转**：`del=<id>` 是订阅（从屏蔽表删除），`add=<id>` 是屏蔽。Android 源码还发现按子版块 type 不同 add/del 语义再反转一次。订阅状态判定靠魔法数：子版块 `attributes ∈ {7, 558, 542, 2606, 2590, 4654}` 视为已订阅，`> 40` 视为可订阅/屏蔽（试出来的，无文档）。

---

## 2. 主题列表（`thread.php`，XML 或 lite=js）

一个端点通过参数组合覆盖 6 个场景：

| 场景 | 参数 |
|---|---|
| 版块主题列表 | `fid=<fid>`（或 `stid=<stid>`，二选一、stid 优先）+ `page=N` |
| 排序 | `order_by=postdatedesc`（按发帖时间）；不传=按最后回复 |
| 精华区 | `recommend=1`（Android 额外加 `order_by=postdatedesc&user=1`） |
| 主题搜索 | `key=<关键词(UTF-8)>` [+ `content=1` 搜正文]；fid/stid 都不传 = 全站搜索 |
| 收藏夹 | `favor=<收藏夹id>`（默认夹用 `1`） |
| 某用户的主题 | `authorid=<uid>`（按用户名用 `author=<GBK urlencode>`） |
| 某用户的回复 | `authorid=<uid>&searchpost=1`，每条 item 多出 `__P` 子对象（`pid`/`content`/`postdate`） |

**响应结构**（XML XPath / JSON 键等价）：

- `__T` → 主题列表；`__F` → 当前版块信息（含 `sub_forums` 子版块）；`__ROWS` 总条数；`__T__ROWS_PAGE` 每页条数（35）→ 算总页数
- 主题字段：`tid` `fid` `quote_from` `subject` `author` `authorid` `postdate` `lastpost` `replies` `type` `tpcurl` `topic_misc` `titlefont` `recommend` `parent`

**解析要点**：

1. **真实 tid**：`quote_from` 非空且非 `"0"` 时用它，否则用 `tid`（Android 干脆从 `tpcurl` 正则提取）。
2. **`fav` 码**：从 `tpcurl` 正则提取 `fav=([a-fA-F0-9]+)`，是访问隐藏/过期帖的钥匙，后续 `read.php` 要带上。
3. **`type` 位掩码**：`0x8000`(32768)=合集/stid 快捷方式；`0x200000`=fid 快捷方式（fid 在 `topic_misc_var` 里）；`1024`=锁定；`8192`=有附件。
4. **标题样式**：`topic_misc` 是 base64(无 padding) 的 TLV（1 字节 type + 4 字节 BE u32），type=1 的值为位掩码；`titlefont` 直接是同一套掩码：`1`红 `2`蓝 `4`绿 `8`橙 `16`银 `32`粗体 `64`斜体 `128`下划线。
5. 匿名作者：`author`/`authorid` 以 `#anony_` / `-` 开头，可本地还原成中文假名（天干地支 22 字表 + 百家姓表按 hex 分段查表）。
6. **`parent` 字段类型不稳定**：2024-04 起服务端把 `__T[].parent` 从 JSON 对象改成了**字符串化 JSON**，解析需兼容两种形态（Android v4 `TopicConvertFactory` 已兼容）。

**热门话题不是 API**：客户端并发拉前 5~10 页，按 `postdate` 过滤时间窗（24h/7d/30d），按 `replies` 倒序取前 N 条。

---

## 3. 帖子详情（`read.php`，XML / JSON / 网页 HTML）

```
POST read.php?tid=<tid>&page=<N>[&fav=<fav码>][&pid=<pid>][&authorid=<uid>][&opt=512]
```

| 参数 | 含义 |
|---|---|
| `tid` | 主题 id |
| `page` | 页码，每页 20 楼（`__ROWS / 20` 向上取整算总页数） |
| `fav` | 收藏码（访问隐藏/过期帖） |
| `pid` | 只看某一楼（从通知跳转时用） |
| `authorid` | 只看某人 |
| `opt=512` | 只看匿名 |
| `v2` | 新版结构（Android 带） |

**响应**：`__T` 主题信息、`__R` 楼层列表、`__U` 用户表（含 `__GROUPS` 用户组）、`__F` 版块名、`__ROWS` 回复总数、`__GLOBAL._ATTACH_BASE_VIEW`（**附件图片域名**，取 `/` 分隔第一段，用于拼相对路径 `[img]`——见 0.1 节，别再硬编码图片域名）。

楼层字段：`pid` `tid` `fid` `lou`(楼层号) `authorid` `content`(BBCode) `postdatetimestamp`/`postdate` `score`(赞数) `score_2` `alterinfo`(非空=被编辑) `vote`(投票原始串，`~` 分隔 kv) `from_client`(发帖设备) `"17"`(热门回复 pid 逗号串，仅 JSON)；子节点 `hotreply`(热门回复，仅主楼) `comment`(贴条) `attachs`(附件：`attachurl`/`size`/`type`，`thumb=="1"` 时缩略图加 `.thumb.jpg`)。

用户字段：`uid` `username` `avatar`(可能是 JSON 串，需抠出第一个 http URL) `regdate` `postnum`/`posts` `fame`/`rvrc`(显示时÷10) `signature`/`sign` `buffs`(含 `105`/`117`=禁言) `ipLoc` `yz`(`-1`=被 nuke) `mute_time`。

**匿名楼层**：`authorid` 以 `-` 开头；同一请求内的匿名 id 要加请求级 context 前缀，避免不同页的 `-1` 串号。

---

## 4. 发帖 / 回复 / 编辑（`post.php`，XML；响应可能是 HTML）

### 4.1 第一步：拉取编辑上下文（必做，为了拿附件凭证）

```
POST post.php?action=<reply|quote|modify|new>[&tid=<tid>&pid=<pid>][&fid=<fid>|&stid=<stid>]
```

响应（XML）：

| 字段 | 含义 |
|---|---|
| `/root/content` | 预填内容（引用/编辑时；需两轮实体解码） |
| `/root/subject` | 预填标题 |
| `/root/modify_append` | 非空 = 超时只能追加编辑 |
| `/root/auth` | **附件上传鉴权码** |
| `/root/attach_url` | **附件上传目标 URL（绝对地址）** |

`auth`/`attach_url`/`modify_append` 要**原样**带到后续请求。

### 4.2 上传附件（multipart POST 到 `attach_url`）

| 字段 | 值 |
|---|---|
| `func` | `upload` |
| `v2` | `1` |
| `auth` | 4.1 的 auth |
| `fid` | 版块 fid |
| `origin_domain` | 当前域名（如 `ngabbs.com`） |
| `attachment_file1` | 二进制，`Content-Type: image/jpeg` |
| `attachment_file1_img` | `1` |
| `attachment_file1_dscp` / `attachment_file1_url_utf8_name` | 文件名（后者 UTF-8） |
| `attachment_file1_watermark` | `""`（或 `tl`/`tr`/`bl`/`br`） |
| `attachment_file1_auto_size` | `""`（或 `1` 自动缩图） |
| `lite` | `js`（Android 带） |

响应：`attachments`（附件名）、`attachments_check`（校验码）、`url`（相对路径，正文里插 `[img]./<url>[/img]`）。`error_code==9` = 文件过大，压缩后重传。

### 4.3 第二步：提交

```
POST post.php?action=<reply|quote|modify|new>&step=2
  &post_content=<转义后正文>
  [&post_subject=<转义后标题>]
  [&tid=<tid>&pid=<pid>]          # reply/quote/modify
  [&fid=<fid>|&stid=<stid>]       # new
  [&attachments=<A\tB>&attachments_check=<a\tb>]   # 多附件用 \t（%09）连接
  [&comment=1]                    # 贴条（楼中楼），action 用 reply；Android 额外带 nojump=1&lite=htmljs
  [&modify_append=1]              # 追加编辑
  [&anony=1]                      # 匿名（扣 100 铜币）
```

⚠️ **响应可能是 HTML 而非结构化数据**（Android 路线），成功判定靠文本含 `发贴完毕`（或 `@提醒每24小时不能超过50个`）。MNGA 的 XML 路线正常解析。

### 4.4 主题分类标签（发新帖时的分类下拉）

```
GET nuke.php?__lib=topic_key&__act=get&fid=<fid>&__output=8
```

响应：`data["0"]["N"]["0"]` 依次是分类名。

---

## 5. 收藏

### 5.1 新版多收藏夹（MNGA，`topic_favor_v2`，JSON）

```
POST nuke.php?__lib=topic_favor_v2&__act=add   form: tid=<tid>, folder=<夹id|-1新建>       # 收藏
POST nuke.php?__lib=topic_favor_v2&__act=del   form: tidarray=<tid>, folder=<夹id>        # 取消（⚠️参数名是 tidarray 不是 tid）
POST nuke.php?__lib=topic_favor_v2&__act=list_folder&page=1                                # 收藏夹列表：data["0"].*，含 id/name/length，有 default 键=默认夹
POST nuke.php?__lib=topic_favor_v2&__act=new_folder&raw=3     form: name=<名>, opt=<2默认|0>  # 新建，返回 folder_id 在 data["1"]或["0"]
POST nuke.php?__lib=topic_favor_v2&__act=modify_folder&raw=3  form: name, opt, folder        # 重命名/设默认
POST nuke.php?__lib=topic_favor_v2&__act=del_folder&raw=3     form: folder                   # 删除
```

### 5.2 旧版单收藏夹（Android，`topic_favor`）

```
POST nuke.php?__lib=topic_favor&__act=topic_favor&action=add&tid=<tid>[&pid=<pid>]&lite=js&noprefix   # 收藏主题/楼层
POST nuke.php   body: __lib=topic_favor&__act=topic_favor&__output=8&action=del&page=N&tidarray=<tid>[_<pid>]  # 取消
```

收藏列表 = `thread.php?favor=1`（见第 2 节）。

---

## 6. 点赞 / 点踩

```
POST nuke.php?__lib=topic_recommend&__act=add&value=<1|-1>&tid=<tid>&pid=<pid>     （JSON；主楼 pid=0）
```

响应 `data["1"]` 或 `data["0"]` 是分数增量 delta。据 delta 判断最终状态：点赞且 delta>0 → 已赞；点踩且 delta<0 → 已踩；否则视为取消（NGA 的赞踩是切换式）。

---

## 7. 举报

```
POST nuke.php?__lib=log_post&__act=report&raw=3&info=<转义后理由>&tid=<tid>&pid=<pid>
```

（Android 变体：`__output=8&charset=gbk`，且 query 和 form 各带一遍全部参数。）
响应：`{"data":{"0":"操作成功"}}` 或 `{"error":{"0":"你在217秒后方可举报"}}`。

---

## 8. 投票 / 投注（仅 Android 实现）

```
POST nuke.php?__lib=vote&raw=3&lite=js&__act=vote&tid=<tid>&voteid=<id1,id2,...>      # 投票
POST nuke.php?__lib=vote&raw=3&lite=js&__act=settle&tid=<tid>&voteid=<id1,id2,...>    # 结算/开奖
```

（query 与 body 内容相同。）投票题目数据来自帖子楼层的 `vote` 字段（`~` 分隔 kv 串）。响应 `data["0"]` 以"操作成功"开头即成功。

---

## 9. 通知 / 提醒（`nuke.php?__lib=noti`，JSON）

### 9.1 拉取全部

```
POST nuke.php?__lib=noti&__act=get_all
```

响应 `data["0"]` 下：`unread`（未读数）、`"0"`（回复/@/贴条类数组）、`"1"`（短信类数组）、`"2"`。每条是数字下标对象：

| Key | 含义 |
|---|---|
| `0` | 类型：`1`回复我的主题 `2`回复我的回复 `3`主题贴条 `4`回复贴条 `7`主题@我 `8`回复@我 `10`新短信 `11`短信回复 `17`帖子获评价 |
| `1` / `2` | 对方 uid / 用户名 |
| `5` / `6` | 主题标题 / tid |
| `7` / `8` | 对方 pid / 我的 pid |
| `9` / `10` | 时间戳 / 对方帖子所在页码 |

⚠️ **服务端不提供逐条已读状态**，需本地维护（MNGA 用 `<timestamp>-<type>-<tid>-<pid>` 作稳定 ID，刷新时只插入新条目不覆盖）。

### 9.2 清空全部

```
POST nuke.php?__lib=noti&raw=3&__act=del
```

---

## 10. 短消息（`nuke.php?__lib=message&__act=message`，JSON）

```
act=list&page=N                          # 会话列表：data["0"] 含 nextPage/currentPage 和 "0","1"… 会话（mid/subject/from_username/last_modify/posts/all_user）
act=read&mid=<mid>&page=N                # 会话详情：data["0"].userInfo / .allmsgs（id/from/subject/content/time）/ .nextPage
act=new&to=<收件人>&subject=<S>&content=<C>       # 新会话（MNGA 多收件人空格分隔；Android 逗号分隔）
act=reply&mid=<mid>&content=<C>[&subject=<S>]     # 回复
```

`all_user`/`allUsers` 是 `\t` 分隔的 (uid, username) 交替序列，按 2 个一组切分。`nextPage` 非空表示还有下一页。短信内相对图片路径 `[img]./mon_...` 拼附件域名。

**Android v4 实测可行的调用形态**（v3.7.6 老实现已被服务端拒绝，新实现是唯一验证过的 Android 路线）：
- 列表/详情走 GET + `lite=js`；发送走 POST，query 带 `lite=js&charset=gbk`，body 为 `act`/`mid`/`to`/`subject`/`content` 表单（`to` 用 GBK URLEncode，中文逗号需转英文逗号）。
- 发送成功判定字符串：响应含 `发送完毕` / `操作成功` / `@提醒每24小时不能超过50个`。

---

## 11. 用户

### 11.1 资料

```
POST nuke.php?__lib=ucp&__act=get&{uid=<uid> | username=<用户名>}     （JSON；uid 优先）
Referer: <host>/nuke.php?func=ucp&...                                 ← 必须带，否则拒绝
```

响应 `data["0"]`：`uid` `username` `money`(铜币：÷10000=金，余÷100=银，余=铜) `fame`/`rvrc`(÷10 显示) `posts`/`postnum` `email` `phone` `group` `regdate` `sign`/`signature` `avatar` `verified`(`-1`=NUKED) `muteTime` `buffs` `ipLoc` `reputation`(各版声望) `adminForums`。
用户不存在返回 `{"error":{"0":"找不到用户"}}`（注意它在"假错误"白名单里，要另判 user 是否为空）。

### 11.2 头像补充查询（资料接口没给头像时）

```
POST nuke.php?__lib=ucp&__act=get_avatar&uid=<uid>       # 只认 uid；URL 在 data["0"]
```

### 11.3 修改签名

```
POST nuke.php?__lib=set_sign&__act=set&uid=<自己uid>&sign=<转义后签名>[&raw=3&lite=js]
```

### 11.4 签到

```
POST nuke.php?__lib=check_in&__act=check_in           # MNGA
GET  nuke.php?__lib=check_in&__act=check_in&lite=js   # Android v4（自动签到，默认关）
```

无额外参数。"今天已经签到"视为成功；客户端按 UTC+8 日期本地去重。

### 11.5 官方屏蔽词（云同步，Android v4 新增）

```
POST nuke.php?__lib=ucp&__act=get_block_word&__output=8&uid=<uid>     # 读取
POST nuke.php?__lib=ucp&__act=set_block_word&__output=8&data=<D>      # 写入
Referer: <host>/nuke.php?func=ucp&uid=<uid>                            ← 必须带
```

- 读取响应 `data["0"]` 是**多行纯文本**：第 2 行 = 空格分隔的屏蔽关键词，第 3 行 = 空格分隔的 `uid/用户名` 对。
- 写入的 `data` 参数 = GBK URLEncode 的 `1\r\n<词列表>\r\n<用户列表>`（词/用户内部仍空格分隔）。
- 这是 NGA 网页版「控制面板→屏蔽」的同一份数据，可与网页端互通；Android v4 在 App 内把官方组做成只读，编辑引导去网页。

---

## 12. 端点速查表

| 端点 | `__lib` / 参数 | 功能 | 格式 |
|---|---|---|---|
| `app_api.php` | `home` / `category` | 版块分类树 | JSON |
| `forum.php` | `key=` | 版块搜索 | XML/JSON |
| `thread.php` | `fid/stid/page/key/favor/authorid/searchpost/recommend/order_by` | 主题列表·搜索·收藏夹·用户主题/回复 | XML / lite=js |
| `read.php` | `tid/page/pid/authorid/fav/opt` | 帖子详情 | XML / JSON / HTML |
| `post.php` | `action`（无 step）| 编辑上下文 + 附件凭证 | XML |
| `post.php` | `action` + `step=2` | 发帖/回复/引用/编辑/贴条 | XML / HTML |
| `<attach_url>` | multipart | 附件上传 | XML / JSON |
| `nuke.php` | `noti` | 通知拉取/清空 | JSON |
| `nuke.php` | `message` | 短信列表/详情/发送 | JSON |
| `nuke.php` | `ucp` | 用户资料/头像 | JSON |
| `nuke.php` | `ucp` / `get_block_word`·`set_block_word` | 官方屏蔽词云同步 | JSON |
| `nuke.php` | `set_sign` | 修改签名 | JSON |
| `nuke.php` | `check_in` | 签到 | JSON / lite=js |
| `nuke.php` | `topic_favor_v2`（新）/ `topic_favor`（旧） | 主题收藏 & 收藏夹 | JSON |
| `nuke.php` | `forum_favor2` | 版块收藏 | JSON |
| `nuke.php` | `topic_recommend` | 点赞/点踩 | JSON |
| `nuke.php` | `log_post` | 举报 | JSON |
| `nuke.php` | `user_option` | 子版块订阅/屏蔽 | JSON |
| `nuke.php` | `topic_key` | 主题分类标签 | JSON |
| `nuke.php` | `vote` | 投票/投注 | lite=js |
| `nuke.php` | `login`（WebView 打开） | 登录页 | HTML |
| ~~`login_check_code.php`~~ | — | 图形验证码（账密登录已废弃，勿用） | PNG |

---

## 13. 复刻要点（按踩坑概率排序）

1. **编码不统一是第一大坑**：响应 GBK/GB18030 回落；POST body GBK；`thread.php` 的 `key` 却是 UTF-8 而 `author` 是 GBK；登录用 `__inchst=UTF-8`。逐接口对照，别全局一刀切。
2. **响应不是合法 JSON/带前缀**，必须先做 0.6 节的清洗；`noprefix` 不总生效；`data` 里字符串数字键当数组，只能手工遍历。
3. **客户端身份必须声明**：老做法是 `User-Agent: Nga_Official/xxx`；Android v4 验证了更稳的新做法——UA 用系统 WebView UA，身份放辅助头 `X-User-Agent: Nga_Official`（`read.php` 另可用 `NGA_WP_JW/(;WINDOWS)`）。
4. **提交内容必须做 UTF-16 十进制实体转义**（emoji/ZWJ/变体选择符），读取时两轮解码 + 代理对还原。
5. **空值参数必须丢弃**（MNGA 路线大量逻辑依赖）。
6. **真实 tid 看 `quote_from`**；**`fav` 码从 `tpcurl` 提取**，访问隐藏帖必带。
7. **附件上传是两步**：先 `post.php` 拿 `auth`+`attach_url`，再 multipart 上传；发帖带 `attachments`+`attachments_check`（`\t` 连接）。
8. **收藏增删参数名不同**：加用 `tid`，删用 `tidarray`。**子版块订阅 `del`=订阅、`add`=屏蔽**（且可能按 type 再反转）。
9. **`post.php` 提交的响应可能是 HTML**，成功判定靠文本包含"发贴完毕"。
10. **HTTP 非 2xx 仍要解析 body**；五个"假错误"（`完毕/没找到/没有符合条件的结果/今天已经签到/找不到用户`）当成功处理。
11. **通知已读、热门话题、匿名昵称还原、骰子结果**都是客户端本地实现，服务端不提供。
12. **反封锁从第一天就设计**：格式参数交替（`lite=xml` ↔ `__output=10`）+ 成功组合缓存 + Web HTML 反解兜底 + 本地缓存兜底。
13. 子版块 `attributes` 魔法数（`{7,558,542,2606,2590,4654}`=已订阅、`>40`=可过滤）无文档依据，可能随 NGA 更新失效。
14. Android 项目里硬编码的 `bbs.ngacn.cc`/`nga.178.com`/`app.myauth.us`（登录、验证码、版面搜索、改头像图床）多为历史遗留、部分已失效，复刻时统一走可配置域名；其 `arrays.xml` 里 `nga.178.com"` 末尾多引号是 bug（v4.2.2 仍未修），别抄。
15. **图片域名不要硬编码**：附件域名从 `read.php` 响应的 `__GLOBAL._ATTACH_BASE_VIEW` 动态取（0.1 节），静态规则只作兜底——`img.nga.178.com` 已死就是前车之鉴。
16. **服务端字段类型会悄悄变**（例：`__T[].parent` 2024 年从对象变字符串化 JSON），手工解析层对每个字段都要做类型容错；遇到新解析失败先查 Justwen fork 最新提交。

## 14. 联调对拍资源

- MNGA Rust 侧每个 service 模块带真实网络集成测试（`#[ignore]`），内含真实样例 id：tid `45150945`（通用验证）、fid `650`（原神版）、uid `41417929`（MNGA 作者）。
- 第三方接口文档（MNGA `AGENTS.md` 推荐，交叉验证用）：
  - https://github.com/wolfcon/NGA-API-Documents/wiki
  - https://gitee.com/AgMonk/nga-api-doc
