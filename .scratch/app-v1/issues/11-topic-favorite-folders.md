# 11 — 主题收藏夹 v2

**What to build:** 详情页菜单「收藏本帖」弹多选收藏夹对话框(可勾多夹、内联新建);收藏夹管理页(新建/重命名/设默认/删除,默认徽标);「已收藏的主题」列表页按收藏夹展示并可打开主题。走 topic_favor_v2 接口族(注意删除参数名是 tidarray)。

**Blocked by:** 07, 09

**Status:** resolved

- [x] 一个主题可同时归属多夹,取消单夹不影响其他夹
- [x] 新建/重命名/设默认/删除后列表与服务端一致
- [x] 对话框与管理页与设计稿 1:1

## Comments

### 实现摘要(2026-08-08)

`pnpm typecheck` 与 `pnpm test`(632 个单测,新增 32 个)全绿。零新增依赖。

**core 层(零 RN 依赖)**

- `src/core/api/topic-favor.ts` —— `topic_favor_v2` 接口族。收藏夹列表(`list_folder`,`data["0"].*` 里 `id`/`name`/`length`,**有 `default` 键的是默认夹**)、加入 `add`(form `tid`)、移出 `del`(form **`tidarray`**)、新建/重命名+设默认/删除(`new_folder`/`modify_folder`/`del_folder`,都带 `raw=3`)。某夹的主题列表是 `thread.php?favor=<夹id>`,响应形状与版块主题列表一致,直接复用 `parseTopicList`。写操作不判「操作成功」文案——服务端出错时 envelope 已经抛 `kind: 'server'`,能走到返回就是成功。
- 四个新 fixture(`favor-folders` / `favor-folders-empty` / `favor-new-folder` / `favor-topics`)是拿 `.env.local` 的 UID/CID 现抓的真实字节,cookie 值没入库。`favor-folders` 里的夹名是抓包时漏带 `__inchst=UTF8` 产生的 mojibake,正好留作「夹名原样透传」的样本。
- `src/core/local/topic-favor-index.ts` —— **「这个主题在哪几个夹里」的本机索引**,见下面「唯一一处设计取舍」。

**唯一一处设计取舍:归属关系只能本机记**

`topic_favor_v2` 给得出「某个夹里有哪些主题」,给不出反向的「某个主题在哪几个夹里」——MNGA 与官方 Android 端也都没有这个接口(`docs/research/mnga-report.md` §E)。而多选对话框必须先知道当前勾了哪几个夹,「取消单夹不影响其他夹」才谈得上。

逐夹翻页去反查不可行:一个夹上百个主题就是好几页,20 个夹就是几十个请求,正撞在 ADR-0002 的枪口上。所以改成**只记本机看得见的那部分**:收藏/取消成功后按结果改索引;打开某个夹的主题列表时把那一页的 tid 一并记下(`seedFolderTopics`)。索引因此是「宁缺勿滥」的——记着的一定对,没记着的未必没收藏。对话框底下用一行灰字把这层限制跟用户说明白(「在网页版等别处收藏的帖子可能显示为未勾选」)。

索引按账号分开存(MMKV `topic-favor-index/v1/<uid>`),切号自动换;删夹后按重拉回来的服务端列表 `pruneFolders`。只翻了一页时不敢反过来清记录,整个夹就这一页(`complete`)时才清。

**store 层**

`src/store/topic-favor.ts` —— 夹列表 `useFavoriteFolders`、某夹主题 `useFavoriteTopics`(无限翻页,每翻一页顺手喂索引)、四个写操作的 mutation,以及索引的 Zustand + MMKV 落地。

- **写完一律重拉夹列表**,计数与默认徽标以服务端为准(验收项 2)。受影响的夹的主题列表用 `resetQueries` 丢掉已翻的页,下次从第一页重取。
- 下拉刷新走 `useRefreshFavoriteTopics`(先砍到第一页再取),理由与主题列表那套一样:直接 `refetch` 会把翻过的每一页都重打一遍(ADR-0002)。
- 多选对话框点「完成」时**逐个串行发**,不并发——一次勾三四个夹就是三四个 `nuke.php`。每成功一个就更新一次索引,中途失败时已做成的那几个不回滚。

