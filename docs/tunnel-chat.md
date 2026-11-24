# 穿透聊天设计

状态：本仓库实现了独立穿透平台与 Android 文本、在线图片和文件附件聊天。Android 默认使用 `https://dshx-tunnel.filesharing.asia:8443`。该地址是客户端默认配置，平台构建不会发布或修改公网服务。旧版已保存的平台地址不会自动覆盖；如仍使用旧默认地址，可在「设置 → 穿透聊天」中改为新地址。

## 当前客户端与平台契约

以下接口由 `core/tunnel-platform.mjs` 提供。平台注册证明端点持有其密钥，不等同于 CWNAS 账号登录：

| 调用 | 用途 |
|---|---|
| `POST /api/v1/dshx/chat/endpoints/register/challenge` | 获取 30 秒内有效、仅可使用一次的随机挑战 |
| `PUT /api/v1/dshx/chat/endpoints/register` | 提交端点公钥和公开资料，用 P-256 端点私钥签署 `dshx-platform-register/1\n<挑战>\n<原始请求体>`；返回目录项、信令凭证和网络路径配置 |
| `GET /api/v1/dshx/chat/endpoints` | 按 `query`、`cursor`、`limit` 查询目录，返回 `items` 和可选 `nextCursor` |
| `DELETE /api/v1/dshx/chat/endpoints/{endpointId}` | 使用本端注册凭证注销；设置页未接入注销操作 |
| `GET /api/v1/signaling` | 凭注册返回的 Bearer token（临时访问凭证）升级为 WebSocket，提交路由注册首帧后标记在线 |

信令（用来交换连接参数，不承载正文）借用 CWNAS Signaling V1 的路由注册和协商消息格式，平台只转发匹配会话的协商消息，不兼容 CWNAS 透明 HTTP 或身份认证。两端分别配置编号为 `0`、名为 `dshx-h3/1` 的同一条无序、零重传 WebRTC 数据通道。每条二进制通道消息承载一个最多 1200 字节的 QUIC v1 数据报；客户端运行标准 HTTP/3，不另开网络端口。发起连接的手机是 HTTP 客户端，接收连接的手机是 HTTP 服务端，穿透平台不参与 HTTP/3 会话。

HTTP/3 建连后，双方用 Android Keystore（系统密钥存储）的端点私钥签发 `Dshx-Endpoint-Token`，不复用平台注册凭据。token 包含公钥以及签名，从公钥派生的端点 ID 必须匹配预期值。签名绑定双方端点 ID、发起或接收角色、连接 ID、会话 ID 和 TLS 导出值（本次 QUIC 加密握手产生的连接专属值）。HTTP 服务端的临时 TLS 证书不代表端点身份；只有双方验签成功才传业务，重连重新认证，不接受握手前请求。旧版 `dshx-chat/1` DataChannel 无回退；`dshx-chat/1` 只作为 HTTP 消息和本地队列中的 JSON 信封标记保留。

### 信令保活与断联恢复

客户端每 20 秒发送一次 WebSocket Ping 控制帧，平台每 10 秒发送一次；收到 Ping 的一方回复 Pong。到下一轮仍未收到 Pong 的一方关闭信令连接。这只检查客户端到平台的 WSS（加密 WebSocket），不代表聊天双方之间的数据通道可用。

网络故障或临时注册失败后，客户端按约 1 秒起步、最长 60 秒的指数退避重试，即连续失败时逐步延长等待并加入随机偏移。信令协议异常、WSS 证书验证失败或平台注册被替换时，等待 5 分钟再重试；端点本地配置有误或 HTTP 注册被明确拒绝且不可重试时，等待用户调整。每次恢复重新获取平台注册凭证并建立新的 WSS，收到路由注册确认后才显示在线并重置退避。停止引擎、禁用或切换平台会撤销旧重试与信令握手；已经验签且仍可用的聊天数据通道不因单纯信令断联而被关闭。

当前界面开放文本、在线图片和文件附件、AI 接待与人工接管。不开放远端读取本地文件、记忆、知识库或 Phone control。成功建立 TURN 中继（由网络服务器转发加密流量）需要平台提供有效的 ICE 配置和 TURN 凭据。重连保留本地待发文字、已验证会话关系、人工接管状态、AI 正式回复计数和有限的收件记录；本端已投递的附件保存记录，已投递的文字仍只在内存中。

