package local.dshx.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** 独立应用的页面及本机设置；网页不能代替用户修改权限和适用范围。 */
class MicroActivity : Activity() {
    private val app get() = application as MicroApp
    private val colors get() = AppColors(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private var page: AppHost.Page? = null
    private var settingsOpen = false
    private lateinit var root: LinearLayout
    private var notice: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        shell()
        label(root, "正在打开…")
        app.prepare {
            if (isDestroyed) return@prepare
            if (app.failure != null) { shell(); label(root, app.failure!!) }
            else {
                if (app.store.active().isNotEmpty() && app.definition.background != null) startBackground()
                if (app.store.active().isEmpty()) showSettings() else showPage()
            }
        }
    }

    private fun shell() {
        page?.onClosed = null; page?.close(); page = null
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.background) }
        setContentView(root)
        window.statusBarColor = colors.background; window.navigationBarColor = colors.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }

    private fun header(settings: Boolean) {
        val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        if (settings) row.addView(button("‹", "返回应用") { if (app.store.active().isEmpty()) finish() else showPage() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(this).apply {
            text = if (settings) "应用设置" else app.definition.name
            textSize = 18f; setTextColor(colors.text); setPadding(dp(12), 0, dp(8), 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!settings) row.addView(button("设置") { showSettings() })
        root.addView(row)
        root.addView(View(this).apply { setBackgroundColor(colors.border) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun showPage() {
        settingsOpen = false; shell(); header(false)
        runCatching { app.host.open(app.definition.id) }.fold({ current ->
            page = current
            current.appearance(JSONObject().put("--bg", css(colors.background)).put("--fg", css(colors.text))
                .put("--surface", css(colors.surface)).put("--line", css(colors.border)).put("--muted", css(colors.muted))
                .put("--accent", css(colors.primary)).put("--input", css(colors.input))
                .put("color-scheme", if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"), colors.background)
            current.onClosed = { if (!isDestroyed && !settingsOpen) showSettings() }
            root.addView(current.view, LinearLayout.LayoutParams(-1, 0, 1f))
        }, { label(root, it.message ?: "应用无法打开。") })
    }

    private fun showSettings() {
        settingsOpen = true; shell(); header(true)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val definition = app.definition
        val row = app.store.status(definition.id).getJSONArray("apps").getJSONObject(0)
        val draft = JSONObject(row.getJSONObject("settings").toString())
        label(body, definition.name, 20f)
        label(body, definition.description)
        val enabled = row.getBoolean("enabled")
        val toggle = Switch(this).apply { text = "启用应用"; isChecked = enabled; minHeight = dp(56); setTextColor(colors.text) }
        body.addView(toggle)
        val readers = mutableListOf<() -> Unit>()
        definition.settings.forEach { field ->
            when (field.type) {
                "switch" -> {
                    val control = Switch(this).apply { text = field.label; isChecked = draft.getBoolean(field.id); minHeight = dp(56); setTextColor(colors.text) }
                    body.addView(control); readers.add { draft.put(field.id, control.isChecked) }
                }
                "app-list" -> {
                    val selected = AppPackage.array(draft.getJSONArray(field.id)).map(Any::toString).toMutableSet()
                    lateinit var control: Button
                    control = button("${field.label} · 已选 ${selected.size} 个") {
                        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                            .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled && it.activityInfo.packageName != packageName }
                            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
                        val names = apps.map { it.activityInfo.packageName }
                        AlertDialog.Builder(this).setTitle(field.label)
                            .setMultiChoiceItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray(), BooleanArray(names.size) { names[it] in selected }) { _, index, on ->
                                if (on) selected.add(names[index]) else selected.remove(names[index])
                            }.setPositiveButton("完成") { _, _ -> control.text = "${field.label} · 已选 ${selected.size} 个" }.show()
                    }
                    body.addView(control, spaced()); readers.add { draft.put(field.id, JSONArray(selected.sorted())) }
                }
                else -> {
                    label(body, field.label)
                    val input = EditText(this).apply {
                        setText(draft.get(field.id).toString()); textSize = 16f; setTextColor(colors.text); setHintTextColor(colors.muted)
                        minHeight = dp(48); maxLines = 4; setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = shape(colors.input, true)
                        inputType = if (field.type == "number") EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
                            else EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    body.addView(input, spaced())
                    readers.add { draft.put(field.id, if (field.type == "number") input.text.toString().toDoubleOrNull() ?: error("${field.label}需要填写数字。") else input.text.toString()) }
                }
            }
        }
        if (definition.usesScreen) {
            label(body, "仅操作你选择的应用。首次使用请开启本应用的无障碍服务。")
            body.addView(button("开启无障碍服务") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, spaced())
        }
        if (definition.background != null) label(body, "启用后通过常驻通知保持后台运行，可从通知中停止。")
        notice = label(body, "", 14f)
        body.addView(button("保存设置") {
            runCatching {
                readers.forEach { it() }
                val settings = definition.settings(draft)
                if (toggle.isChecked) {
                    app.change("enable", JSONObject().put("id", definition.id).put("settings", settings))
                    if (definition.background != null) startBackground()
                    showPage()
                } else {
                    // 停用也保留编辑后的设置；屏幕应用范围为空时不临时启用。
                    app.store.configure(definition.id, settings, row.getLong("revision"))
                    stopService(Intent(this, MicroService::class.java)); showSettings()
                }
            }.onFailure { notice?.text = it.message ?: "保存失败，请重试。"; notice?.setTextColor(colors.danger) }
        }, spaced())
    }

    private fun startBackground() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, MicroService::class.java))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun css(value: Int) = "#%06x".format(value and 0xffffff)
    private fun shape(color: Int, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(10).toFloat(); if (border) setStroke(dp(1), this@MicroActivity.colors.border)
    }
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    private fun label(parent: LinearLayout, value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(colors.text); setPadding(0, dp(8), 0, dp(8)); parent.addView(this)
    }
    private fun button(value: String, description: String = value, action: () -> Unit) = Button(this).apply {
        text = value; contentDescription = description; textSize = 14f; isAllCaps = false
        minHeight = dp(48); minimumHeight = dp(48); setTextColor(colors.text); setPadding(dp(16), dp(8), dp(16), dp(8))
        stateListAnimator = null; elevation = 0f
        background = RippleDrawable(ColorStateList.valueOf(colors.border), shape(colors.selected), null)
        setOnClickListener { action() }
    }
    @Deprecated("平台返回兼容")
    override fun onBackPressed() { if (settingsOpen && app.store.active().isNotEmpty()) showPage() else super.onBackPressed() }
    override fun onDestroy() { page?.onClosed = null; page?.close(); page = null; super.onDestroy() }
}