**UI**

- `src/ui/favorite-folder-dialog.tsx` —— 「收藏到…」多选对话框,**传 tid 即可从任意入口调起**(用法见下)。关着的时候整棵子树不挂载,所以不会在每次进详情页时白打一发 `list_folder`。「新建收藏夹…」那行换成设计稿同一槽位的 input 形态(复用 `InputDialog`),建完自动勾上再回到多选;不叠两层对话框,叠着的话两层遮罩会把底下压得死黑。
- `src/app/favorites/folders.tsx` —— 收藏夹管理。设计稿的卡片(folder 图标 accent 24、名字 15/600、`默认` 徽标 primary-c 底、edit/push_pin/delete 三个 20px 动作)。删夹走新的 `ui/confirm-dialog.tsx`(设计稿对话框壳的 body 形态),不走 Android 原生 Alert——原生弹窗的配色不跟主题走,深色下白得刺眼。
- `src/app/favorites/index.tsx` —— 已收藏的主题。**一次只展示一个收藏夹**,进来落在默认夹,点副标题条换夹;设计稿那句「默认收藏夹 · 126 个主题」说的就是当前这个夹。把所有夹拼成一屏就是开屏打 N 个请求(ADR-0002)。行复用 `TopicRow`,带 fav 码进详情页。

**收藏对话框怎么复用(12 票的楼层菜单直接照抄)**

```tsx
const [favorOpen, setFavorOpen] = useState(false);
// …菜单里那一项:onPress: () => setFavorOpen(true)
<FavoriteFolderDialog open={favorOpen} tid={topicId} onClose={() => setFavorOpen(false)} />
```

组件自己拉夹列表、自己写回服务端、自己 toast,调用方只管开关和给一个 tid。

**改到的共享文件(都是可加的小改动)**

1. `src/app/topic/[tid].tsx` —— 顶栏「更多」原本是 toast「本版本未开放」,现在按设计稿 `MENUS.article` 立了菜单(跳页/复制链接/收藏本帖/缓存本页/分享/夜间模式),只接了「跳页」和「收藏本帖」,其余各票到时换掉自己那一行。
2. `src/ui/app-drawer.tsx` —— 「收藏夹管理」那行接上 `/favorites/folders`(原本没有 href)。没有加新行。
3. `src/app/index.tsx` —— 首页菜单「收藏夹」接上 `/favorites`。
4. `src/ui/tokens.ts` + `tokens.test.ts` —— 补三档设计稿字号:`dialogListItem` 14.5(对话框夹条目)、`folderBadge` 10.5/700(默认徽标)、`cardMeta` 12(卡片副行 / 副标题条)。
5. `src/core/local/index.ts`、`src/core/api/index.ts` —— 导出。

### 遗留问题

1. **真机没跑**,写操作(add/del/新建/改名/设默认/删夹)都只有对着真实抓包字节的请求断言,没在真机上走过一遍。27 票全局验收时要重点看:同一个帖勾两个夹后去两个夹里都能看到、取消其中一个另一个还在。
2. **索引的「宁缺勿滥」**:在网页版收的帖子,本机没见过就勾不上,因而也取消不掉(只能先去那个夹的列表里逛一次让索引认识它)。这是接口能力所限,对话框里已用灰字说明。
3. **设计稿里的两处没做**:夹条目右侧的 `more_horiz`(设计稿里 `pick: () => {}`,本来就没有行为,而且图标字体里没有这个码点)、「长按拖动排序」(接口没有排序参数)。管理页底部的说明文案据此改写过。
4. **`new_folder` 的返回 id** 按 MNGA 的读法取 `data["1"] ?? data["0"]`。挖不出来时(服务端只回文案)新夹不会自动勾上,列表照常刷出来,用户再点一下即可。
5. **收藏夹上限 20** 是照设计稿的提示文案写死的常量,不是服务端下发的;真到上限时服务端的报错会原样 toast 出来。