聊天详情在连接完成并验证对端身份后，显示 WebRTC 当前选中的路径（P2P 直连或中继）和双方往返延迟。客户端约每 5 秒读取一次现有连接统计，不发送额外聊天探测消息。延迟是网络探测从本机到对方再返回的时间，不是单程耗时，也不包含 AI 响应时间。没有可用统计时不显示估算值，断开或重连时不沿用旧连接的读数。

## 打包和运行穿透平台

在 DSHX 仓库根目录执行以下命令。构建需要 Python 3、Node.js 24 或更新版本及 npm，无需 Android SDK、APK 或 CWNAS 项目。

```bash
python3 tools/tunnel.py build
```

生成 `build/dshx-tunnel-platform.tar.gz`，包含服务入口和锁定版本的 `ws`（服务端用于接收 WebSocket 信令的库）。将压缩包上传到服务器，在压缩包所在目录执行以下命令。服务器只需要 Node.js 24 或更新版本，不必再用 npm 安装依赖：

```bash
tar -xzf dshx-tunnel-platform.tar.gz
cd dshx-tunnel-platform
node tunnel-platform.mjs
```

默认只监听 `127.0.0.1:9082`，可用 `DSHX_TUNNEL_PORT` 改端口。由公网 HTTPS 反向代理（负责接收域名请求并转交本机平台进程）转发 `/api/v1/dshx/chat/` 和 `/api/v1/signaling`；后一条需允许 WebSocket 升级并转发 `Authorization`。部署到 `filesharing.asia` 时，须在反向代理配置该域名的有效证书；自建平台使用自己的域名和证书。两台 DSHX 客户端使用同一平台地址，开启穿透聊天并启动引擎；使用自建平台时，在「设置 → 穿透聊天」填写其 HTTPS 地址。

平台进程本身不转发正文；独立 coturn 服务提供 TURN 中继，部署步骤见下节。`DSHX_TUNNEL_ICE_SERVERS` 可设置为 WebRTC ICE（网络路径发现）服务器的 JSON 数组，例如 `[{"urls":["stun:stun.example.org:3478"]}]`，继续用于 STUN 或兼容既有配置。不要在该变量中配置长期 TURN 密码，应使用平台签发的临时凭据。

当前为单进程平台，端点目录保存在内存，重启后由客户端重新注册。断开的公开端点最多保留 7 天，便于对方选择离线投递；目录读取无需账号，发起连接必须持有注册凭证。限频与并发由接收方客户端执行。部署到公网前仍需加上反代层限流和运维监控；本服务无账号层、持久目录或举报功能；已支持基于端点签名注册的临时 TURN 凭据发放。

## 独立 TURN 中继

