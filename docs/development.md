# 构建、安装与定向验证

[说明索引](README.md) · [术语与架构](architecture.md) · [返回项目](../README.md)

## 构建前准备

以下命令使用 Linux 开发环境，在仓库根目录执行。首次准备需要联网下载锁定依赖；构建流程不调用聊天模型，也不要求提供模型密钥。

**JDK** 是开发机编译 Java/Kotlin 字节码所需的工具集；**Android SDK** 是开发者安装的 Android 接口、构建与设备工具；**NDK** 是编译 Android C/C++ 程序的工具集；**CMake** 读取仓库编译配置并组织原生库构建。这些是开发机依赖，用户安装 APK 后无需另行安装它们。

1. 安装下方列出的 JDK、Android SDK、NDK、CMake 与命令行工具。
2. 在仓库根目录创建不提交的 `local.properties`，写入实际 SDK 路径，例如 `sdk.dir=/你的/Android/Sdk`；示例路径需要自行替换。
3. 确认 NDK 与 CMake 已安装到该 SDK，必要时通过 `ANDROID_NDK_HOME` 指定 NDK 路径。
4. 准备语音依赖，再构建对应设备架构的运行包，最后构建 Android 安装包。依赖准备会下载语音库与唤醒资源；系统语音识别不需要应用下载模型，可选文本模型由应用后续下载；播报默认在线使用 Edge TTS。

## 开发环境与命令

需要 JDK 17、Android SDK 36、NDK `28.2.13676358`、CMake `3.22.1`、Python 3.11+、Node/npm、curl、dpkg-deb、readelf 和 Node N-API 头文件（构建脚本使用 `/usr/include/node/node_api.h`）。Gradle Wrapper、Android 构建插件及 Kotlin 版本由仓库配置固定；可通过 `ANDROID_NDK_HOME` 指定运行包编译所用 NDK。

在仓库根目录执行：

```bash
# 准备本地语音依赖及本机推理源码。
python3 tools/voice.py prepare

# 为 ARM64 手机生成本轮运行包资源。
python3 tools/core.py build --abi arm64-v8a

# 构建 app 开发调试包，按实际 JDK 安装位置调整 JAVA_HOME。
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

模拟器架构将 `--abi arm64-v8a` 改成 `--abi x86_64`。**ABI** 是程序与目标处理器、系统二进制接口的约定，此处用于区别 ARM64 手机与 x86_64 模拟器；不是只改文件名。每个 APK 只包含所选架构，切换运行资源后必须重新构建对应 APK。主输出为 `app/build/outputs/apk/debug/app-debug.apk`，多架构留档需另存，不能依赖该固定输出路径同时保留两份包。

当前 `minSdk=26`、`targetSdk=28`、`compileSdk=36`，分别表示最低支持系统接口、应用声明采用的目标行为和编译所用接口版本；部分功能另有更高系统要求。应用为手动安装开发包路线，不能由构建成功推断满足应用商店发布要求。

## 安装并完成第一次对话

构建成功后，开发调试安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。选择与手机或模拟器架构一致的包，按设备允许的方式安装。

如果使用 Android SDK 提供的 `adb`（开发机与已授权设备通信的工具），先用 `adb devices` 确认目标。只有一个目标时，在仓库根目录运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

多个目标时，通过 `adb -s <设备序列号>` 选择设备，尖括号部分需替换。覆盖安装要求与设备现有应用签名一致，安装成功也不代表模型服务已配置。

打开 DSHX，在工作台选择“启动 dsh”，随后进入“聊天”配置自己的模型服务地址、模型名称和凭据。发送“你好，请用一句话介绍你能做什么”；看到该会话中的实际模型回答，才表示这次文字对话路径完成。普通对话使用用户配置的服务；云端请求的计费由该服务决定。

服务能启动、聊天网页能加载、模型能回答是三个检查点。网页提示需要更新时，检查 Android System WebView 是否达到项目要求的 126 或更新版本。

## 测试范围

仅运行当前包或改动涉及模块的具名测试，不执行全仓或全量测试。原生测试追加到已有 [`CoreTest.kt`](../app/src/test/java/local/dshx/app/CoreTest.kt)，例如只验证安装复用和当前 Full 行为：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest \
  --tests 'local.dshx.app.CoreTest.normalStartupReusesTheInstallReceiptWithoutScanningTheInventory' \
  --tests 'local.dshx.app.CoreTest.defaultFullBelongsToLiveSessionsAndDoesNotStopWhenARootEnds'
```

