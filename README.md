# DSHX

**DSHX 的最终产品是可自定义身份和提示词的手机 AI 助手，支持语音对话、声音唤醒，并通过小模型分工降低 token 消耗。** 第一阶段先将真实 dsh 移植到 Android，复用官方引擎、聊天网页、模型配置、会话和插件机制，用 Kotlin 实现手机宿主。语音与小模型部署尚未实现；实现状态与后续验收要求见 产品设计（本地文档）。当前正式模块是 `app/`，旧 `prototype/` 仅保留早期产品实验。

2026-10-22 当前 V2 已完成助手文字设置、后台调用账本和真实单模型基线验收。按用户最新要求移除原生设置页的整块用量统计，后台记录继续保留。原生 16 项、ARM 运行时 15 项定向测试通过；真实任务包含名字与记忆、模型文件读写、摘要后回忆和流式取消。全部 26 次请求尝试中的首次失败、重试和取消均保留；摘要背景准备的修订及取消时实际输出的限制说明见 本地助手配置验证记录（仅本地保留）。最终双架构调试包位于 `build/outputs/v2/minimal-settings-dev/`，已安装验收，没有正式发布。以下 V1 轮次与旧产物说明按历史保留。

当前已推进到 V1-B，并补充 V1-C 的手机输入与 ARM 真机验证：离线运行环境、后台启动停止、原版聊天、文件读写与搜索、前台 bash、手动终端、附件和诊断。按用户要求默认完全权限，不提供会话沙箱管理，不要求在原生和网页分别开启 Full。Full 使用本应用已有的系统权限，工作区是默认目录，不增加手机 root 权限。普通退出重开或停止再启动复用安装环境；首次安装、更新及有记录的引擎故障才完整校验，正常命令和停止不自动搬移用户文件或重建配置。其他未适配工具仍不可用。

2026-10-22 本轮先补插件安装与管理：复用原版插件页，接入固定 pnpm，补充安装内容检查、取消信号转发和安装/启用失败配置恢复。已完成 ARM 真机插件 4/4、app 定向 3/3 测试；修复 pnpm 查询依赖缺失 npm 的问题，查询用 npm 固定 `11.6.2`。页面与公网等验收仍待完成，没有构建或发布新安装包，已有 APK 不含本次改动。默认 Full 和普通重开复用环境的行为保持，详见 插件流程（本地文档）。

ARM 真机已接通用户的 DeepSeek 官方服务，模型为 `deepseek-flash`，自定义验收身份有实际回复。语音、小模型部署、完整手机操作和正式发布验收仍未完成。最新默认 Full 的 ARM 开发包、实际测试和剩余事项见 本地交付记录（仅本地保留），旧轮次验证保留在 [app/README.md](app/README.md)。

界面使用 **Miuix 0.8.8**，参考 Qoder 的暖中性色、留白与克制边框，重新设计为工作台、聊天、设置。支持跟随系统、浅色和深色。聊天继续使用原版 dsh 页面。

手机输入已补充竖屏与短横屏适配：打开键盘时隐藏原生底部导航，横屏空间不足时让正文与原有操作按钮并排，保留附件可见性；收起键盘后恢复完整标题和统计区。最终开发包已在 API 36 x86_64 模拟器覆盖安装并恢复原会话，实际截图和边界见 app 验证记录。

## 技术来源与致谢

