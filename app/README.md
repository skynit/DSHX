# DSHX Android 宿主

[项目首页](../README.md) · [构建与安装](../docs/development.md) · [全部模块说明](../docs/README.md)

本模块生成正式应用的 Android 安装包，应用标识为 `local.dshx.app`。**宿主**是由 Android 系统创建的 Kotlin 应用，负责页面、引擎进程、系统权限、文件交接和语音生命周期；官方 dsh 引擎负责模型请求、会话与工具调度，二者通过本机认证连接协作。

## 入口与职责

| 入口 | 说明 |
|---|---|
| [`EngineService.kt`](src/main/java/local/dshx/app/EngineService.kt) 中的 `DshApp`、`EngineService` | 由系统创建应用共享状态及后台服务，持有引擎和语音对象。[生命周期](../docs/engine.md) |
| [`Engine.kt`](src/main/java/local/dshx/app/Engine.kt)、[`CoreStore.kt`](src/main/java/local/dshx/app/CoreStore.kt) | 准备运行资源、数据目录、启动认证和进程收尾。[运行包安装](../docs/runtime.md) |
| [`MainActivity.kt`](src/main/java/local/dshx/app/MainActivity.kt)、[`DshScreen.kt`](src/main/java/local/dshx/app/DshScreen.kt) | 系统页面、原生管理与官方聊天网页交接。[界面说明](../docs/interface.md) |
| [`SettingsScreen.kt`](src/main/java/local/dshx/app/SettingsScreen.kt) | 由原生页面调用，组织设置总览、助手与资料、语音交互、任务分工、权限及备份表单。 |
| [`AppMotion.kt`](src/main/java/local/dshx/app/AppMotion.kt)、[`PageTransition.kt`](src/main/java/local/dshx/app/PageTransition.kt) | 开发者配置的共享动画参数与页面手势实现；保存拖动预览，完成后才提交导航，不代替正式业务状态。[页面切换](../docs/interface.md) |
| [`PolicyBridge.kt`](src/main/java/local/dshx/app/PolicyBridge.kt) | 与 Node 侧交换会话、手机能力、本机推理及管理请求。[本机通信](../docs/bridge.md) |
| [`PhoneControl.kt`](src/main/java/local/dshx/app/PhoneControl.kt)、[`VoiceControl.kt`](src/main/java/local/dshx/app/VoiceControl.kt) | 分别协调系统能力和语音流程。[手机能力](../docs/phone.md)、[语音与小模型](../docs/voice.md) |
| [`MemoryStore.kt`](src/main/java/local/dshx/app/MemoryStore.kt)、[`KnowledgeStore.kt`](src/main/java/local/dshx/app/KnowledgeStore.kt) | 原生摘要解析和管理交接；正式业务状态由 Node 模块写入。[记忆](../docs/memory.md)、[知识库](../docs/knowledge.md) |

## 构建与验证

先在仓库根目录准备语音依赖、目标架构运行包及 Android 开发工具，再执行 `./gradlew :app:assembleDebug`。完整前置条件、命令、安装步骤和成功判断以[开发说明](../docs/development.md)为准，不能省略资源准备直接把 Gradle 构建当作完整运行环境。

默认输出为 `app/build/outputs/apk/debug/app-debug.apk`。当前配置为 `0.1.0-dev`、最低 API 26、目标行为 API 28、编译接口 API 36；一个安装包只包含所选架构。声明的最低系统级别不保证所有机型和系统功能均已验收。

仅运行本包相关具名测试，现有用例集中在 [`CoreTest.kt`](src/test/java/local/dshx/app/CoreTest.kt)。构建通过、界面检查、系统授权、真实模型回答和数据恢复分别验证。

语音输入调用手机系统识别服务，无需下载整句识别模型；本机仅保留关键词唤醒与可选文字推理模型。回答逐段交给 Edge TTS 或用户配置的火山服务在线播报，支持语音及按钮打断；系统服务可用性、联网合成和设备播放需分别验证，见[语音说明](../docs/voice.md)。

## 数据与历史材料

私有目录、旧路径迁移和两类归档见[数据目录](../docs/storage.md)及[迁移与备份](../docs/backup.md)。早期包的逐轮结果已保存在本地 `plan/`，不代表当前源码重新通过对应检查。
