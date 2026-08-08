# 23 — 签到 + 修改签名 + 子版块订阅

**What to build:** 抽屉「登录账号」下方新增「每日签到」行(设计语言延伸):点击调签到接口,「今天已经签到」按成功处理,本地按 UTC+8 日期去重并显示今日状态;资料页自己的签名可编辑保存(提交转义);子版块页:订阅/屏蔽按钮按 attributes 魔法数判定状态,操作走 user_option(注意 del=订阅、add=屏蔽的反转语义)。

**Blocked by:** 05, 09

**Status:** implemented

- [x] 签到本地去重单测;重复点击不重复请求
- [x] 签名含 emoji 时转义提交成功、回读一致
- [x] 子版块订阅状态判定与操作语义单测(含 type 反转分支)

## Comments

实现落点:

- 签到:`src/core/api/check-in.ts`(接口)+ `src/core/local/check-in.ts`(UTC+8 日期去重)+ `src/store/check-in.ts`(MMKV 持久化、在途去重)+ 抽屉那一行。「今天已经签到」是 core/net 假错误白名单里的词,envelope 已按成功返回,api 层只把它标出来让 UI 换句话说。
- 签名:`src/core/api/set-sign.ts`,提交前过新写的 `escapeForSubmit`(core/bbcode/entities.ts,与 `unescapeNgaText` 互为逆向)。资料页只对当前账号显示「编辑」,保存后 invalidate 资料查询重新回读。
- 子版块:`src/core/api/sub-board.ts`(魔法数判定 + add/del 反转)+ `src/store/sub-boards.ts`(乐观状态,盖在 attributes 上)+ 新页 `src/app/board/sub-boards.tsx`(版块页菜单「子版块」进入)。`filter_id`/`attributes` 由 `parseSubBoard` 从 `__F.sub_forums` 的第 3、4 项带出来。

单测都是 mock fetcher 对拍请求参数(不打真实请求),`pnpm test` / `pnpm typecheck` 全绿。

留待真机验收(本地验不了):

1. 抽屉「每日签到」点一次是否真签上(次日再点应重新发请求);当天第二次点击应只出提示、不发请求(可用抓包/日志确认)。
2. 资料页改签名:含 emoji + BBCode 的签名保存后返回、重进资料页,显示是否与输入一致(转义链路的真实验证)。
3. 子版块订阅/屏蔽:操作后回版块列表,被屏蔽子版块的主题是否真的不再出现;重进版块页(重新拉 `__F.sub_forums`)时 attributes 是否与本地显示的状态一致——**魔法数是试出来的,失效的表现就是这一步对不上**。
4. `user_option` 的 `type` 反转分支:真实样本里子版块几乎都带 `filter_id`(type=1),type=0 那一支只有单测覆盖,真机遇到再确认。
