# DSHX 微应用运行契约 v1

`web-v1` 是通用网页应用包格式，承载 HTML、CSS、JavaScript 和独立数据。它由 dsh 生成，DSHX 的应用管理对象检查并保存，由 Android WebView（系统提供的网页运行组件）执行页面和脚本。它不是 GKD 订阅，不要求每个应用都是手机自动化。

## 包字段

| 字段 | 内容 |
|---|---|
| `format` | 固定 `web-v1` |
| `id` | 稳定编号，小写字母、数字和连字符，最多64字符 |
| `name`、`description` | 中文显示名、用途说明，分别最多40、200字符 |
| `version` | 三段数字，例如 `1.0.0`，同版内容不可替换 |
| `entry` | 包内 HTML 路径，例如 `index.html` |
| `background` | 可选，包内 JavaScript 路径，例如 `background.js` |
| `files` | 路径到 UTF-8 文本内容的对象。1到64文件，单文件≤256KiB，全包≤1MiB |
| `capabilities` | 使用的宿主能力列表；纯计算页面可以为空 |
| `network` | 可选，允许请求的HTTPS来源，例如 `https://api.example.com`，最多8个，不含路径、端口、用户信息或通配符 |
| `settings` | 配置项列表，可以为空 |

文件支持 html、js、css、json、txt、svg；路径不含 `..`、绝对路径或链接；`__dshx/` 留给宿主。资源相对于当前 HTML 路径加载。不要依赖在线 CDN 或另装 npm 包，所需代码应放入包内。

配置项：`{id,type,label,default}`。支持 `text`（≤2000字符）、`number`（有限数字，绝对值≤10亿）、`switch`（布尔值）、`app-list`（实际应用包名列表）。`app-list` 的包默认值必须为空。屏幕功能必须提供编号为 `apps` 的 `app-list`；普通应用无需此项。配置由宿主设置页编辑，脚本通过 `info()` 获取。

## 生命周期和网页限制

启用应用后，宿主启动可选后台入口；打开应用时创建独立页面；关闭页面销毁页面上下文，后台入口继续存在。停用、移除或更新会销毁该应用上下文，撤销尚未派发的手机调用。进程被系统终止后不能继续执行，重新启动 DSHX 后恢复已启用后台入口；不保证系统定时唤醒或永久常驻。

后台与页面不共享 JavaScript 变量，通过本应用存储交换数据。不要在两个入口同时监听并点击同一界面。停止 dsh 模型引擎不会主动停用应用；应用开关才控制应用运行。

每个应用有独立 HTTPS 虚拟来源，由宿主内存提供已检查资源，不访问真实网站。禁止读取 `file://`、`content://`、其他应用来源和远程顶层页面；禁止嵌入网页框架、弹出窗口、工作线程及网页插件。使用独立存储接口，勿依赖 localStorage、IndexedDB 或Cookie作为持久存储。

标准 DOM、事件、普通计算和定时器可用。`fetch`、图片等外部请求仅允许声明的 HTTPS 来源，仍须满足服务的 CORS（服务端允许网页来源访问的规则）。联网声明在启用前展示给用户，不含用户模型密钥。网页摄像头、定位、文件选择和任意 Android API 未开放。

宿主先注入 SDK，应用等待 `dshx.ready`。每次调用为 Promise（异步结果），拒绝时捕获异常；10秒超时不代表动作没有发生。最多16待处理调用、每秒60调用。失败、超时和未知结果不自动重放点击。

## JavaScript 接口

```js
await dshx.ready;
const app = await dshx.info();
// {id, version, settings, background}
const text = await dshx.storage.get('note'); // 不存在为 null
await dshx.storage.set('note', { text: '内容' });
await dshx.storage.remove('note');
```

`storage` 能力使上述存储接口可用；每应用独立 JSON 数据≤1MiB，键长1到80字符；值为可 JSON 序列化数据。单次接口请求≤80KiB，因此大数据应分键保存。更新或回滚保留数据，删除时按明确选择删除。

屏幕接口需要分别声明 `screen.observe`、`screen.click`；后者必须同时声明前者：

