# 验收公共说明

- 项目 /Users/cola/Documents/code/ng2，功能行为规范：docs/ai-assistant.md；票在 .scratch/ai-assistant/issues/；设计稿 design/ai-assistant/*.dc.html（可用 grep 查文案）。
- 模拟器 emulator-5554（1080x2400），dev 包 com.chasel.ng2.dev 已安装最新 debug 构建并登录 NGA 账号；DeepSeek Key 已在设置里保存；单次额度已选「长楼与个人分析」，每日上限 US$1.00（如遇每日额度卡且不是在验它，记录后到设置把上限改为 3.00 再继续）。
- 只用模拟器，不要 rebuild/reinstall，不要改代码，不要 git 操作，不要读 .env.local。
- 操作工具：/private/tmp/claude-501/-Users-cola-Documents-code-ng2/819b36d3-4f59-4c2c-b572-72db8b5dd9b7/scratchpad/ui.sh
  - `ui.sh dump` 打印可见节点 文本 [x1,y1][x2,y2] C(可点击)；`ui.sh tap X Y`；`ui.sh text 'str'`（adb input text，中文不可用，中文用 `adb shell am broadcast` 不可靠，可用英文提问）；`ui.sh key N`（4=Back 隐藏键盘/返回）；`ui.sh swipe x1 y1 x2 y2 [ms]`；`ui.sh shot name` 存 PNG 到同目录后用 Read 查看。
  - 优先用 dump 判断状态，只在需要看渲染效果（Markdown、颜色、布局、动画结果）时截图；每张截图看完即可，不要反复截同一屏。
  - 输入框输入后键盘遮挡按钮：先 `key 4` 隐藏键盘再点。
  - logcat：`adb -s emulator-5554 logcat -d | grep -E 'ng2n|AndroidRuntime'` 查崩溃。
- 模型调用真实付费，流式回答需等待：用 dump 轮询（每 3–5 秒）而不是固定长 sleep；单次分析 2 分钟仍无变化视为卡住并记录。
- 找主题：从首页版块（如「网事杂谈」）进入列表选一个回复较多的主题；需要图片时挑标题带图或进去看到图的主题。
- 输出：把问题清单写到指定文件，格式每条：`- [P1|P2|P3] 现象；复现步骤；期望（引用票/文档条款）；证据（截图文件名或 dump 片段）`。P1=功能不可用/崩溃/付费失控，P2=行为与票不符，P3=文案/样式细节。没有问题的项也简要列「通过」。最终回复只写文件路径 + 问题数量统计 + 3 行内摘要。
