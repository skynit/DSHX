# 手机能力、界面观察与操作小窗

[说明索引](README.md) · [术语与架构](architecture.md) · [返回项目](../README.md)

手机能力模块把模型提出的命名操作转交给 Android 系统；开关、系统授权、观察凭据和实际执行结果分别检查。

本文所称 **Android 宿主**是 `app/` 中由系统创建的 Kotlin 应用，负责页面与系统交接；**dsh** 是宿主启动的官方智能体引擎，负责会话、模型请求和工具调度；**Node** 是执行 dsh 与适配模块的 JavaScript 运行程序。其他通用概念见[术语表](architecture.md#术语与整体架构)。

**会话**由 dsh 创建并分配稳定编号，保存一段对话与工具事件；**工具**由引擎或插件注册，是模型可以提出调用的命名操作，执行结果由程序返回。

**工作区**是宿主准备的默认任务目录，也用于筛选项目资料与记忆；它与整个应用私有数据目录分开，不自动构成文件隔离。

## 使用入口

1. 先启动 dsh 并配置能正常对话的模型。
2. 在“设置 → 手机操作”打开系统无障碍设置，按需授予 DSHX 对应能力；悬浮提示和通知使用各自系统授权。
3. 在聊天中明确提出目标。需要观察或操作界面时，模型必须使用当前有效快照；看到系统动作回执后仍应核对真实界面结果。
4. 操作小窗提供立即停止入口。当前总开关及返回应用后的恢复规则见下方“开关与系统授权”，不要按旧版本的默认关闭行为判断。

## 职责与实现

手机模块把模型命名工具转成 Android 系统调用；当前 Node 侧注册 **28 个 `phone_*` 工具**。普通设备能力与屏幕无障碍能力分开实现。

| 文件或组件 | 创建方与职责 |
|---|---|
| [`phone.mjs`](../core/phone.mjs) | 由 dsh 固定插件配置加载，注册手机工具及参数、控制每轮失败处理，把截图回执保存为 dsh 图片附件 |
| [`PhoneControl.kt`](../app/src/main/java/local/dshx/app/PhoneControl.kt) | `Engine` 创建的原生总入口，检查开关、会话、锁屏、亮屏和超时，串行派发请求，并统一返回执行状态 |
| [`PhoneService.kt`](../app/src/main/java/local/dshx/app/PhoneService.kt) | 用户在系统设置授权后由 Android 创建的无障碍服务；无障碍服务是系统提供的界面节点观察与动作接口，本组件负责控件树、节点点击、文本替换、滚动、系统按键、截图和手势 |
| [`PhoneDevice.kt`](../app/src/main/java/local/dshx/app/PhoneDevice.kt) | `PhoneControl` 取得的共享设备能力对象，处理设备信息、剪贴板、通知、提问、分享、位置、传感器、手电等；按需检查独立开关和系统授权 |
| [`PhoneFiles.kt`](../app/src/main/java/local/dshx/app/PhoneFiles.kt) | `PhoneDevice` 使用的文件交接对象，区分应用工作区与系统选择器授权目录；处理限量文本读取、导出和文件分享 |
| [`PhoneWindow.kt`](../app/src/main/java/local/dshx/app/PhoneWindow.kt) | `PhoneControl` 创建的操作小窗状态，记录当前动作名称、次数和结果，持有立即停止入口 |
| [`PhoneWindowActivity.kt`](../app/src/main/java/local/dshx/app/PhoneWindowActivity.kt) | Android 创建的独立操作页面，在支持时进入系统画中画，显示操作提示；画中画是系统把现有页面缩小显示的能力，不是另一块虚拟屏幕 |
| [`phone_service.xml`](../app/src/main/res/xml/phone_service.xml) · [`phone_files.xml`](../app/src/main/res/xml/phone_files.xml) | 分别配置无障碍服务能力和系统文件分享允许交接的路径 |

工具按用途分组如下，每个名称都由 `phone.mjs` 注册，再由宿主对应实现执行。

| 类别 | 工具 |
|---|---|
| 状态与应用 | `phone_status`、`phone_apps`、`phone_open`、`phone_device` |
| 界面节点 | `phone_observe`、`phone_click`、`phone_input`、`phone_scroll` |
| 系统动作与视觉后备 | `phone_back`、`phone_home`、`phone_key`、`phone_screenshot`、`phone_tap`、`phone_swipe` |
| 文件与剪贴板 | `phone_clipboard`、`phone_read_file`、`phone_export` |
| 通知与用户交接 | `phone_notify`、`phone_toast`、`phone_ask`、`phone_vibrate`、`phone_share`、`phone_open_url`、`phone_overlay` |
| 位置与硬件 | `phone_location`、`phone_sensors`、`phone_sensor`、`phone_torch` |

**先观察再操作。** `phone_observe` 返回一次界面快照编号 `snapshot` 和节点编号 `node`。这些编号由原生服务依据真实窗口创建，绑定当前会话、界面状态和有效时间，不能由模型猜测。点击、输入和滚动必须引用有效观察；操作后重新观察结果。输入是替换控件全部文本，不是模拟逐字键盘输入，密码框不接收该操作。

**截图与坐标。** 常规操作优先使用无障碍控件树；只有节点信息不能回答当前问题或用户明确需要画面时才使用截图。截图需要 Android 11 及以上系统和支持图片输入的实际模型；坐标动作还绑定该次截图凭据，10 秒内有效且一次性消费。屏幕或窗口变化会使旧凭据失效，不能沿用旧坐标盲目重放。

**开关与系统授权。** 当前 `PhoneControl.enabled()` 默认返回开启，主页面 `onResume()` 会调用 `resume()` 恢复总开关及悬浮提示能力开关。小窗停止会立即关闭本轮手机操作；回到应用会恢复上述默认允许行为。剪贴板、文件、位置、传感器、手电、悬浮提示另按实际能力检查；无障碍、位置、相机、悬浮窗或目录访问等系统授权仍由用户在系统界面完成。旧记录中的“总开关默认关闭”属于当时实现，不能直接用于描述当前源码。

**结果与取消。** 回执的 `execution_state` 区分 `not_started`（尚未派发）、`completed`（已有确定调用回执）、`unknown`（动作可能已发生但结果不明）。系统接受动作不保证业务目标已完成，仍要观察实际界面。失败、超时和取消不自动重放；连续失败或能力撤销会停止本轮操作。`phone_ask` 最多等 60 秒，只承认用户实际选择；悬浮提示和手电在会话结束时收尾。手机桥不提供 root、ADB 或虚拟屏幕。

## 关联说明

[本机通信、会话登记与工具准入](bridge.md) · [典型执行流程](workflows.md) · [原生管理界面与官方聊天网页](interface.md)
