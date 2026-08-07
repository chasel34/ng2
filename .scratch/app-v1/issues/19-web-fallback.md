# 19 — Web 反解 + 网页兜底页

**What to build:** 反封锁链后半段:read.php 请求不带格式参数拿网页 HTML,从内联 JS(postArg.proc / userInfo.setAll / __PAGE / msgcode 错误标记)反解出与正常接口同构的数据,详情页无感继续原生渲染,顶部出现「原生解析失败,已切换为网页数据源」提示条(可重试原生);反解也失败时进网页版 WebView 兜底页(带「用 APP 阅读这一页」回切按钮与网页菜单)。Web 反解档位可配(Disabled/Secondary/Primary/Only)。

**Blocked by:** 07, 18

**Status:** ready-for-agent

- [ ] 用真实网页 HTML fixture 的反解单测:楼层元数据/用户/分页/错误四类都覆盖
- [ ] 反解数据走同一渲染管线,楼层显示与正常接口一致
- [ ] 兜底页与提示条与设计稿 1:1,回切动作生效

## Comments
