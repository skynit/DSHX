# 任务跟踪：GitHub Issues

本仓库的需求、规格和实施任务统一记录在 `skynit/DSHX` 的 GitHub Issues，
通过 `gh` 命令行工具操作。任务正文和说明使用中文，代码标识符保留原文。

## 仓库定位

每次会话首次写入前，运行：

```bash
gh repo view --json nameWithOwner
```

确认目标为 `skynit/DSHX`。对支持仓库参数的命令显式传入
`--repo skynit/DSHX`，避免在其他工作目录或 fork 中写入错误仓库。

## 常用操作

以下命令中的 `<编号>` 表示 GitHub Issue 编号；执行时替换为实际值。

- 创建任务：
  `gh issue create --repo skynit/DSHX --title "标题" --body-file <正文文件>`
- 读取完整任务：
  `gh issue view <编号> --repo skynit/DSHX --json number,title,body,labels,comments`
- 列出未关闭任务：
  `gh issue list --repo skynit/DSHX --state open --json number,title,body,labels,comments`
  按需增加 `--label` 等筛选条件；完整扫描时使用分页读取。
- 更新正文：
  `gh issue edit <编号> --repo skynit/DSHX --body-file <正文文件>`
- 追加评论：
  `gh issue comment <编号> --repo skynit/DSHX --body-file <评论文件>`
- 添加或移除标签：
  `gh issue edit <编号> --repo skynit/DSHX --add-label "<标签>"`
  或 `--remove-label "<标签>"`。
- 关闭任务：先追加结论评论，再执行
  `gh issue close <编号> --repo skynit/DSHX`。

多行正文和评论先写入临时文件，再使用 `--body-file`，
保留真实换行并避免 shell 解释正文。

技能要求“发布到任务跟踪系统”时，创建 GitHub Issue；
要求“读取相关任务”时，读取对应 Issue 的正文、标签和评论。

## 父子任务与依赖

父子关系用于拆分工作，依赖关系用于表达执行先后；分别维护。

- 创建子任务：在 `gh issue create` 中增加 `--parent <父任务编号>`。
- 关联已有子任务：
  `gh issue edit <父任务编号> --repo skynit/DSHX --add-sub-issue <子任务编号>`。
- 创建被其他任务阻塞的任务：在 `gh issue create` 中增加
  `--blocked-by <前置任务编号列表>`。
- 为已有任务增加前置依赖：
  `gh issue edit <任务编号> --repo skynit/DSHX --add-blocked-by <前置任务编号>`。
- 查询尚未关闭的前置依赖数量：
  `gh api repos/skynit/DSHX/issues/<编号> --jq .issue_dependencies_summary.blocked_by`。

前置任务全部关闭后，依赖才解除。查询返回缺失或异常值时，
读取实际依赖任务确认，不将缺失值视为零。

若平台不支持父子任务，在子任务正文顶部写 `Part of #<父任务编号>`，
并在父任务正文维护任务清单。
若平台不支持原生依赖，在正文顶部写 `Blocked by: #<编号>, #<编号>`，
逐一检查前置任务是否已关闭。

## PR 入口

**PRs as a request surface: no.**

PR（Pull Request，拉取请求）用于提交和审查代码变更。
`triage` 按上述开关决定是否将外部 PR 纳入任务分类。

## Wayfinder 操作

`wayfinder` 用于把尚有不确定性的工作整理成一张探索地图，
再通过研究、原型、追问和实施任务逐步解决。

- 地图：一个带 `wayfinder:map` 标签的 Issue，
  正文记录笔记、已有决策和待确认事项。
- 子任务：关联到地图的子 Issue，按类型使用
  `wayfinder:research`、`wayfinder:prototype`、
  `wayfinder:grilling` 或 `wayfinder:task`。
  使用前补齐所需的 `wayfinder:` 标签。
- 依赖：沿用上述原生依赖关系及降级写法。
- 选择下一项：只检查当前地图的子任务，
  按地图顺序选择未关闭、前置依赖已解除且尚未指派负责人的第一项。
- 认领：开始处理前执行
  `gh issue edit <编号> --repo skynit/DSHX --add-assignee @me`。
- 解决：追加答案评论、关闭子任务，再将结论摘要和链接写回地图。

地图和探索子任务只使用 `wayfinder:` 标签。
实施任务的分类标签按 `triage-labels.md` 使用。