```js
const unsubscribe = dshx.screen.onChange(async event => {
  // event: {package, at}。事件不是点击指令，也不含屏幕正文。
  // 自行用 busy 等状态合并并发事件。
  const observation = await dshx.screen.observe();
  // 根据实际观察进行判断，确定唯一目标后：
  // await dshx.screen.click(observation.snapshot, actualNodeId);
});
// 需要停止监听时：unsubscribe();
```

事件由 Android 无障碍服务产生，宿主合并150ms内的变化；仅投递用户允许应用范围内的事件。读取和点击时再次核对范围、手机总开关、亮屏解锁、服务连接和聊天任务。宿主拒绝不完整观察，节点最多200个、文字最多256字符。两个应用不能覆盖彼此或聊天的有效手机观察。

观察含 `snapshot`、`package`、`nodes`、`truncated`；每个节点包含 `id`、`text`、`description`、真实 `viewId`、`type`、`bounds`、`parent`、`index`、`depth`、`childCount`、`visible`、`enabled`、`clickable`、`actions`、`clickTarget` 等。`id` 是本次观察临时编号，`viewId` 是目标应用提供的控件标识，不能混用。`parent` 是本次树中的父节点编号。无动作文字节点可用非空 `clickTarget` 定位可点击容器，不能随意点击整个广告容器。

点击复用最近观察，同一凭据仅消费一次；执行前复核树、父链、边界及遮挡。每网页上下文至少间隔1秒，应用还应自行设置场景冷却和次数限制。`ok` 只表示系统动作有回执，不等于用户目标完成；应重新观察业务结果。脚本错误和点击回执记入应用最近记录，不保存屏幕正文或异常原文。

## dsh 管理工具

| 工具 | 参数和结果 |
|---|---|
| `app_status` | 可选 `id` 获取完整单个包；不填返回清单摘要；可传原 `operationId` 查询回执 |
| `app_check` | `package` 完整对象；返回五分钟有效 `checkId`、`hash` |
| `app_install` | `checkId`、`hash` 和修改通用参数；安装为停用 |
| `app_enable` | `id`、全部 `settings` 和修改通用参数；屏幕应用范围不能为空 |
| `app_disable` | `id` 和修改通用参数；持久停用 |
| `app_logs` | `id`、可选 `limit`（1到100，默认20） |
| `app_rollback` | `id`、保存过的 `version` 和修改通用参数 |
| `app_remove` | `id`、`deleteData` 和修改通用参数 |

状态中的顶层 `revision` 用于修改请求并发检查；每个应用行的 `revision` 表示该应用自身包、设置或启停的变化，`runtime` 表示后台实际状态与故障。不要把已启用等同于后台已启动。

修改通用参数：`operationId` 是本次稳定编号；`expectedRevision` 是最新状态版本，必须照抄。重复编号同内容返回原结果，内容不一致拒绝。保留最多100条回执，过旧查不到时仍须核实当前状态。

`app_check` 只检查数据结构与入口，不执行 JavaScript；`app_install` 只证明安装，不证明运行。构建、页面检查和实际功能检查需分别报告。现有 Full 模式仍可访问已有文件权限，应用包约定不代表 dsh 全文件系统已有隔离。


## 独立 APK

用户可在应用详情或页面标题栏选择“打包 APK”。APK 是 Android 安装包，由 DSHX 原生入口在本机用精简运行壳、已检查应用定义和系统保存的签名私钥生成，再交给系统文件保存界面。目标手机不需要 DSHX，也不运行 dsh 或模型。

网页 SDK 和包格式不变；导出不会附带已有存储、日志、用户设置或应用范围。纯页面应用安装后直接打开；屏幕应用默认停用，需在独立 APK 的设置中选择范围、开启自身无障碍服务并启用。此时应用启用开关就是手机总开关，没有聊天任务优先队列，其余范围、亮屏解锁、观察与派发检查继续生效。后台脚本通过独立应用的常驻通知维持，可停止，但不保证系统杀进程后的永久运行。

同一 DSHX 安装复用包名和签名，可生成覆盖更新并保留目标手机数据。清除打包端数据、卸载或更换打包设备会失去原签名连续性。智能体负责生成和安装 `web-v1` 定义；APK 打包由原生用户操作发起，不新增网页签名、文件系统或任意原生接口。