DSHX 直接使用 [DeepSeek 官方 deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 提供的智能体引擎、聊天网页和插件机制，并维护 Android 适配补丁。这些上游能力不作为 DSHX 原创成果。

Android 本地运行环境的技术选型参考了 [Soodok / Deepseek-Harness-Local-Android（DSH Mobile）](https://github.com/Soodok/Deepseek-Harness-Local-Android) 的实践，包括 Android 版 Node、本机引擎、随 APK 分发运行包及 WebView 承载聊天页面。感谢该项目公开实现与验证经验。DSHX 维护自己的 Android 宿主、原生管理页面、运行包构建和系统适配；本次登记不据此宣称所有文件均为独立原创。

来源记录（本地文档） 区分直接依赖、技术参考和待补证的代码来源，记录参考版本、模块对应关系及首次 Git 基线。设计参考矩阵（本地文档） 保留其他项目的设计启发；设计目标不等于已经实现的能力。参考项目的版权声明保留在 [DSH Mobile 许可原文](licenses/DSH-Mobile-LICENSE.txt)。

## 构建

需要 JDK 17、Android SDK 36、NDK 28.2.13676358、Python 3.11+、Node/npm、curl、dpkg-deb、readelf 和 Node N-API 头文件。N-API 是 Node 提供给 C 扩展的稳定接口，此项目用它适配文件锁、原子文件发布，并编译官方 node-pty 终端模块。

```bash
python3 tools/core.py build --abi arm64-v8a
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest \
  --tests 'local.dshx.app.CoreTest.normalStartupReusesTheInstallReceiptWithoutScanningTheInventory' \
  --tests 'local.dshx.app.CoreTest.defaultFullBelongsToLiveSessionsAndDoesNotStopWhenARootEnds'
```

模拟器使用 `--abi x86_64`。每次构建运行环境会执行锁定依赖的 `npm ci --ignore-scripts`，校验固定的 Termux 包并生成离线资源。每个 APK 只含所选架构，切换架构后必须重新构建 APK。

运行环境统一命名为 `core`：源码与依赖在 `core/`，构建入口为 `tools/core.py`，APK 资源为 `core.zip`、`core.json` 和 `core-info.json`。手机上的安装目录为 `files/core/`。从旧目录布局覆盖升级后，首次启动会在新目录安装运行环境；用户配置与会话所在的 `files/home/`、工作文件所在的 `files/workspace/` 保留，旧运行目录不自动删除。

- Android 最低 API 26、targetSdk 28，当前只做手动安装开发包。
- 网页组件要求 Chromium 126 或更新版本。
- 引擎只监听本机地址，随机端口并带启动认证。认证地址不在界面或诊断中展示。
- 会话和模型配置放在应用私有目录，和运行环境版本目录分开。卸载或清除应用数据会删除它们。
- 模型请求发送给用户配置的服务商；本地引擎不代表离线推理。
- 未承诺恶意同一应用用户身份代码之间的文件或网络隔离。

## 文档

- 来源记录（本地文档）：技术参考、依赖来源、许可边界及 Git 记录规则。
- 本地交付记录（仅本地保留）：2026-10-22 实际完成程度、安装包、验证边界和剩余交付顺序。
- 本地能力核对记录（仅本地保留）：源码位置、用户入口、六个模型工具与其他未开放能力、已有证据及实际缺口。
- 本地发行准备记录（仅本地保留）：已归集的第三方声明和归档、摘要核对、逐包配方对应及剩余发行事项；未生成新安装包。
- 产品设计（本地文档）：移植目标、首次使用和交互。
- 本地实施计划（仅本地保留） · 本地计划摘要（仅本地保留）：V1-A/B/C 的阶段和证据要求。
- 设计索引（本地文档） · 决策记录（本地文档）：当前决策及历史演变。
- 架构（本地文档）：完整目标设计，具体已实现范围以 app 说明为准。
- 工具集（本地文档） · 插件（本地文档）：实际适配、插件安装管理流程与待验收项。
- [旧原型](prototype/README.md) · [P0 记录](p0/README.md)：早期实验，不能替代真实移植验收。

三 App 选型、五人研究等旧场景不再作为移植前置条件，见 ADR-017。

## License

项目自有源码采用 [MIT License](LICENSE)；打包的第三方项目保留各自许可证，不因本项目采用 MIT 而变更。上游为 DeepSeek 的 deepseek-harness，固定版本及依赖见 `core/package-lock.json`。第三方许可与对应源码的发行配套仍在整理，进度见阶段交付文档。
