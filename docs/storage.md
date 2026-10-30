# 数据目录与备份范围

[说明索引](README.md) · [术语与架构](architecture.md) · [返回项目](../README.md)

本文所称 **Android 宿主**是 `app/` 中由系统创建的 Kotlin 应用，负责页面与系统交接；**dsh** 是宿主启动的官方智能体引擎，负责会话、模型请求和工具调度；**Node** 是执行 dsh 与适配模块的 JavaScript 运行程序。其他通用概念见[术语表](architecture.md#术语与整体架构)。


下面的 `files/` 是 Android 分配给本应用的私有文件目录，`cache/` 是应用缓存目录；由系统确定实际绝对路径，不使用开发机路径作为手机配置。

```text
files/
├── core/<版本>-<运行包摘要前16位>/    # 可替换运行程序与依赖
├── android.patch.yml              # 本次运行使用的固定适配配置
├── .trust-pending                 # 实际故障后的检查标记，正常时可不存在
├── home/.dsh/                     # dsh 用户配置、凭据与数据根目录
│   ├── skills/                    # 用户技能指令与辅助资源
│   └── storages/
│       ├── sessions/              # 官方引擎持久化会话日志
│       ├── attachments/           # 官方引擎管理的附件
│       ├── hosting.json           # 独立托管会话编号，不保存待补跑任务
│       ├── specimen.json          # 培养来源范围、候选方法、复用与目标预算
│       ├── app_creation.json      # 创建微应用请求阶段和会话绑定
│       ├── memory/state.json      # 独立长期记忆正式快照
│       └── knowledge/
│           ├── state.json         # 知识集合、来源、设置、操作与任务
│           ├── sources/           # 按来源与版本保存原文、规范正文和分块
│           ├── indexes/           # 关键词与向量索引批次
│           ├── summaries/         # 生成的集合概览
│           ├── inbox/             # Android 交接的待导入文件
│           ├── staging/           # Node 加工及发布前暂存
│           └── exports/           # 等待系统保存的知识归档
├── assistant/                     # 助手人设与模型、动作、后台托管记录
├── micro-apps/                    # 微应用包、独立配置、启停、storage.json数据和运行记录
├── builtin-skills.json            # 内置技能安装摘要，用于保留用户编辑
├── specimen-adoptions.json        # 原生技能发布意图与采用回执
├── library/files/<编号>/           # 知识库保留的上传原件，不包含在知识归档中
├── workspace/                     # 默认任务目录；.dsh/skills 与 .agents/skills 可存放工作区技能
└── voice/                         # 本地语音与文本模型
    ├── models/                    # 清单下载并校验完成的模型
    └── custom-model/              # 用户导入的小模型及摘要清单
cache/
└── engine/                        # 引擎临时文件
```

记忆和知识的 `state.json` 使用上游 `{unit, global, tables}` 外层格式，业务状态位于 `global`；不能把文档里的某个业务对象直接覆盖为整个文件。Node 是这两类正式业务状态的唯一写入方，Android 的目录迁移和暂存文件交接不改变这一分工。

旧 `home/.dsh/sessions`、`attachments`、`knowledge` 与 `storages/memory.json`、`knowledge.json` 在停机阶段迁入上述位置。旧 `files/assistant/memory.json` 不属于当前记忆迁移来源。

| 内容 | 会话 ZIP | 知识 gzip JSON | 覆盖安装同签名 APK |
|---|---|---|---|
| 会话与已提交附件 | 包含 | 不含 | 保留应用数据时保留 |
| 长期记忆 | 不含 | 不含 | 保留应用数据时保留 |
| 培养快照、目标预算、采用回执及技能文件 | 不含 | 不含 | 保留应用数据时保留 |
| 导入文档、规范正文、分块及集合 | 不含 | 包含 | 保留应用数据时保留 |
| 知识向量与生成概览 | 不含 | 不作为可启用结果恢复，需重建 | 原数据保留，是否可用取决于版本和配置兼容性 |
| 模型密钥、助手人设、模型配置 | 不含 | 不含 | 保留应用数据时保留 |
| 工作区文件、本地模型、运行包 | 不含 | 不含 | 私有文件通常保留，运行包按 APK 版本准备 |

当前长期记忆没有独立导出恢复功能；两种归档不能充当完整应用备份。卸载或清除应用数据会删除本机私有数据，系统自动备份已在应用清单关闭。

## 关联说明

[迁移、会话备份与知识归档](backup.md) · [记忆](memory.md) · [知识库](knowledge.md)

微应用安装包、设置、运行记录和 app_creation 创建回执均不在现有会话备份或知识归档范围中；创建会话本身仍按普通会话保存。内置技能在启动时整体准备到用户技能目录，builtin-skills.json 只记录可安全更新的内置内容摘要，不覆盖用户编辑。
