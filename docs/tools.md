# 文件、搜索、命令、附件与终端

[说明索引](README.md) · [术语与架构](architecture.md) · [返回项目](../README.md)

模型工具由引擎注册并根据模型请求执行；手动终端由用户直接操作。本文说明两种入口的执行方式和文件访问边界。

本文所称 **Android 宿主**是 `app/` 中由系统创建的 Kotlin 应用，负责页面与系统交接；**dsh** 是宿主启动的官方智能体引擎，负责会话、模型请求和工具调度；**Node** 是执行 dsh 与适配模块的 JavaScript 运行程序。其他通用概念见[术语表](architecture.md#术语与整体架构)。

**会话**由 dsh 创建并分配稳定编号，保存一段对话与工具事件；**工具**由引擎或插件注册，是模型可以提出调用的命名操作，执行结果由程序返回。

**Full（完全权限）**是宿主为有效登记会话返回的工具准入模式，使用应用已有系统权限；它不授予 root，也不替代各项系统授权。

## 职责与实现

**作用与入口。** 模型基础工具为 `read`、`write`、`edit`、`glob`、`grep`、默认持久 `bash`、单次隔离 `bash_isolated` 和六个 `terminal_*` 工具。主要业务实现来自官方 dsh，Android 通过 [`files.mjs`](../core/files.mjs)、[`policy.mjs`](../core/policy.mjs)、[`android.patch.yml`](../core/android.patch.yml) 和[构建补丁](../tools/core.py)适配路径、工具名称与执行环境。

`files.mjs` 导出的 `AndroidFiles` 继承上游文件服务，保留原子的文件替换和旧版本检查。“原子替换”指对外一次切换到完整新文件，避免读到写了一半的结果；旧版本检查用于阻止拿过时内容覆盖新编辑。实际模型工具进入后才应用对应调用上下文，引擎读取自身配置不与模型文件调用混用。

Full 下相对路径从实际工作目录解析，访问范围取决于 Android 为应用提供的系统访问权限。`glob` 和 `grep` 分别用于按文件名模式查找、按正文查找，底层使用构建时内置的 **ripgrep（rg）** 搜索程序。

**命令的两种模式。** Android 同时接入官方 `dsh-tool-bash-persistent` 与 `dsh-tool-bash`，避免自行实现命令完成协议和进程管理。前者注册默认 `bash`，后者在 Android 命名为 `bash_isolated`，因此两个实现可以同时存在：

| 入口 | 状态与用途 | 参数和结果 |
| --- | --- | --- |
| `bash` | 每个 Agent 启动时创建名为 `default` 的持久 shell；后续调用串行复用，保留 `cd`、`export`、函数及 `source` 激活环境 | 只接收 `command`；输出由官方完成标记附上命令退出码，默认命令超时 300 秒 |
| `bash_isolated` | 每次启动新 shell，适合独立检查或不希望影响默认环境的命令；不继承默认 shell 后来修改的变量、目录或激活环境 | 接收 `description`、`command`，可选 `workdir`、`timeoutMs`；返回独立 stdout、stderr 和退出码 |

持久终端启动时注入 `DSH_HOME`、`DSH_PROFILE`、`DSH_PROFILE_DIR` 及插件提供的 `DSH_WEB_URL` 等引擎环境快照，之后保留 shell 自身的变量修改；单次隔离 `bash_isolated` 每次重新获取引擎环境。

默认 shell 内 `cd` 不会改变文件工具的会话默认目录；随后调用文件工具时应明确使用目标路径。`bash_isolated` 未指定 `workdir` 时从会话工作区开始。两个入口和 `terminal_send` 都拒绝 `run_in_background=true`，全部工具拒绝 `sandbox_permissions`。需要长期服务时，在额外持久终端中启动并通过终端工具管理。

**额外持久终端。** **PTY（伪终端）**是系统提供的终端输入输出通道，由构建的 `node-pty` 模块创建，支持交互式 shell。引擎创建的终端注册表按实际 Agent 对象管理 PTY 的编号、访问与清理。模型可以创建多个终端，各自保留状态；不同 Agent 不能操作对方的终端。隔离指 shell 状态独立，文件系统和应用权限仍然共享。

| 工具 | 用途与主要参数 |
| --- | --- |
| `terminal_open` | 用 `{type:"shell",name?,cwd?}` 创建额外持久终端，返回 `sessionId`；初始目录从会话工作区解析 |
| `terminal_send` | 用 `{sessionId,text,submit?}` 连续发送命令或交互输入；默认提交 Enter，控制字符或只等待输出时设 `submit:false` |
| `terminal_read` | 用 `{sessionId,offset?,count?}` 读取保留输出，offset 从最新行向前计数 |
| `terminal_signal` | 用 `{sessionId,signal}` 给当前前台进程组发信号，例如 `SIGINT`；关闭 shell 使用 `terminal_close` |
| `terminal_close` | 用 `{sessionId}` 关闭终端，并等待所属进程树清理 |
| `terminal_list` | 列出当前 Agent 的终端；`default` 是默认 `bash` 使用的终端 |

同一 PTY 同时只能进行一个发送操作；并行任务、交互程序与长期服务应创建额外终端，避免直接给忙碌的默认 shell 输入命令。`terminal_send` 合并终端输出并返回等待原因：`stdin_read` 表示提示符或程序等待输入，`inferred_idle` 表示静默，`timeout` 表示本次等待达到上限，`session_exit` 表示顶层 shell 退出。前三者不能作为命令成功的证据；其中 timeout 不会终止前台程序，可用 read 追读、signal 中断。`sessionStatus.exitCode` 是顶层 shell 的退出码；逐命令退出码由 `bash` 的完成标记提供。

**生命周期。** 默认和额外终端在当前引擎进程、当前 Agent 生命周期内保持活跃，普通一轮对话结束不会关闭。Agent 销毁或引擎正常停止时由官方注册表清理所属进程。默认 `bash` 超时或取消会关闭并重置默认 shell；默认终端被关闭后，下一次 `bash` 重新创建。重建 Agent、恢复会话或重启引擎也会获得新 shell，目录、环境变量和激活状态需重新设置；文件改动继续保留。

**手动终端与工具调用。** 网页手动终端由用户操作，使用独立控制入口，不与模型默认终端共用状态。**PTC** 是上游让模型生成程序再组合工具的调用方式，Android 未启用它，当前使用工具名称和结构化参数直接调用。

**附件原理。** 文件选择授权由宿主交给网页，附件保存与会话引用由上游处理；图片处理使用随包提供的 sharp WebAssembly 版本。WebAssembly 是可由运行环境加载的跨平台二进制执行格式，这里用于避开不兼容 Android 的桌面图片原生库。图片上传、`phone_screenshot` 返回图片、模型 `read_image` 是三种入口，前两者可用不代表第三个模型工具已开放，也不代表当前服务商支持看图。

## 关联说明

[本机通信、会话登记与工具准入](bridge.md) · [插件管理与底层 Android 兼容](plugins.md) · [数据目录与备份范围](storage.md)
