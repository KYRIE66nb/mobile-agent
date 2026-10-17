---
name: android-use
description: 通过 AUA（Android Use Agent）MCP 工具高效操作真实 Android 手机；本对话首次使用先 aua_setup status 检查通道，缺失步骤补齐后 aua_ready。默认乐观执行导航、点击、输入和滚动，在下一步观察时顺带判断进度，只对关键最终结果显式验收；指导 App 查找、列表采集、屏幕预览及密码/支付认证接管。
---

# 用 AUA 完成 Android 任务

新对话没有本对话的有效 READY 证明时，先执行初始化，再开始手机任务；此前聊天成功、安装过插件或打开过预览都不替代本对话 READY。通道未就绪是需要继续处理的启动步骤，不能据此提前结束任务。已有配置默认复用，只补齐缺失步骤。

先确定用户要交付的结果和完成条件。默认把一次正常返回的动作当作已按计划执行；不要为每次点击、切页、Home、启动或输入专门调用观察来证明成功。下一步需要页面信息时读取一次并顺带判断上一步是否生效；明确未生效再调整。最终关键操作、发送/提交结果、关键文本与交付物需要实际结果证据。

工具的 `ok`、`verified`、`complete` 只描述对应调用，不代表整个任务完成。`verified=false` 表示没有单独验收，不是失败，不要求停下。

## READY 与通道

本对话首次使用手机时直接调用 `aua_setup(action="status")`：它逐级检查 adb → 设备授权 → App 安装 → 无障碍 → 配对 token → 桥开关 → 转发可达。`ok=false` 时按 `missing`/`next_action` 顺序补齐：`enable_accessibility`（adb 自动开通，通常无需手动进设置）→ `pair`（取回 Bearer token）→ `start_bridge` → `forward`，然后 `aua_ready`。

- `aua_ready` 返回 `ready=true`：通道可用。直接复用 READY 的 `observation` 规划下一步，不立即重复 observe；setup 的 `ok=true` 只说明该配置步骤成功。
- `aua_unreachable`：桥不可达是启动分支，不是任务失败——继续 `aua_setup status` 补齐。
- `accessibility_off`：桥在响应但无障碍未连接，跑 `enable_accessibility` 或让用户在系统设置里开启。
- `session_busy`/`device_busy`：另一操作进行中，等待而非重试同一动作。

`aua_ready(screenshot=false)` 只跳过截图验证，仍检查树、视口、前台和控制状态。认证暂停期间不能 READY；等待用户完成后先 `aua_screen(action="resume")` 再检查。

## 按下一步需要选择观察

已知唯一语义目标和已知导航路径用默认 `observe="none"`，可连续执行或合并 batch。下一步需要辨认未知页面、选区域或读内容时，在本次动作直接设 `observe="tree"/"screenshot"/"both"`，用返回的 `observation` 同时规划下一步和顺带判断上一动作。独立读取用 `aua_observe(mode=...)`，不要混用 mode 与 observe。

节点字段：`type` 是短类名（Button/EditText/RecyclerView…）；`rect=[x,y,width,height]` 单位是屏幕像素；`tap` 是预计算中心点；`id` 是 view resource id；未出现的布尔字段是默认值——`enabled`/`visible` 省略为 true，`clickable`/`editable`/`scrollable`/`focused`/`checked`/`selected` 省略为 false。`actions` 列出节点支持的语义动作（click/long_click/scroll_*）。

树被截断（`truncated`）或节点在视口内，不证明内容全量或目标未被遮挡；固定表头和浮层可能盖住节点。保持 `max_nodes` 默认 200；只查一个目标用 `aua_find`/`aua_wait`。

截图随同一结果以图片返回，已缩放：`图像像素 × image.pixel_to_point[x,y] = 屏幕像素`，坐标传给 `aua_tap x/y` 时必须是屏幕像素。

## 选择器

selector 字段：`label`（text 或 contentDescription 精确）、`label_contains`、`name`（view id 精确）、`value`（text 精确）、`type`（短类名）、`enabled`/`clickable`/`editable`/`scrollable` 布尔过滤、`text_regex`/`desc_regex`、`index`（多个匹配取第 n 个）。

