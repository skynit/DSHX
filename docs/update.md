# Android 应用在线更新

[说明索引](README.md) · [构建与验证](development.md) · [返回项目](../README.md)

## 范围与入口

OTA 是通过网络交付更新的方式。本项目仅更新完整 Android 安装包（APK）；引擎运行包随 APK 更新，不提供单独替换引擎的入口。

用户进入“设置 → 应用更新”，检查版本、阅读更新内容、下载并安装。安装包名称为 `DSHX-arm64-${versionName}.apk`，例如 `DSHX-arm64-0.1.0-dev.2.apk`。`versionName` 是开发者在 Gradle 构建配置中设置的可读版本；`versionCode` 是判断版本新旧的递增整数，发布时两者都从实际 APK 读取。

文件名中的 `arm64` 是简写；构建、运行包和设备匹配仍使用 Android 标准架构名称 `arm64-v8a`。第一版只发布 ARM64 手机安装包，模拟器版不能作为 ARM64 安装包发布。

## 组件与调用过程

- **更新清单**：发布脚本生成的 `latest.json`，记录版本、对应的 Git 提交、APK 地址与摘要、外部更新说明地址与摘要，并带发布者数字签名。它负责发现版本，不包含更新说明正文。
- **更新说明**：发布脚本生成的 UTF-8 文件 `release-note.txt`，记录上次发布到本次发布之间的 GitHub 提交；首次发布包含当前提交可达的完整提交历史。它与机器读取的更新清单分别保存。
- **`UpdateStore`**：由 `AppUpdate` 创建的下载与校验组件，验证清单签名、选择设备兼容的安装包、下载文件并核对大小和 SHA-256。SHA-256 是根据文件内容计算的摘要，用来判断内容是否一致；数字签名另外证明摘要和版本信息来自持有发布密钥的人。
- **`AppUpdate`**：由 Android 应用对象 `DshApp` 按需创建的更新控制组件，持有可观察状态，在后台线程协调检查、下载、安装包验签和安装交接；与负责模型和会话的引擎独立。
- **`FileProvider`**：Android 文件交接组件，由系统根据项目清单创建，只把指定 APK 的临时读取权限交给系统安装器；它不自行安装，也不公开应用数据目录。
- **系统安装器**：Android 提供的安装页面，负责用户确认和最终覆盖安装。下载成功、打开安装器、安装完成是不同结果。

客户端先读取并验证清单，再比较 `versionCode`；有新版时下载 `release-note.txt` 并验证摘要。说明加载失败允许重新检查，也不影响经过验证的 APK 下载。安装包下载到 `.part` 临时文件，完整大小和摘要匹配后才改为正式文件；随后验证 APK 的实际签名、包名、版本和最低系统要求。

下载由应用对象持有，离开页面后仍可继续；主动取消会中断网络请求并清理临时文件。已验证下载及其签名清单保存在应用私有目录 `files/updates/`，应用进程重启后可以继续安装。安装前再次完整验证，避免把存在、大小正确直接当作内容可信。

未允许 DSHX 安装应用时，进入系统的来源授权页面；用户允许并返回后继续安装。用户拒绝授权或取消安装时保留安装入口。新版本启动后，以系统读取到的实际安装版本清理已经完成的待安装文件。

## IP 下载入口与信任依据

当前公网下载入口为 `http://47.119.120.17/dshx/latest.json`，不依赖域名。独立 Nginx 容器在服务器 18088 端口提供静态文件，现有 80 端口的 Nginx 通过新增 `/dshx/` 路径转发请求。部署配置在 `tools/ota.compose.yml` 和 `tools/ota.nginx.conf`；原有站点的其他路径保持原处理方式。

HTTP 提供文件传输；清单使用 SHA256withRSA 数字签名，客户端下载自身 APK 的签名证书作为信任依据。发布私钥只在开发机读取，不上传到服务器。更新说明和 APK 的摘要包含在签名覆盖的清单中，APK 自身的签名还必须与当前应用完全一致。客户端拒绝跨服务器、协议降级和 HTTP 跳转；网络请求设置连接、读取超时与文件大小上限。

签名时，先移除 JSON 顶层的 `signature` 字段，将对象键排序，以无额外空格的 UTF-8 JSON 生成签名原文，整数使用十进制；签名结果以 Base64 保存回 `signature`。客户端和发布脚本使用同一规则，当前仅支持 RSA 发布密钥和整数格式的版本字段。

