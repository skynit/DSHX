# 技术来源与许可证

[说明索引](README.md) · [术语与架构](architecture.md) · [返回项目](../README.md)

DSHX 直接使用 DeepSeek 官方的 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)，由 Android 宿主启动它，复用会话、模型请求、网页与插件机制。项目自己的代码负责 Android 生命周期、系统能力和适配；上游能力保留各自归属。

## 当前依赖与固定位置

| 来源 | 用途 | 仓库中的核对入口 |
|---|---|---|
| DeepSeek dsh `0.2.1-rc.2` | 官方智能体引擎、聊天网页和插件 | [`core/package.json`](../core/package.json)、[`core/package-lock.json`](../core/package-lock.json) |
| Termux Android 软件包 | Node、bash、rg、证书及运行库；不要求用户另装 Termux 应用 | [`core/termux.lock.json`](../core/termux.lock.json)、[`tools/core.py`](../tools/core.py) |
| Miuix `0.8.8`、Lucide 与 Compose | 原生管理页面和图标 | [`app/build.gradle.kts`](../app/build.gradle.kts) |
| sherpa-onnx、SenseVoice、Silero 与可选 MeloTTS | 本地唤醒、语音活动检测、识别与播报 | [`tools/voice.py`](../tools/voice.py)、[语音第三方声明](../app/src/main/assets/voice/NOTICE.txt) |
| llama.cpp 与 Qwen3 0.6B | 本机短文本推理 | [`tools/voice.py`](../tools/voice.py)、[模型清单](../app/src/main/assets/voice/models.json)、[语音与小模型说明](voice.md) |
| MiniSearch `7.2.0` | 本机知识库的关键词检索 | [`core/package.json`](../core/package.json)、[`knowledge-search.mjs`](../core/knowledge-search.mjs) |

上表版本来自仓库锁定配置，未表示它们是外部项目的最新版本。构建脚本固定下载来源和摘要；升级时应同时核对补丁锚点、平台兼容性与许可证。

## 设计参考与致谢

Android 本地运行环境的技术选型参考了 [Soodok / Deepseek-Harness-Local-Android（DSH Mobile）](https://github.com/Soodok/Deepseek-Harness-Local-Android) 的实践。项目保留 [Soodok MIT 许可证原文](../licenses/DSH-Mobile-LICENSE.txt)，不因参考或登记来源就宣称所有代码均独立原创。

语音职责划分参考 hi-mochi，固定参考版本和相关声明见[语音 NOTICE](../app/src/main/assets/voice/NOTICE.txt)。参考项目的功能或设计不自动成为 DSHX 已实现能力。

## 许可范围

项目自有源码采用 [MIT License](../LICENSE)。打包依赖、模型与参考项目的声明继续适用，应用内的[语音许可目录](../app/src/main/assets/voice/licenses/)保留对应原文。构建与下载资源不因自有源码采用 MIT 而改换许可证；第三方材料的完整发行核对仍需单独完成。
