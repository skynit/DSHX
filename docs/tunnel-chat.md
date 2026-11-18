# 穿透聊天设计

状态：本仓库已实现独立穿透平台与 Android 文本聊天基础。默认地址仍为 `https://cwnas.net`，本次未在该地址部署或修改服务。可另行运行 DSHX 平台供测试客户端使用。

## 当前客户端与平台契约

以下接口由 `core/tunnel-platform.mjs` 提供。平台注册证明端点持有其密钥，不等同于 CWNAS 账号登录：

| 调用 | 用途 |
|---|---|
| `POST /api/v1/dshx/chat/endpoints/register/challenge` | 获取 30 秒内有效、仅可使用一次的随机挑战 |
| `PUT /api/v1/dshx/chat/endpoints/register` | 提交端点公钥和公开资料，用 P-256 端点私钥签署 `dshx-platform-register/1\n<挑战>\n<原始请求体>`；返回目录项、信令凭证和网络路径配置 |
| `GET /api/v1/dshx/chat/endpoints` | 按 `query`、`cursor`、`limit` 查询目录，返回 `items` 和可选 `nextCursor` |
| `DELETE /api/v1/dshx/chat/endpoints/{endpointId}` | 使用本端注册凭证注销；设置页未接入注销操作 |
| `GET /api/v1/signaling` | 凭注册返回的 Bearer token（临时访问凭证）升级为 WebSocket，提交路由注册首帧后标记在线 |

信令（用来交换连接参数，不承载正文）借用 CWNAS Signaling V1 的路由注册和协商消息格式，平台只转发匹配会话的协商消息，不兼容 CWNAS 透明 HTTP 或身份认证。DSHX 聊天握手另在 WebRTC 数据通道上完成：两端交换随机挑战，用 Android Keystore（系统密钥存储）的端点私钥签名，绑定双方身份、本次会话和协商内容。对端公钥必须能派生出预期端点 ID。两端均验证后才收发业务消息。

当前 UI 只开放文本、AI 接待与人工接管。记忆、知识库、本机文件和 Phone control 未开放；附件仅校验描述信息。成功建立 TURN 中继（由网络服务器转发加密流量）需要平台提供有效的 ICE 配置和 TURN 凭据；未在两台 Android 设备上验证。重连保留本地待发消息标识、已验证会话关系、人工接管状态、AI 正式回复计数和有限的已接收消息 ID；聊天历史正文仍在内存中，进程重启后不保留。

## 运行自建平台

在已安装 core 依赖的 DSHX 仓库根目录运行：

```bash
node core/tunnel-platform.mjs
```

默认只监听 `127.0.0.1:9082`，可用 `DSHX_TUNNEL_PORT` 改端口。用提供有效证书的 HTTPS 反向代理转发 `/api/v1/dshx/chat/` 和 `/api/v1/signaling`；后一条需允许 WebSocket 升级并转发 `Authorization`。两台 DSHX 客户端在「设置 → 穿透聊天」填写同一 HTTPS 地址，开启穿透聊天并启动引擎。服务不带 TURN 服务器；`DSHX_TUNNEL_ICE_SERVERS` 可设置为 WebRTC ICE（网络路径发现）服务器的 JSON 数组，例如 `[{"urls":["stun:stun.example.org:3478"]}]`。TURN 的 `username`、`credential` 会发给每个注册端点，不应填入不宜公开分发的长期凭据。

当前为单进程平台，端点目录保存在内存，重启后由客户端重新注册。断开的公开端点最多保留 7 天，便于对方选择离线投递；目录读取无需账号，发起连接必须持有注册凭证。限频与并发由接收方客户端执行。部署到公网前仍需加上反代层限流和运维监控；本服务无账号层、持久目录、举报或动态 TURN 凭证发放。

本文定义 DSHX 客户端之间的穿透聊天能力。目标是让一个 DSHX 客户端发布一个可发现的端点，其他 DSHX 客户端可以尝试连接；连接成功后由端点上的 AI 自动接待，端点用户可以随时人工接管。术语沿用根目录 [`GLOSSARY.md`](../GLOSSARY.md)。

## 目标与边界

第一版支持：

- 公共端点目录；
- 隐身、暂停接收、在线和离线状态；
- 端点用户设置公开的 dsh 身份介绍；
- 文本协议，以及图片和受限类型文件的附件清单校验；
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
- DataChannel 建立后的端点身份握手。

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
  -> WebRTC DataChannel
  -> 双方端点身份握手
  -> dshx-chat/1 能力协商
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

协议允许：

- 图片：`jpg/png/webp`，单个最多 10 MB；
- 文档：`pdf/txt/md/docx`，单个最多 20 MB；
- 单个会话附件总量最多 100 MB。

拒绝脚本、可执行文件、未知二进制和压缩包。当前 Android 基础实现已完成附件类型、大小、摘要和消息清单校验；DataChannel 分块传输、接收端落盘和系统文件选择器仍未接入，不能把当前版本描述为已完成图片/文件正文传输。平台不保存附件。附件默认只属于聊天，不自动导入知识库；端点本地默认保存 7 天，除非端点用户主动保留。

## 离线和重试

平台不提供离线消息邮箱。端点离线时，发送方在本地按 `endpoint_id + conversation_id` 保存待发送消息，端点重新在线后恢复原会话并重试。

默认本地队列策略：

- 保存 7 天；
- 最多 100 条消息；
- 附件总量最多 100 MB；
- 加密保存在发送方本地；
- 指数退避重试；
- 会话关闭、消息过期或超过容量后停止重试。

重试必须使用原消息 ID和会话 ID，不能通过反复创建新聊天绕过 5 秒限频。

## 隐私、屏蔽和删除

目录只展示公开资料，不列出 IP 地址或设备指纹。建立点对点连接时，网络探测与通信可能向对端暴露 IP 地址；隐身不是网络匿名功能。

规划支持双方屏蔽，以及用户主动举报；本次未实现。未来的屏蔽应立即阻止新连接请求，举报只能在用户明确选择后才上传正文或附件。

聊天正文和附件只保存在双方客户端本地。平台只保留端点目录、在线状态、信令临时数据和必要路由信息。建议默认本地聊天记录保留 90 天，附件保留 7 天；用户可以手动延长或立即删除。删除一方本地副本不代表删除对方已经保存的副本。

注销账号、关闭端点或更换设备时：

1. 端点立即从公共目录移除；
2. 现有聊天连接失效；
3. 旧端点密钥失效；
4. 新设备生成新的端点身份；
5. 不通过普通备份自动复制旧端点密钥。

## 协议兼容与实现顺序

当前基础握手使用 `hello` 和 `proof` 完成端点签名验证，再接受 `dshx-chat/1` 文本业务帧。它不是 CWNAS 的 Endpoint Identity V1，也不与 CWNAS 透明 HTTP 通道互通。附件分块和远端工具协商仍属于后续实现。

建议按以下顺序实施：

1. DSHX 端点身份、发布状态和本地设置；
2. 穿透平台端点目录与注册接口；
3. WebRTC 信令、端点身份握手和 `dshx-chat/1` DataChannel；
4. 文本聊天、消息确认、去重和本地离线队列；
5. AI 自动回复、轮次上限和人工接管；
6. 本地能力开关与 Phone control 接入；
7. 图片、文件分块和附件保留；
8. 黑名单、举报、注销、密钥轮换和兼容性处理。

每一步都需要区分：平台注册成功、穿透连接成功、聊天消息投递成功、AI 回复成功和本地工具执行成功；其中任何一个成功都不能替代其他结果。

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