Node 适配测试集中在 `core/test.mjs`，依赖已经构建或安装、与当前源码一致的运行目录。`DSHX_CORE` 指向该目录，`DSHX_WORKSPACE` 指向可写测试工作区；需要 Android 原生库的测试必须在对应环境执行，不能直接把开发机 `core/` 当作手机运行目录。使用 `--test-name-pattern` 选择涉及模块的用例，应先核对所选测试需要的运行目录与平台，不能把开发机包内的依赖当作已构建的 Android 环境。

预装命令环境选择 `^Android (runtime|package commands|package runners|Git runtime|terminal persistent|plugin manager|plugin registry)`；覆盖本地 HTTP/HTTPS、JSON、数据库、GNU 命令、归档压缩、Python 标准库与离线虚拟环境、npm/pnpm 安装、npx/pnpx 脚本、SSH 文件传输及现有插件、终端行为。运行测试的进程应继承 `Engine` 配置的兼容库、证书、Python 缓存和 Git 环境。HTTPS 用例通过 `DSHX_TEST_TLS_CERT`、`DSHX_TEST_TLS_KEY` 指定设备上的临时证书和密钥，证书须包含 `127.0.0.1` 的 IP 地址；同时验证信任该证书时连接成功，默认信任库拒绝该测试证书。SSH 用例需要独立的本机临时服务，通过 ADB 反向转发暴露到设备的 `127.0.0.1`，并设置 `DSHX_TEST_SSH_PORT`、`DSHX_TEST_SSH_USER`、`DSHX_TEST_SSH_ROOT`、`DSHX_TEST_SSH_KEY`、`DSHX_TEST_SSH_HOSTS`。这些变量分别指定端口、测试用户、远端临时目录、设备上的临时客户端密钥和已知主机文件；仅使用本轮生成的测试密钥，不读取用户凭据，结束后清理本轮服务、映射和临时文件。

后台托管只选择 `^Android hosting` 适配用例，以及 `CoreTest` 中的状态查询、运行包缺失修复和服务恢复相关用例。测试中的本地模型适配器、虚拟时钟和手机回执用于验证执行流程，不代表真实服务商或实体手机验收。

实验体优化只选 `^Android (hosting|memory local specimen|assistant usage)`，覆盖来源、后台竞争、预算、目标与复用；原生选择 `specimenSkillAdoptionIsDurableIdempotentAndKeepsUserEdits`、`specimenDraftsSurviveRefreshAndInvalidDraftsDoNotPreventStopping` 及实际受影响的技能、桥接、运行包修复方法。新增模块 `specimen.mjs`、`specimen-store.mjs`、`background.mjs` 必须同时包含在构建清单和启动必需文件检查中。模拟器上运行同组测试时使用 APK 实际安装的对应架构运行目录，固定模型与手机回执仍不代表真实服务商或真实手机动作。

构建检查、单元测试、界面检查、设备实际功能和真实模型服务分别报告。涉及界面时，按项目规则使用 `niri-window-screenshot` 按实时窗口编号截取本机目标窗口并打开检查；文档修改本身无需重新构建 APK 或验证手机界面。

## 构建模块如何协作

[`tools/core.py`](../tools/core.py) 是运行包构建入口：执行锁定依赖的 `npm ci --omit=dev --ignore-scripts`，校验固定 Termux 包，提取 Android Node、bash、rg、Git、Git 传输助手与模板、证书和实际依赖库，并调用 [`tools/runtime.py`](../tools/runtime.py) 安装[预装命令环境](runtime.md#预装命令环境)；保留官方网页与许可，移除不适用的桌面原生二进制及 LibreOffice 打包内容；按固定锚点应用 Android 补丁，使用 NDK 编译 `native.node`、`dshx-run` 和 PTY 扩展，再检查原生文件的 16KB 对齐，生成三个资源文件。**NDK** 是开发者安装的 Android 原生编译工具集，负责把 C/C++ 编译成目标架构库；对齐检查不是 16KB 真机功能验收。

构建补丁除注册项目模块，还适配上游文件和搜索、最终工具准入、默认 Full、附件发布、用户手动终端、插件失败恢复、人设、模型用量与 `storages` 路径。补丁要求目标片段唯一匹配；上游版本变更导致锚点不符时构建停止，不能静默忽略。

[`tools/voice.py`](../tools/voice.py) 根据固定版本和摘要准备 sherpa-onnx Android 库、唤醒及发音资源、模型清单和 llama.cpp 源码。可选文本模型按清单由应用按需下载；语音识别使用手机系统服务，在线播报也不下载模型。随后 [`app/build.gradle.kts`](../app/build.gradle.kts) 检查运行资源和语音库摘要，选择单个目标架构，通过 CMake 编译本机推理库，再生成 APK。
