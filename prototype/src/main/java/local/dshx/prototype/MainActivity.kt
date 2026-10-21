package local.dshx.prototype

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** G0 的原生可操作原型。所有外部动作均为演练，不连接模型或手机执行接口。 */
class MainActivity : Activity() {
    private val state get() = application as PrototypeApp
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var root: LinearLayout
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val background get() = Color.parseColor(if (dark) "#151D1B" else "#F5F6F3")
    private val surface get() = Color.parseColor(if (dark) "#22302B" else "#FFFFFF")
    private val controlSurface get() = Color.parseColor(if (dark) "#2E4238" else "#E6ECE7")
    private val ink get() = Color.parseColor(if (dark) "#F0F5EF" else "#182C25")
    private val muted get() = Color.parseColor(if (dark) "#C0CEC7" else "#4B6258")
    private val accent get() = Color.parseColor(if (dark) "#AEDDC7" else "#245F51")
    private val onAccent get() = Color.parseColor(if (dark) "#11281E" else "#FFFFFF")
    private val warning get() = Color.parseColor(if (dark) "#FFD8A3" else "#7A4400")
    private val timer = object : Runnable {
        override fun run() {
            if (state.flow.stage == TaskStage.APPROVAL) {
                if (expireApproval()) render()
                findViewById<TextView>(COUNTDOWN)?.text = remainingText()
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onStart() {
        super.onStart()
        state.listener = { if (!isFinishing && !isDestroyed) render() }
        expireApproval()
        render()
        main.post(timer)
    }

    override fun onStop() {
        state.listener = null
        main.removeCallbacks(timer)
        super.onStop()
    }

    private fun render() {
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        setContentView(root)
        val masthead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        masthead.addView(label("DSHX", 22, true), LinearLayout.LayoutParams(0, -2, 1f))
        masthead.addView(label("P0 · 产品走查", 13).apply { setTextColor(muted) })
        root.addView(masthead)
        root.addView(label("本地文件实际处理 · 模型与手机操作为演练", 12).apply { setTextColor(warning); setPadding(0, dp(8), 0, dp(10)) })
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(20)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (state.busy) notice("正在处理本地文件… 请稍候，原文件保持不变。")
        state.message?.let { notice(it) }
        when (state.page) {
            "home" -> home()
            "setup" -> setup()
            "file" -> filePage()
            "task" -> taskPage()
            "advanced" -> advanced()
            "study" -> study()
            else -> home()
        }
        if (state.flow.stage !in listOf(TaskStage.READY, TaskStage.STOPPED, TaskStage.COMPLETE)) {
            val taskBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            taskBar.addView(Button(this).apply {
                text = getString(R.string.active_task, stageName())
                textSize = 14f
                minHeight = dp(48)
                backgroundTintList = android.content.res.ColorStateList.valueOf(controlSurface)
                setTextColor(accent)
                setOnClickListener { go("task") }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (state.flow.stage in listOf(TaskStage.RUNNING, TaskStage.APPROVAL, TaskStage.DISPATCHED)) {
                taskBar.addView(Button(this).apply {
                    text = "停止任务演练"
                    textSize = 14f
                    minHeight = dp(48)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(controlSurface)
                    setTextColor(warning)
                    setOnClickListener { state.flow.stop(); render() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            root.addView(taskBar)
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("工作台" to "home", "手机任务" to "task", "设置" to "setup").forEach { (title, page) ->
            nav.addView(Button(this).apply {
                text = title
                textSize = 14f
                minHeight = dp(48)
                isAllCaps = false
                backgroundTintList = android.content.res.ColorStateList.valueOf(controlSurface)
                setTextColor(if (state.page == page) accent else muted)
                setOnClickListener { go(page) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
    }

    private fun home() {
        eyebrow("先得到一个能交付的结果")
        title("把零散资料\n整理成下一步。")
        paragraph("导入一个副本，检查整理前后的内容，再保存到你选择的位置。")
        card {
            heading("01  整理文件")
            paragraph("从内置周会样本提取未完成事项。演示整理使用固定本地规则，不调用模型。")
            action("用周会样本开始", primary = true, enabled = !state.busy) { importSample() }
            action("从手机选择文本副本", enabled = !state.busy) { importFile() }
        }
        if (state.files.isNotEmpty()) {
            heading("工作文件 · ${state.files.size}")
            state.files.forEach { file ->
                action("打开 ${file.name}", enabled = !state.busy) { state.select(file) }
            }
        }
        card {
            heading("02  手机任务")
            paragraph("走查一次提交前确认、停止和结果不明的处理。尚未连接任何目标应用。")
            action("进入操作演练") { go("task") }
        }
        card {
            heading("03  审核插件")
            paragraph("尚未选定通过审核的固定版本插件，当前不可运行。需要真实插件、完整依赖与报告样本，才会显示为可用。")
            action("插件检查尚未准备", enabled = false) { }
        }
        action("查看首次使用流程") { go("setup") }
        action("走查说明与记录") { go("study") }
    }

    private fun setup() {
        eyebrow("首次使用")
        title("清楚知道，\n任务将在哪里运行。")
        val webPackage = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val version = webPackage?.versionName ?: "未检测到"
        val webSupported = (version.substringBefore('.').toIntOrNull() ?: 0) >= 126
        card {
            heading("1  设备条件")
            paragraph("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n处理器：${Build.SUPPORTED_ABIS.joinToString()}\nWebView：$version\n聊天网页要求：Chromium 126 或以上。")
            notice(if (webSupported) "网页组件版本满足设计门槛；尚未验证 dsh 与完整运行时。" else "当前网页组件不满足聊天要求。请更新系统 WebView；文件原型仍可走查。")
        }
        card {
            heading("2  本地运行组件")
            paragraph("原型未打包 Node、bash、rg 和终端组件，没有真实安装进度。正式版将显示实际体积、空间需求与校验结果。")
            action("本地引擎尚未准备", enabled = false) { }
        }
        card {
            heading("3  模型接收方演练")
            paragraph("正式版的任务数据会发送给你选定的模型服务。原型没有联网权限，下面仅保存本次走查中的示例地址，不接收密钥。")
            val field = EditText(this@MainActivity).apply {
                id = RECEIVER
                setText(state.receiver)
                hint = "https://你的模型服务地址"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(ink)
                setHintTextColor(muted)
                minHeight = dp(56)
                setSingleLine(true)
            }
            body.addView(label("模型服务地址（演练）", 14, true))
            body.addView(field)
            action("保存示例接收方", enabled = !state.busy) {
                val address = field.text.toString().trim()
                val uri = Uri.parse(address)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
                    field.error = "请填写不带密钥、参数或片段的 HTTPS 服务地址"
                } else {
                    state.receiver = address
                    state.allowed = false
                    state.message = "示例接收方已更新，原有数据发送授权已清除。未发起网络请求。"
                    render()
                }
            }
        }
        action("4  用无敏感样本体验文件交付", primary = true, enabled = !state.busy) { importSample() }
        action("进阶与恢复演练") { go("advanced") }
    }

    private fun filePage() {
        val file = state.selected ?: return go("home")
        eyebrow("文件副本 · 原文件未改动")
        title(file.name)
        paragraph("${state.text.toByteArray(Charsets.UTF_8).size} 字节 · ${if (state.changed) "有本次整理结果" else "尚未整理"}")
        card {
            heading("本次范围")
            paragraph("仅读取这个副本：${file.name}\n示例模型接收方：${state.receiver}")
            val check = CheckBox(this@MainActivity).apply {
                text = "我理解正式版将把选定内容发给此服务（原型仅演练，不发送）"
                textSize = 15f
                setTextColor(ink)
                minHeight = dp(56)
                isChecked = state.allowed
                setOnCheckedChangeListener { _, value -> state.allowed = value }
            }
            body.addView(check)
            paragraph("读取、发给模型、执行修改分别授权。切换文件或模型接收方后需要重新确认。历史内容不能因此自动发往新服务。")
        }
        if (state.changed) {
            heading("整理前")
            code(state.before)
            heading("整理后 · 可交付内容")
            code(state.text)
            action("撤销这次整理", enabled = !state.busy) { undo(file) }
        } else {
            heading("副本内容")
            code(state.text)
            action("按本地规则提取未完成事项", primary = true, enabled = !state.busy && file.name.endsWith(".md", true)) {
                if (!state.allowed) {
                    state.message = "先确认上方的数据范围和接收方演练，再继续。原型不会发送数据。"
                    render()
                } else {
                    state.runWork({ state.workspace.extract(file) }) {
                        state.text = it
                        state.changed = true
                        state.message = "整理结果已保存到本地副本。请核对原文行号，再导出。模型调用：未发生。"
                    }
                }
            }
        }
        card {
            heading("交付到你选择的位置")
            paragraph("使用系统“另存为”创建新文件，不覆盖原文件。若外部写入结果不明，请先到目标位置核实，本地副本始终保留。")
            action("另存为新文件", primary = state.changed, enabled = !state.busy) { export(file) }
        }
        action("返回工作台") { go("home") }
    }

    private fun taskPage() {
        eyebrow("手机操作演练 · 不会操作其他 App")
        title(stageName())
        paragraph("演练目标：向示例协作应用提交一条进度。此目标应用为虚构占位，没有包名和已验证版本，不计入三项真实手机任务。")
        card {
            heading("这项任务的边界")
            paragraph("仅本次任务 · 选定页面文字 · 20 次模型请求 / 10 分钟（待验证默认值）\n原型实际模型调用：未发生；费用：不适用。")
        }
        when (state.flow.stage) {
            TaskStage.READY -> action("开始任务演练", primary = true) { state.flow.start(); render() }
            TaskStage.RUNNING -> {
                notice("正在演练观察页面。真实任务需要设备权限、审核工具和已验证的目标应用。")
                action("演练：准备提交，进入确认", primary = true) {
                    state.flow.ask()
                    state.approvalDeadline = SystemClock.elapsedRealtime() + 30_000
                    render()
                }
            }
            TaskStage.APPROVAL -> {
                card {
                    heading("确认这一次提交")
                    paragraph("目标：示例协作应用 / 项目进度页\n动作：提交文本\n内容：导出流程已核对，等待设备测试。\n后果：目标应用中的其他成员可能立即看到，停止无法撤回。")
                    val countdown = label(remainingText(), 14, true).apply { id = COUNTDOWN; setTextColor(warning) }
                    body.addView(countdown)
                    action("仅允许这一次（演练）", primary = true) {
                        if (expireApproval()) { render(); return@action }
                        state.flow.approve(); render()
                    }
                    action("拒绝并结束") { state.flow.deny(); render() }
                }
            }
            TaskStage.DISPATCHED -> {
                notice("演练：动作已派发。现在停止会关闭后续操作，已经发出的动作需要核实。")
                action("演练：收到可核实的完成结果", primary = true) { state.flow.complete(); render() }
                action("演练：连接中断，结果不明") { state.flow.loseResult(); render() }
            }
            TaskStage.STOPPING -> {
                notice("停止中：不再接纳后续操作。演练中的已派发动作尚未取得结果，不能显示为已撤销。")
                action("演练：等待后仍无回执", primary = true) { state.flow.loseResult(); render() }
            }
            TaskStage.UNKNOWN -> {
                notice("结果不明：这次提交可能已经发生。不会自动重试，请先到目标应用核实，避免重复提交。")
                paragraph("人工核实只补充记录，原动作仍保留“结果不明”。原型没有真实目标应用，以下均为核实演练。")
                state.flow.verification?.let { heading("人工核实记录：$it（演练）") }
                listOf("已发生", "未发现结果", "暂时无法确认").forEach { note ->
                    action("记录：$note") { state.flow.verify(note); render() }
                }
            }
            TaskStage.COMPLETE -> notice("演练已完成：模拟收到了可核实结果。这不构成真实应用任务成功证据。")
            TaskStage.STOPPED -> notice("演练已停止，未派发的动作已取消。真实任务仍须核实已派发操作。")
        }
        if (state.flow.stage in listOf(TaskStage.STOPPED, TaskStage.COMPLETE, TaskStage.UNKNOWN)) {
            action("另开一轮演练") {
                AlertDialog.Builder(this).setTitle("另开演练，不重发旧动作")
                    .setMessage("本轮核实记录只在原型进程内保留。正式版必须保留原动作账本。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("开始新的演练") { _, _ -> state.flow = TaskFlow(); render() }.show()
            }
        }
        action("离开此页，查看任务入口") { go("home") }
    }

    private fun advanced() {
        eyebrow("设置 / 进阶 · 仅演练")
        title("恢复环境，\n保留你的成果。")
        paragraph("Full 是用户为当前会话开启的完全信任模式，允许模型任意执行代码。它不增加系统权限；同一应用身份不能隔离恶意代码。这里不会启用真实 Full。")
        when (state.recovery) {
            "default" -> {
                notice("默认模式演练。首次文件任务不需要 Full。")
                action("演练进入完全信任", primary = true) {
                    AlertDialog.Builder(this).setTitle("完全信任前先了解恢复成本")
                        .setMessage("正式版仅当前会话/实例有效；退出后要停止任务、撤销授权并重建受信环境。文件、会话、附件、模型配置保留。原型仅切换演练画面。")
                        .setNegativeButton("保持默认", null)
                        .setPositiveButton("开始演练") { _, _ ->
                            state.flow.stop()
                            if (state.flow.stage == TaskStage.STOPPING) state.flow.loseResult()
                            state.recovery = "full"
                            render()
                        }.show()
                }
            }
            "full" -> {
                notice("完全信任状态演练。退出开关不等于环境已恢复可信。")
                action("退出并查看恢复范围", primary = true) { state.recovery = "required"; render() }
            }
            else -> {
                card {
                    heading("只重建运行环境")
                    paragraph("重建：受信引擎、审核插件与受控配置。\n保留：工作文件、会话、附件、模型配置。\n不自动执行保留脚本，不恢复任意开发者组合。")
                    notice(if (state.recovery == "failed") "恢复失败演练：正常工具保持不可用。请先导出成果，再重试或查看诊断。" else "需要恢复演练：正式版完成清理验证后才开放默认工具。")
                    action("演练恢复成功", primary = true) { state.recovery = "default"; render() }
                    action("演练恢复失败") { state.recovery = "failed"; render() }
                }
                action("导出已保留的工作文件") { go("home") }
                action("查看设备与未验证项目") { go("setup") }
            }
        }
        action("返回设置") { go("setup") }
    }

    private fun study() {
        eyebrow("产品走查说明")
        title("让真实使用\n决定下一步。")
        paragraph("这份原型用于检查能否找到结果、停止和核实入口，以及是否理解数据发送与停止的边界。没有用户参与记录，不代表 G0 已通过。")
        card {
            heading("可实际操作")
            paragraph("导入 UTF-8 文本副本、提取 Markdown 未完成事项、对照原文、撤销一次、系统另存为、重新打开本地副本。文件上限 256 KiB 是原型试验限制，不是正式版定值。")
        }
        card {
            heading("仍是演练 / 尚未接入")
            paragraph("模型配置与数据发送、手机动作、审批期限、停止、结果核实、Full 恢复均为演练。未接入 dsh、后台服务、正式 SQLite 账本、插件或运行时，不具有手机自动化能力。")
        }
        paragraph("请尝试：完成一次文件交付；在任务演练中提交前拒绝；派发后停止并核实；模拟恢复失败后找到导出入口。走查员按研究记录模板登记过程，不收集真实密钥。")
        action("开始文件任务", primary = true, enabled = !state.busy) { importSample() }
    }

    private fun importSample() {
        state.runWork({
            assets.open("meeting.md").use { state.workspace.import("meeting.md", it) }
        }) { file -> state.files = state.files + file; state.select(file) }
    }

    private fun importFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "application/json", "text/x-markdown"))
        }
        runCatching { startActivityForResult(intent, IMPORT) }.onFailure { state.message = "系统文件选择器不可用，本地样本仍可使用"; render() }
    }

    private fun export(file: WorkFile) {
        // 冻结本次交付内容，避免返回文件选择器时导出后来的另一份文件。
        state.exportText = state.text
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (file.name.endsWith(".json", true)) "application/json" else "text/plain"
            putExtra(Intent.EXTRA_TITLE, "DSHX-${if (state.changed) "整理结果-" else "副本-"}${file.name}")
        }
        runCatching { startActivityForResult(intent, EXPORT) }.onFailure { state.exportText = null; state.message = "系统另存为不可用，本地结果已保留"; render() }
    }

    @Deprecated("Legacy platform result callback is retained for API 26 without an additional UI framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == EXPORT) state.exportText = null
            state.message = "已取消，本地副本保留。"
            render()
            return
        }
        when (requestCode) {
            IMPORT -> state.runWork({
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: throw IllegalArgumentException("无法读取文件名，未导入")
                val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取文件，未导入")
                input.use { state.workspace.import(name, it) }
            }) { file -> state.files = state.files + file; state.select(file) }
            EXPORT -> {
                val text = state.exportText
                state.exportText = null
                if (text == null) {
                    state.message = "原型进程已重建，未开始外部写入。请从保留的本地副本重新选择导出。"
                    render()
                    return
                }
                state.runWork({
                    try {
                        val output = contentResolver.openOutputStream(uri, "w") ?: error("无法打开目标")
                        output.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
                    } catch (_: Exception) {
                        throw IllegalStateException("外部保存结果不明：可能已写入部分或全部内容。请到目标位置核实；不会自动重试，本地成果保留。")
                    }
                }) { state.message = "系统保存已返回成功。请在目标应用打开文件核对内容；本地副本仍保留。" }
            }
        }
    }

    private fun undo(file: WorkFile) {
        state.runWork({
            val restored = state.workspace.undo(file)
            Triple(restored, state.workspace.read(restored), state.workspace.list())
        }) { (restored, text, files) ->
            state.selected = restored
            state.text = text
            state.before = text
            state.changed = false
            state.files = files
            state.allowed = false
            state.message = if (restored.id == file.id) "已恢复到整理前的副本。" else "当前副本后来发生变化，已另存恢复副本，没有覆盖。"
        }
    }

    private fun expireApproval(): Boolean {
        if (state.flow.stage != TaskStage.APPROVAL || SystemClock.elapsedRealtime() < state.approvalDeadline) return false
        state.flow.expire()
        state.message = "本次审批已过期，演练结束。不会自动续期或重复弹出。"
        return true
    }
    private fun remainingText() = "${((state.approvalDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000} 秒后过期 · 后台不延长"
    private fun stageName() = when (state.flow.stage) {
        TaskStage.READY -> "准备好，再开始。"
        TaskStage.RUNNING -> "正在观察"
        TaskStage.APPROVAL -> "等待你的确认"
        TaskStage.DISPATCHED -> "动作已派发"
        TaskStage.STOPPING -> "停止中"
        TaskStage.STOPPED -> "已停止"
        TaskStage.UNKNOWN -> "结果不明，先核实"
        TaskStage.COMPLETE -> "已完成演练"
    }
    private fun go(page: String) { state.page = page; render() }
    @Deprecated("Platform back dispatch keeps the prototype compatible with API 26")
    override fun onBackPressed() { if (state.page == "home") super.onBackPressed() else go("home") }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Int = 16, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(4).toFloat(), 1f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private fun eyebrow(text: String) { body.addView(label(text, 13, true).apply { setTextColor(accent); setPadding(0, 0, 0, dp(8)) }) }
    private fun title(text: String) { body.addView(label(text, 30, true).apply { setPadding(0, 0, 0, dp(12)); if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }) }
    private fun heading(text: String) { body.addView(label(text, 18, true).apply { setPadding(0, dp(8), 0, dp(8)); if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }) }
    private fun paragraph(text: String) { body.addView(label(text).apply { setTextColor(muted); setPadding(0, 0, 0, dp(12)) }) }
    private fun code(text: String) { body.addView(label(text, 15).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(surface) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    private fun notice(text: String) { body.addView(label(text, 14).apply { setTextColor(warning); setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(surface); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun card(content: () -> Unit) {
        val parent = body
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)); background = rounded(surface) }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        body = card
        content()
        body = parent
    }
    private fun action(text: String, primary: Boolean = false, enabled: Boolean = true, run: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            minHeight = dp(52)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isEnabled = enabled
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accent else controlSurface)
            setTextColor(if (primary) onAccent else accent)
            alpha = if (enabled) 1f else 0.5f
            setOnClickListener { run() }
        }
        body.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    companion object {
        private const val IMPORT = 1
        private const val EXPORT = 2
        private const val COUNTDOWN = 1001
        private const val RECEIVER = 1002
    }
}
