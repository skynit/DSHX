# 早期原型与 P0 证据工具

[说明索引](README.md) · [术语与架构](architecture.md) · [返回项目](../README.md)

prototype 是项目早期独立安装的交互原型；P0 是维护者组织的早期可行性验证阶段。这些文件不进入正式应用的运行链路。

本文所称 **Android 宿主**是 `app/` 中由系统创建的 Kotlin 应用，负责页面与系统交接；**dsh** 是宿主启动的官方智能体引擎，负责会话、模型请求和工具调度；**Node** 是执行 dsh 与适配模块的 JavaScript 运行程序。其他通用概念见[术语表](architecture.md#术语与整体架构)。

**会话**由 dsh 创建并分配稳定编号，保存一段对话与工具事件；**工具**由引擎或插件注册，是模型可以提出调用的命名操作，执行结果由程序返回。

## 职责与实现

`prototype` 是独立包 `local.dshx.prototype`，帮助早期检查交互，不联网、不调用模型、不操作其他应用。其 [`MainActivity.kt`](../prototype/src/main/java/local/dshx/prototype/MainActivity.kt) 由 Android 创建页面，[`PrototypeApp.kt`](../prototype/src/main/java/local/dshx/prototype/PrototypeApp.kt) 持有共享状态和后台线程，[`Workspace.kt`](../prototype/src/main/java/local/dshx/prototype/Workspace.kt) 管理导入文件副本、按 Markdown 提取事项、一次撤销及冲突恢复副本，[`TaskFlow.kt`](../prototype/src/main/java/local/dshx/prototype/TaskFlow.kt) 演练确认、停止和结果不明状态。正式 `app/` 不通过这些类执行真实聊天或手机动作。

本地 `p0/` 保存固定任务样本、预期结果、用户走查模板和当时环境证据，由 Git 忽略。P0 指项目早期方案与可行性验证阶段，由维护者组织，不是应用里的常驻服务。[`tools/p0.py`](../tools/p0.py) 保留在仓库，只检查本地样本完整性、采集开发环境或指定设备信息，不安装应用、不请求模型，也不自动判定产品验收通过。旧文档中的前置门槛和模拟能力应结合后续决策阅读，不能反向覆盖当前实现。三 App 选型、五人研究等旧场景不再作为基本移植的前置条件；当前正式模块与原型的关系见[整体架构](architecture.md)。

## 关联说明

[术语、架构与目录](architecture.md) · [构建与定向验证](development.md) · [原生管理界面与官方聊天网页](interface.md)
