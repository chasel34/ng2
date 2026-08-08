# 15 — 搜索三合一

**What to build:** 首页/列表页搜索图标进搜索页:三 tab(搜主题/搜板块/搜用户)+ 搜索选项(当前板块/全部板块单选、包括正文勾选)+ 各 tab 独立搜索历史(可清空/删单条)。三种结果页:主题结果(复用列表行,注意 key 是 UTF-8 编码而 author 是 GBK)、版块结果(可进入/收藏)、用户结果(纯数字按 uid 否则按名,点击进资料页)。

**Blocked by:** 05, 14

**Status:** resolved

- [x] 本版/全站/含正文三种参数组合结果正确
- [x] 中文关键词编码正确(thread.php key=UTF-8、forum.php key=GBK 的差异有单测)
- [x] 搜索历史持久化,记录 tab 与范围;搜索首页与设计稿 1:1

## Comments

- 2026-08-08 实现要点:
  - **core/api/search.ts**:`fetchTopicSearch`(thread.php,key 走默认 UTF-8 + `__inchst=UTF8`;
    fid/stid 二选一限定本版、`content=1` 含正文;「没有符合条件的结果」假错误归一成空页,
    解析复用 `parseTopicList`)+ `fetchBoardSearch`(forum.php,key 用 `gbk()` 标记走 GBK
    urlencode——2026-08-08 真机对拍:UTF-8 的 key 服务端按 GBK 解成乱码直接搜不到)+
    `parseUserSearchInput`(纯数字按 uid,否则按用户名;超出安全整数的数字串按名字兜底)。
  - **版块搜索响应形状**(真实抓包,fixture `forum-search-key.gbk.bin`):条目直接以数字键挂在
    data 上(不是 data["0"]),每条 `{fid, stid, name, descrip, relevance, url, parent:{name}}`;
    合集 stid 非 0 且 fid 是宿主版块,身份规则与分类树同一条(stid 优先)。无分页,上限 100 条。
  - **用户搜索**:ucp 资料接口本来就 uid/username 二选一(API 文档 §11.1),
    `fetchUserProfileByName` 与按 uid 查共用一条解析路;中文名 UTF-8 编码真机验证过。
  - **实测修正**:`content=1` 的结果**不带 `__P`**,三种组合的结果统一是普通主题行,
    所以主题结果只用 `TopicRow`,ReplyRow 用不上;三种组合的 `__ROWS` 都是有效总数,总页数可信。
  - **store/search.ts**:三个查询 hook(范围与含正文进 queryKey)+ `useSearchHistory`
    (zustand + MMKV `search/history`,各 tab 独立、记录 scope/content、每 tab 留 20 条,
    同词同范围去重挪前;删单条/清空只动当前 tab)。
  - **app/search.tsx**:设计稿 isSearch 屏 1:1(顶栏输入框 40/圆角 6、tab 48 + 3px 指示条、
    选项行 radio/checkbox 23、历史行 13/16 内边距);「当前板块」只在从列表页带 boardId
    进来时出现且默认选中;历史条目点击按存下的范围原样重搜。三种结果列表按现有设计语言延伸:
    主题=TopicRow 无限滚动 + listSub 统计条,版块=BoardIcon 行 + 星标收藏(复用 board-favor
    乐观切换),用户=头像资料卡点进 `/user/[uid]`。
  - 共享文件只加了入口:首页搜索图标 `push('/search')`,列表页带
    `boardId/kind/boardName`;`tokens.ts` 新增 `searchSection`(16/600)一档并登记进
    tokens.test.ts。
  - 新 fixture 三份(thread-search-key / forum-search-key / forum-search-none,`__CU.uid`
    已脱敏);`search.smoke.test.ts` 联网冒烟四条(NGA_INTEGRATION=1 才跑)当天全过。