- 一处嵌套的多个匹配自动选最内层可点击/可编辑节点；分散的多个匹配返回 `ambiguous_target` + `candidates[]`（含 tap 点与 index）——按 index 或坐标点选，不要换写法重试。
- `no_such_element`：不要尝试其它 selector 写法，直接看错误里的截图用 x/y。
- `offscreen_target`：先滚动到可见再操作；`occluded`/弹层：先处理浮层再点。

## 界面异常时先看截图，再决定操作

截图是异常兜底。selector/焦点失败、滚动找不到可点击目标、`no_scroll_progress`、输入回读不符、预期页面没出现时，先查看结果里的当前截图，再决定下一步；没有截图时取一次 `aua_observe(mode="screenshot")`。不要先重读整树、换标签写法或原样重试。

- 输入：先点截图中实际可见的输入框，再调用不带 selector 的 `aua_type_text(text=...)`。`no_focused_field` 表示上一个点击可能被浮层拦截，不能原样重复那组坐标。
- 滑动无进展不证明列表为空或已完整：检查末尾控件/空白、弹窗、区域与方向。

## 动作语义

- `aua_tap`：selector 命中可点击节点时走语义 click（比坐标可靠）；否则坐标 tap。`expect`/`observe` 按需。
- `aua_long_press`：坐标长按（上下文菜单、拖拽起点）。
- `aua_drag`：用 `x1/y1/x2/y2` 屏幕像素发送一次拖动；可传 `observation_id` 与 `duration_ms`。方向翻页优先用 `aua_swipe`。
- `aua_swipe`：一次手势；`verify=true` 检查锚点位移一次，失败回截图，不再发手势。
- `aua_type_text`：整条发送；自动按至多 500 UTF-16 单元分片，最终字段含已有前缀最多 2000 单元，超限会在写入前拒绝。`input_complete=false` 时拿 `continue_token` 单独续传，期间不要操作手机。续传绑定原 session/App/视口/输入框/焦点/已写前缀；`input_continuation_expired` 后先读实际文本，只补缺失内容，不能重发全文。`replace=false` 追加。`submit` 发送 Enter——无障碍通道可能不支持，退回点发送按钮。换行需 `allow_newlines` 显式声明。
- `aua_screenshot`：请求 Android 系统截图，保存到手机相册/Screenshots，用于 App 图片选择器；返回 `screenshot_requested=true` 仅证明请求被接受，仍需查看相册确认。`aua_observe(mode="screenshot")` 只把画面交给电脑，不保存到手机。截图结果不确定时不可重试。
- `aua_press_button`：home/back/recents/volume/enter/escape/delete/tab/dpad_*；`verify` 只对 home/back/recents 有意义。
- `aua_launch_app`：先用 `aua_apps` 解析真实包名，不要猜；`verify=true` 轮询前台 5 秒。
- `aua_batch`：≤20 个已知步骤一次往返；遇错/预算停在 `stopped_at`，从该步继续，不重放已完成步骤。
- `aua_scroll_find`：每调最多一次滑动；未解决返回截图让你看——不要自动加 `max_swipes` 重试。
- `aua_collect_list`：按 `row_type`（短类名）采集去重分页；返回 `coverage_verified` 与限制说明，业务完整性需自行核对。

## 屏幕预览

`aua_screen(action="open")` 在浏览器打开本地实时预览。用户可点击、拖动、滚轮翻页，使用返回/主屏幕/最近任务按钮；控件共用 Agent 会话和操作锁。页面可下载当前画面或请求系统截图到手机相册。模型继续使用 `aua_*` 工具及新鲜观察，预览不能替代模型观察或最终核验。

用户需要在手机上输入密码/验证码/支付确认时：先调用 `aua_screen(action="pause")` 并确认成功，再请用户在真机完成。暂停会等待已接受动作结束，保留最后画面并禁止新图像和手机控制；暂停失败时不能声称接管已安全建立。用户确认完成后调用 `aua_screen(action="resume")`，等待新帧后再操作。旧帧、断流、方向变化或锁冲突时不要原样重放坐标。

## 永不盲重放

`action_uncertain`/`aua_unreachable` 之后不确定动作是否生效：先读最新状态再决定，绝不直接重复同一动作。`stale_observation`/`observation_mismatch` 表示观察过期——重新 observe 后继续，不重放。输入/提交类错误同样：读实际字段与页面，只补缺失部分。