TURN 是客户端直连失败时使用的网络转发服务。平台负责发放临时访问凭据，coturn 负责转发 WebRTC 加密数据，Android 客户端继续优先尝试直连。参考 coturn 官方的 [TURN REST API 和服务器配置说明](https://github.com/coturn/coturn/blob/master/README.turnserver)，采用兼容 WebRTC 的时间戳用户名和 HMAC-SHA1 临时密码，不向客户端发送服务器共享密钥。

平台环境变量：

| 变量 | 含义 |
|---|---|
| `DSHX_TUNNEL_TURN_URLS` | TURN 地址 JSON 数组，例如 `["turn:47.119.120.17:3478?transport=udp","turn:47.119.120.17:3478?transport=tcp"]` |
| `DSHX_TUNNEL_TURN_SECRET_FILE` | 共享密钥文件路径，内容至少 32 字符，与 coturn 的 `static-auth-secret` 相同 |
| `DSHX_TUNNEL_TURN_TTL_SECONDS` | 临时凭据有效期，默认 3600 秒，可设 60–86400 秒 |

地址与密钥文件必须同时配置，否则启动失败；未配置时保留原有 STUN 配置。成功验签注册后，平台在原有 `iceServers` 字段附加 TURN 地址和临时凭据。用户名为 `<过期 Unix 秒数>:<endpointId>`，密码为 `Base64(HMAC-SHA1(共享密钥, 用户名))`。WebSocket 注册回执的 `ice_servers` 也包含当前凭据。在线期间，每 10 秒检查一次，剩余有效期不超过一半时，沿原连接重发 `register_ok`，保留 `connection_id`、路由和原注册消息的 `reply_to`。现有客户端会更新后续连接使用的配置；已建立的数据通道不因续期重建。信令掉线后按既有流程重新注册并领取新凭据。凭据不进入公共端点目录；注销端点不会提前撤销已经签发的 TURN 凭据，其可用于新分配的时间由有效期限制。

服务器部署模板位于 `tools/tunnel/`：

1. 安装系统发行版提供的 `coturn`；若没有其他 coturn 实例，可屏蔽包自带的 `coturn.service`，仅运行独立 `dshx-turn.service`。
2. 创建 `/etc/dshx-turn/`，目录权限设为 `root:turnserver 0750`，用 `openssl rand -hex 32` 生成共享密钥，保存到 `auth-secret`，权限 `root:root 0600`。不要提交真实配置或密钥。
3. 复制 `turnserver.conf.example` 为 `/etc/dshx-turn/turnserver.conf`，替换公网 IP、网卡 IP、域名和 `static-auth-secret`，权限 `root:turnserver 0640`。云服务器网卡为私网地址时，通过 `external-ip=公网地址/网卡地址` 告知客户端正确中继地址；中继 UDP 端口须保持同端口映射。
4. 把 `dshx-turn.service` 安装到 `/etc/systemd/system/`；把 `dshx-tunnel-turn.conf.example` 安装为 `/etc/systemd/system/dshx-tunnel.service.d/turn.conf`。平台服务用 systemd `LoadCredential` 安全读取原始共享密钥，适用于现有 `DynamicUser` 服务。
5. 更新平台程序后执行 `systemctl daemon-reload`、`systemctl enable --now dshx-turn`、`systemctl restart dshx-tunnel`。
6. 在云安全组和主机防火墙放行 `3478/TCP`、`3478/UDP`、`49160–49259/UDP`。`3478` 是客户端入口；后一个范围承载中继数据，缺失时即使凭据正确也不能建立中继聊天。

模板限制每用户 16 个分配、全局 200 个分配、每分配每方向 1 MiB/s 和全局 20 MiB/s；实际容量也受中继端口范围与服务器带宽限制。禁止中继访问内网、回环、组播和云元数据地址，关闭管理端口与 RFC 6062 TCP 对端分配。`no-tcp-relay` 仍允许客户端通过 TCP 连接 TURN，再使用 UDP 中继端口通信。当前模板提供 `turn:` UDP/TCP 入口，未提供 `turns:` TLS 入口；WebRTC 业务数据仍由双方数据通道加密。模板中的地址用于下面的历史测试，不是客户端默认平台地址；新部署时替换为实际中继地址。

验证时必须检查实际选中的 ICE 路径为 `relay`，并在 DataChannel 上双向发送文本；服务 active、取得候选或仅完成信令均不能代替此验证。测试程序通过也不代表两台 Android 客户端、AI 接待和人工接管已通过真机验收。

### 测试平台验收（2026-11-19）

测试使用 `https://dshx-tunnel.filesharing.asia:8443`。服务器 `47.119.120.17` 当时运行独立 `dshx-turn.service`，系统包为 coturn `4.5.2-3.1~ubuntu22.04.1`。公网安全组放行后，测试结果如下：

| 验证范围 | 结果 |
|---|---|
| 平台定向测试 | 本机 Node 26 与服务器 Node 24 环境均为 7/7 通过，涵盖原信令行为、端点独立临时凭据、密钥不泄露、续期及无效配置 |
| 公网 HTTPS/WSS | 签名注册、在线目录、协商信令、隐身拒绝连接和测试端点注销通过 |
| TURN UDP 入口 | 两个独立测试端点强制中继，双向文本通过，实际选中 `relay/relay` |
| TURN TCP 入口 | 只保留 `transport=tcp` 配置，双向文本通过，实际选中 `relay/relay`；两端之间的中继候选仍为 UDP，符合模板配置 |

上述数据通道测试在本机启动两个独立进程，用套接字 `SO_BINDTODEVICE` 固定到 `wlan0`，通过公网 IP `47.119.120.17` 访问 TURN；双方均使用公网中继候选，没有使用 Tailscale 或私网候选直连。临时端点在测试后注销。此结果证明公网平台与中继之间的集成，不代表两个不同接入网络上的 Android 客户端、端点身份握手、AI 接待或人工接管已经通过真机测试。未进行全仓测试或发布 APK。

本文定义 DSHX 客户端之间的穿透聊天能力。目标是让一个 DSHX 客户端发布一个可发现的端点，其他 DSHX 客户端可以尝试连接；连接成功后由端点上的 AI 自动接待，端点用户可以随时人工接管。术语沿用根目录 [`GLOSSARY.md`](../GLOSSARY.md)。

## 目标与边界

第一版支持：

- 公共端点目录；
- 隐身、暂停接收、在线和离线状态；
- 端点用户设置公开的 dsh 身份介绍；
- 文本、在线图片，以及受限类型文件的附件清单校验；
- P2P 优先、TURN 中继回退的穿透连接；
- AI 自动正式回复和人工接管；
- 有限 AI 自动回复轮次；
- 后续按端点用户授权开放记忆、知识库、文件和手机工具；这些授权不进入公开目录；
- 断线恢复、消息确认、去重和发送方本地离线队列。

第一版不做：

- 平台保存聊天正文或附件；
- 平台离线消息邮箱；
- 匿名连接；
- 任意文件同步；
- 默认继承普通本机聊天的记忆、文件和手机权限；
- AI 与人工同时发送正式回复。

## 现有实现约束

### DSHX

DSHX 的本机 `dsh` 会话负责模型请求、对话历史和工具调度；Android 宿主通过本机认证桥与 Node 运行时交接手机能力、会话登记和管理请求。该桥只监听本机回环地址，不能直接作为公网聊天端点。

远端聊天必须创建独立的访客 dsh 会话。当前 Android 基础实现为每个穿透会话使用 `tunnel:<conversation_id>` 会话标识，把外部消息包装为不可信用户输入，并关闭本机工具和长期记忆的自动注入。远端记忆、知识库、文件和 Phone control 的实际工具桥接仍需另行接入。

端点保持在线需要引擎和承载穿透连接的前台服务运行。用户停止引擎、系统强行停止应用、进程尚未恢复或网络不可用时，端点显示为离线；Android 后台保活不能被当前代码承诺为永久在线。

### CWNAS 穿透平台

参考项目 `/home/skynit/workspace/cwnas_fix/` 已有以下可复用边界：

- `/api/v1/signaling` WebSocket 首帧 `cwnas-webrtc-signaling` 注册和注册确认；
- 已验证端点可使用 `endpoint_id`、端点证书和挑战签名；普通 route 注册不触发证书挑战；
- 路由访问校验和会话冲突检查；
- WebRTC Offer、Answer、ICE Candidate 信令；
- P2P 与 TURN candidate path；
- DataChannel 承载的 QUIC v1、HTTP/3 和独立于平台信令的端点认证。

现有 CWNAS 透明 HTTP 功能不是聊天协议。它也没有公共端点目录，因此 DSHX 需要新增端点目录、账号范围外的公开发现能力和 `dshx-chat/1` 业务协议。平台只负责目录、认证、信令和必要的 TURN 数据中继，不保存聊天业务数据。

## 端点状态

端点状态分为三个互不替代的维度：

| 维度 | 状态 | 含义 |
|---|---|---|
| 发布 | `published` | 出现在公共端点目录中 |
| 发布 | `hidden` | 不出现在目录，也不接受新的访客连接 |
| 接收 | `accepting` | 可以按限频、并发和黑名单规则接受新聊天 |
| 接收 | `paused` | 仍可出现在目录，但暂不接受新的聊天 |
| 连接 | `online` | 客户端已注册且有可用穿透信令连接 |
| 连接 | `offline` | 当前没有可用的端点连接 |

新端点初始默认使用 `published + accepting`，正常进入公共目录并接受新聊天。端点用户可以主动切换为 `hidden` 或 `paused`。`hidden` 不泄露端点是否曾经注册；`offline` 可以展示在已发布端点的目录项中。

### 端点配置

```text
endpoint_id                 由公钥派生的稳定端点标识
display_name                公共显示名称
public_introduction         端点用户编辑的 dsh 身份介绍
publication                 published | hidden
acceptance                  accepting | paused
max_concurrent_conversations 默认 8
new_conversation_interval   默认 5 秒，按端点全局计算
max_ai_replies              每个聊天会话的 AI 正式回复上限，默认 10
capabilities                文本、图片、文件及本地能力开关
```

`public_introduction` 只能描述角色、语气、服务范围和回答方式，不能覆盖平台身份、工具权限、人工确认、限频、并发和隐私策略。

## 公共端点目录

目录不按账号隔离。任何客户端都可以浏览公开资料，已登记端点才能尝试连接；能否建立聊天仍由端点状态、认证、限频和并发规则决定。黑名单未实现。

目录项只包含：

```text
endpoint_id
display_name
public_introduction
capabilities: text | image | file
online: true | false
accepting: true | false
last_seen: optional
```

目录不包含平台账号 ID、真实姓名、IP 地址、访问码、私钥、完整证书、文件路径、记忆、知识库、手机工具清单或聊天历史。

目录、平台账号认证和端点身份是三个不同概念：

1. 平台账号认证证明客户端属于平台上的注册主体。
2. 端点密钥或证书挑战证明当前连接确实代表该端点。
3. 聊天会话权限决定这次连接可以读取或执行哪些本地能力。

## 连接建立

```text
端点启动并注册
  -> DSHX 平台端点密钥挑战
  -> 发布端点目录项
  -> 访客读取目录
  -> 访客发起连接请求
  -> 平台检查访客凭证、目标状态和路由资格
  -> WebSocket 信令交换 Offer / Answer / ICE
  -> 无序、零重传的 WebRTC DataChannel
  -> QUIC v1 / HTTP/3
  -> HTTP 请求和响应交换连接绑定的端点 token
  -> 建立或恢复聊天会话
```

公开端点在通过上述检查后自动接受新聊天，不要求端点用户逐条点击同意。端点用户通过隐身、暂停、黑名单、并发上限和 AI 配置控制风险。

连接失败必须区分：

| 状态 | 语义 |
|---|---|
| `hidden` | 对外表现为不存在或不可用 |
| `offline` | 端点已发布但当前没有在线连接 |
| `paused` | 端点在线但暂不接受新聊天 |
| `busy` | 触发 5 秒限频或并发上限，并带 `retry-after` |
| `blocked` | 任一方屏蔽对方 |
| `unauthorized` | 平台或端点身份校验失败 |
| `unsupported` | 双方不支持请求的协议能力 |

## 连接和会话的区别

WebRTC DataChannel 是网络传输连接，`conversation_id` 是聊天会话标识。一个聊天会话可以跨越多次 DataChannel 建立和重连；DataChannel 断开不代表聊天会话自动关闭。

每个端点每 5 秒最多接受一个新的逻辑聊天会话。这个限制不作用于：

- 已建立会话中的消息；
- 已建立会话的 DataChannel 重连；
- 已建立会话的 TURN/P2P 路径切换。

端点最多同时保持 8 个活跃聊天会话。达到上限后返回 `busy`，不在平台排队未知访客。

## 聊天会话状态

```text
connecting
active_ai
human_takeover
waiting_human
closed
expired
```

- `active_ai`：AI 可以生成并发送正式回复；
- `human_takeover`：端点用户已发送正式消息，AI 只能生成内部建议；
- `waiting_human`：达到 AI 回复轮次上限，等待人工接管；
- `closed`：任一方主动结束，旧会话不可恢复；
- `expired`：达到本地保留或会话生命周期限制。

关闭后再次联系必须创建新的 `conversation_id`。短暂断线恢复原会话，不重新消耗新聊天限频。

## 消息协议

业务协议版本为 `dshx-chat/1`。每个消息使用可验证、可重试和可去重的信封：

```json
{
  "protocol": "dshx-chat/1",
  "type": "message",
  "messageId": "sender-generated-id",
  "conversationId": "conversation-id",
  "seq": 12,
  "sender": {
    "role": "visitor",
    "endpointId": "visitor-endpoint-id"
  },
  "kind": "text",
  "createdAt": 1794960000000,
  "replyTo": null,
  "payload": {
    "text": "消息正文"
  }
}
```

业务消息字段使用 `messageId`、`conversationId`、`sender`、`kind`、`seq`、`createdAt` 和 `payload`，其中 `createdAt` 为 Unix 毫秒。`message_id` 和 `session_id` 是信令字段，不用于业务消息。发送方生成唯一消息 ID；接收方在当前进程内按端点、会话及消息 ID 去重。

消息类型包括：

- `text`、`image`、`file`；
- `ai_formal`：AI 对访客可见的正式回复；
- `human_formal`：端点用户对访客可见的正式回复；
- `ai_suggestion`：只对端点用户可见的内部建议；
- `tool_request`、`tool_confirmation`、`tool_result`；
- `conversation_event`：接管、恢复、关闭、达到轮次上限等系统事件。

投递状态为：

```text
queued -> sending -> sent -> received -> seen
                         \-> failed
queued --------------------> expired
```

## AI 和人工协作

每个访客聊天创建独立的 dsh 会话，不复用端点用户的普通本机聊天会话。AI 默认处于自动接待状态，每条访客消息最多触发一条 AI 正式回复。`max_ai_replies` 按聊天会话计算；人工正式消息不消耗 AI 轮次。

达到 AI 回复上限后：

1. AI 发送一次说明消息；
2. 会话进入 `waiting_human`；
3. AI 不再发送正式回复；
4. 端点用户可以人工接管，或结束会话。

端点用户发送正式消息后进入 `human_takeover`。AI 仍可生成 `ai_suggestion`，但建议只在端点用户一侧显示，不能自动发送给访客。端点用户点击“恢复 AI”后，AI 才重新获得正式回复权限。

## 本地能力和外部内容

端点用户按能力独立开放：

- 读取长期记忆；
- 读取知识库；
- 读取选定文件或工作区；
- 写入长期记忆；
- 修改或创建文件；
- 读取手机状态和界面；
- 执行手机操作。

默认关闭远端能力。端点用户开启后，远端会话仍使用有限的上下文和读取预算。访客消息、图片、文件和检索到的文档都属于不可信外部内容，不能改变系统策略、工具权限、平台地址或端点设置。

远端能力以端点用户设置中的独立开关控制；`phone control` 是一个整体开关，不再在穿透聊天协议中拆分复杂的工具权限层级。关闭时不向远端会话开放对应能力，开启后复用 DSHX 现有手机控制流程。访客只看到经过裁剪的正式结果，不看到原始提示、内部推理、密钥、绝对路径、屏幕树隐私字段或错误堆栈。

## 图片和文件

建立已验证的聊天连接后，双方都可通过系统选择器主动选文件并确认发送。图片可在会话里预览；普通附件仅显示名称，由用户点「保存文件」选择导出位置，不自动打开。端点用户发送附件会进入人工接管。附件不触发 AI 接待、不导入知识库，也不授予对方访问本机目录或文件的权限。

- 支持 JPG、PNG、WebP，单张最多 10 MiB（10 × 1024 × 1024 字节），最多 6400 万像素。发送所选的原始图片，因此其中已有的相机或定位信息也可能发给对方。
- 普通文件支持 PDF、TXT、Markdown 和 DOCX，每个最多 20 MiB。文件选择器和传输描述限制声明的文件类型；普通附件始终按不透明字节处理，不运行内容，也不用对端给的名称拼接应用内路径。
- 直连和 TURN 共用一套传输。附件原始字节走独立的 HTTP/3 请求流，不与文字交错写在同一个流上。接收方检查大小和 SHA-256（确认字节没有变化的摘要），图片另验实际格式，保存后才确认收件。
- 附件不进入文字离线队列。断线或超时会结束传输；在原会话中重试同一附件时沿用消息编号。接收方已保存匹配附件时只再次确认，不重复显示。聊天页在前台收发附件时暂时保持屏幕亮，不承诺手动锁屏或退到后台后系统不会暂停连接。
- 附件每个会话最多占用 100 MiB，所有会话合计最多 256 MiB、2048 份。在应用私有目录保留 7 天，不进入系统备份或普通会话备份，平台不保存附件。

## 离线和重试

平台不提供离线消息邮箱。端点离线时，发送方在本地按 `endpoint_id + conversation_id` 保存待发送文字，端点重新在线后恢复原会话并重试。

默认本地队列策略：

- 保存 7 天；
- 最多 100 条消息；
- 待发内容总量最多 100 MiB；
- 加密保存在发送方本地；
- 指数退避重试；
- 会话关闭、消息过期或超过容量后停止重试。

离线队列重试必须使用原消息 ID 和会话 ID，不能通过反复创建新聊天绕过 5 秒限频。

## 隐私、屏蔽和删除

目录只展示公开资料，不列出 IP 地址或设备指纹。建立点对点连接时，网络探测与通信可能向对端暴露 IP 地址；隐身不是网络匿名功能。

规划支持双方屏蔽，以及用户主动举报；本次未实现。未来的屏蔽应立即阻止新连接请求，举报只能在用户明确选择后才上传正文或附件。

聊天正文和附件只保存在双方客户端本地。平台只保留端点目录、在线状态、信令临时数据和必要路由信息。删除一方本地副本不代表删除对方已经保存的副本。

注销账号、关闭端点或更换设备时：

1. 端点立即从公共目录移除；
2. 现有聊天连接失效；
3. 旧端点密钥失效；
4. 新设备生成新的端点身份；
5. 不通过普通备份自动复制旧端点密钥。

## 客户端 HTTP/3 接口

| 请求 | 用途 |
|---|---|
| `POST /authenticate` | 用请求头和响应头交换端点 token，无请求正文 |
| `GET /events` | 长开的响应，每行一个文字、收件确认、会话事件或附件通知。发起端验过对方 token 后才订阅 |
| `POST /messages` | 发起端发送文字、收件确认或会话事件；HTTP 成功不替代业务收件确认 |
| `POST /attachments/{id}` | 发起端上传附件原始字节；附件描述放在 `Dshx-Attachment-Message` 头，接收端保存后返回 `204` |
| `GET /attachments/{id}?transfer=…` | 发起端收到附件通知后按本次传输标识下载原始字节，每份附件单开请求 |
| `POST /attachments/{id}/receipt?transfer=…&saved=1` | 发起端保存下载附件后确认本次传输送达；`saved=0` 表示接收失败 |

文字和会话事件仍使用原消息 ID 和收件确认执行离线重试。附件读写有独立的超时和并发限制，单个附件失败不终止聊天连接。文字与附件流共享同一底层链路，不承诺峰值吞吐提升或跨流按发送时间到达。

## 已确认默认值

以下默认值已确认：

- 新端点首次注册时默认为 `published + accepting`，正常进入公共目录；
- `max_ai_replies` 默认 10 条 AI 正式回复，端点用户可以调整；
- 远端记忆、知识库、文件和手机能力默认全部关闭，端点用户逐项开启；
- `phone control` 是一个独立的本地能力开关，开启后按现有手机控制流程执行，不另行拆分复杂权限层级。

## 参考实现位置

CWNAS 平台注册与路由：

- `cw/pkg/net/webrtc/signaling/routes/routes.go`
- `cw/pkg/net/webrtc/signaling/server.go`
- `cw/pkg/net/webrtc/signaling/registration.go`
- `cw/pkg/net/http/tunnel/routes/routes.go`
- `cw/pkg/net/tunnel/node.go`
- `cw/pkg/net/identity/handshake.go`

DSHX 运行和权限边界：

- `app/src/main/java/local/dshx/app/EngineService.kt`
- `app/src/main/java/local/dshx/app/Engine.kt`
- `app/src/main/java/local/dshx/app/PolicyBridge.kt`
- `app/src/main/java/local/dshx/app/ToolPolicy.kt`
- `core/assistant.mjs`
- `core/policy.mjs`
- `docs/bridge.md`
- `docs/read-budget.md`