HTTP 不提供传输保密性，也不能阻止网络中断或阻止新版信息被旧的合法清单遮蔽；客户端依据本机已安装版本拒绝安装旧版，已有更高版本待安装时保留该文件。以后使用 HTTPS 时，通过 `-PdshxUpdateUrl=https://实际地址/latest.json` 构建，并让发布脚本使用相同 `--base-url`。已经安装的客户端只认识包内配置的入口，地址迁移需要保留旧入口供旧版升级。

## 构建与发布

本轮沿用开发机现有的 `~/.android/debug.keystore`，保证使用同一开发签名的现有应用能够覆盖升级。该文件需要由发布者妥善备份；其他机器默认生成的开发密钥不一定相同。首次安装含 OTA 功能的版本仍需用户手动安装，此后才有应用内检查入口。

每次发布先增加 `app/build.gradle.kts` 中的 `versionCode`，设置 `versionName`，完成定向验证后提交并推送到 `origin/master`。构建任务将当前提交、工作区是否干净和更新地址写入 APK 的 `assets/build-info.json`。发布脚本拒绝来自未提交工作区、未推送提交、错误架构或其他签名的 APK。

提交并推送后重新构建，确保构建记录属于干净提交：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug

python3 tools/release.py prepare \
  --apk app/build/outputs/apk/debug/app-debug.apk

# 将 3 换成这次 APK 的实际 versionCode。
python3 tools/release.py publish --release build/ota/3
```

脚本要求 Python 3.11+、JDK 17+、Git、Android SDK 的 `aapt`/`apksigner`、SSH 和 SCP。脚本会获取 `origin` 最新状态，验证 APK 内记录的提交已包含在 `origin/master` 中，从服务器读取并验签上次发布清单，再生成差异提交说明。未提交到 GitHub 的当前修改不会被冒充为发布记录。

默认服务器为 `root@47.119.120.17`，默认下载根地址为 `http://47.119.120.17/dshx`。可使用 `prepare --base-url` 和 `publish --host` 指定其他目标。签名配置从 `DSHX_STORE_FILE`、`DSHX_STORE_PASSWORD`、`DSHX_KEY_ALIAS` 环境变量读取；默认对应上述本机开发密钥，密码不写入命令行或产物。

准备后的目录如下，`latest.json` 另外留存于每个版本目录，便于核对历史发布：

```text
build/ota/3/
├── DSHX-arm64-0.1.0-dev.2.apk
├── release-note.txt
└── latest.json
```

服务器部署一次静态下载服务：

```bash
ssh root@47.119.120.17 'mkdir -p /opt/dshx/updates'
scp tools/ota.compose.yml tools/ota.nginx.conf root@47.119.120.17:/opt/dshx/
ssh root@47.119.120.17 \
  'docker compose -p dshx-ota -f /opt/dshx/ota.compose.yml up -d'
```

本机镜像拉取不可用时，可在服务器 `/opt/dshx/.env` 配置 `DSHX_OTA_IMAGE` 使用已有镜像；本次部署复用已有 Nginx 镜像并按摘要固定。公网 18088 未开放，因此现有站点还需加入 `/dshx/` 到下载服务的转发配置。本次配置及修改前备份保存在服务器 `/opt/dshx/nginx.frontend.ota.conf`、`/opt/dshx/nginx.frontend.before-ota.conf`；若重新创建现有站点容器，需要恢复此转发并执行 `nginx -t` 后重新加载。

发布时先上传到隐藏暂存目录，在服务器再次验证文件摘要，然后在发布锁内将整个版本目录改名为 `releases/<versionCode>/`，最后原子替换根目录的 `latest.json`。版本目录不能被不同内容覆盖，版本编号必须递增；重复上传同一份产物可以重试。公网清单和更新说明验证通过后，脚本才报告发布完成。当前公网访问使用已有 TCP 80 端口。

## 定向验证

仅运行本模块 OTA 相关测试和必要构建：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest \
  --tests 'local.dshx.app.CoreTest.update*' :app:assembleDebug
```

测试覆盖清单签名与篡改拒绝、版本和架构匹配、跨服务器拒绝、下载完整性与取消、说明摘要和大小限制、APK 实际签名与版本验证。API 26 与 API 34 的相关用例用于覆盖最低系统和较新系统分支。界面截图、模拟器系统安装确认、ARM64 真机覆盖升级和公网发布验证分别记录，不互相替代。
