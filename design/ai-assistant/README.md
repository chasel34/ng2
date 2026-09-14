# AI 助手设计稿

根据 [AI 阅读与对话助手需求](../../.scratch/ai-assistant/spec.md) 和 [开销控制与异常处理](../../.scratch/ai-assistant/cost-and-recovery.md) 绘制的交互原型与组件参考。交互、时序和组件结构参照 [Beautiful UI](https://www.beautifului.dev/)，视觉沿用 App 的 [Tokens.kt](../../app/src/main/kotlin/com/chasel/ng2n/ui/theme/Tokens.kt)，并按移动端触控调整。

| 文件 | 内容 |
| --- | --- |
| [Main.dc.html](Main.dc.html) | 主题详情中的完整交互：主题与楼层入口、半屏/全屏/收起、阅读范围、思考过程、工具调用、流式回答、来源预览、快捷操作与追问、停止。`scenario` 参数可切换达到额度、搜索不可用、生成中断 |
| [Entries.dc.html](Entries.dc.html) | 列表概览、列表单条主题、回复链、个人分析四个入口及各自的默认阅读范围 |
| [Persona.dc.html](Persona.dc.html) | 个人分析报告的全屏状态：倾向卡片、代表性发言与相反表述、变化时间线、敏感属性边界 |
| [History.dc.html](History.dc.html) | AI 对话历史：分类筛选、搜索与空状态、继续聊天、删除与撤销 |
| [Settings.dc.html](Settings.dc.html) | 服务商与 API Key、单次与每日额度、今日用量明细 |
| [Components.dc.html](Components.dc.html) | 组件参考：token、各组件的结构尺寸、时序、状态和对 Beautiful UI 的移动端调整 |
| [canvas.json](canvas.json) | 画板布局 |

`*.dc.html` 依赖同目录的 `support.js`，需要通过本地 HTTP 服务打开，例如在本目录运行 `python3 -m http.server` 后访问对应文件。

## 实现注意

- 新增 token：`green-c`、`accent-c`、`danger-c`，以及 hairline / btn / card / raised / overlay 五级阴影。暗色值在各文件的共享样式中。
- 金额、外部网页、核查结论和个人分析内容都是示例数据，额度默认值仍待样本测试确定。
- 与 Beautiful UI 的主要差异写在组件参考各节的「移动端」「与原组件的差异」中，未采用的组件列在页面末尾。
