package local.dshx.app

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale

/** Android 创建的独立画中画页面；展开、拖动和小窗控制按钮由系统提供。 */
class PhoneWindowActivity : Activity() {
    private var state: PhoneWindow? = null
    private var closing = false
    private var entered = false
    private var registered = false
    private var drawListener: ViewTreeObserver.OnPreDrawListener? = null
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var controls: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var heading: LinearLayout
    private lateinit var summary: TextView
    private lateinit var brand: TextView
    private lateinit var history: LinearLayout
    private var historyRows = emptyList<LinearLayout>()
    private lateinit var colors: AppColors
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateSummary()
            if (!closing && state?.history?.lastOrNull()?.status == PhoneWindow.Status.Running) main.postDelayed(this, 1000)
        }
    }
    private val stop = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra("window") == state?.id) state?.stop()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = PhoneWindow.current?.takeIf { it.id == intent.getStringExtra("window") }
        if (state == null) { closeWindow(); return }
        state!!.attach(this)
        val filter = IntentFilter(STOP)
        ContextCompat.registerReceiver(this, stop, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        buildPanel()
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildPanel() {
        val theme = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "system")
        val dark = theme == "dark" || theme == "system" && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        colors = AppColors(dark)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.dsh_logo)
            imageTintList = ColorStateList.valueOf(colors.primary)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(16)).apply { marginEnd = dp(6) })
        brand = text(12f, colors.text).apply {
            text = "DSHX"
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        heading.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary = text(12f, colors.muted).apply { gravity = Gravity.END }
        heading.addView(summary)
        root.addView(heading)
        title = text(14f, colors.text).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        root.addView(title)
        detail = text(12f, colors.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(detail)
        history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        history.addView(View(this).apply { setBackgroundColor(colors.border) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6); bottomMargin = dp(4)
            })
        historyRows = List(2) {
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(12f, colors.muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(12f, colors.muted).apply { gravity = Gravity.END; setPadding(dp(8), 0, 0, 0) })
                history.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) })
            }
        }
        root.addView(history)
        controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        controls.addView(button("停止操作", "停止手机操作", colors.danger) { state?.stop() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        controls.addView(button("收起", "收起操作小窗", colors.text) { enterWindow() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(colors.background)
            addView(root)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitHistory() }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitHistory() }
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(scroll)
    }

    private fun text(size: Float, color: Int) = TextView(this).apply {
        textSize = size; setTextColor(color); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun button(label: String, description: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; contentDescription = description; textSize = 13f
        isAllCaps = false; minimumHeight = dp(48); minHeight = dp(48)
        setTextColor(color)
        val palette = this@PhoneWindowActivity.colors
        val shape = GradientDrawable().apply {
            setColor(palette.input); cornerRadius = dp(8).toFloat(); setStroke(dp(1), palette.border)
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(32, Color.red(colors.primary), Color.green(colors.primary), Color.blue(colors.primary))), shape, null)
        setOnFocusChangeListener { _, focused -> shape.setStroke(dp(if (focused) 2 else 1), if (focused) colors.primary else colors.border) }
        setOnClickListener { action() }
    }

    /** 用实际文字高度选择能容纳的记录数，字体放大时优先保留当前动作和状态。 */
    private fun fitHistory() {
        if (!::history.isInitialized || root.width == 0) return
        brand.visibility = if (brand.width >= brand.paint.measureText("DSHX")) View.VISIBLE else View.INVISIBLE
        val viewport = (root.parent as? View)?.height ?: return
        var remaining = viewport - root.paddingTop - root.paddingBottom - heading.height - title.height - detail.height -
            (if (controls.visibility == View.VISIBLE) controls.height else 0) - dp(11)
        val past = state?.history.orEmpty().dropLast(1).asReversed()
        var shown = 0
        historyRows.forEachIndexed { index, row ->
            row.measure(View.MeasureSpec.makeMeasureSpec(root.width - root.paddingLeft - root.paddingRight, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val height = row.measuredHeight + dp(2)
            val visible = index < past.size && remaining >= height
            row.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) { shown++; remaining -= height }
        }
        history.visibility = if (shown > 0) View.VISIBLE else View.GONE
    }

    private fun updateSummary() {
        val step = state?.history?.lastOrNull() ?: return
        summary.text = "第 ${step.number} 步 · ${seconds(step.elapsed())}秒"
    }

    private fun seconds(value: Long) = String.format(Locale.ROOT, "%.1f", value / 1000.0)

    internal fun render() {
        if (!::title.isInitialized) return
        val current = state?.history?.lastOrNull()
        title.text = current?.action ?: state?.title
        detail.text = current?.status?.detail ?: state?.detail
        detail.setTextColor(if (current?.status in listOf(PhoneWindow.Status.Failed, PhoneWindow.Status.Unknown)) colors.danger else colors.muted)
        val past = state?.history.orEmpty().dropLast(1).asReversed()
        historyRows.forEachIndexed { index, row ->
            past.getOrNull(index)?.let { step ->
                (row.getChildAt(0) as TextView).text = "${step.number.toString().padStart(2, '0')}  ${step.action}"
                (row.getChildAt(1) as TextView).apply {
                    text = "${step.status.label} · ${seconds(step.elapsed())}秒"
                    setTextColor(if (step.status in listOf(PhoneWindow.Status.Failed, PhoneWindow.Status.Unknown)) colors.danger else colors.muted)
                }
            }
        }
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        main.removeCallbacks(tick)
        tick.run()
        root.post { fitHistory() }
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        buildPanel()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (closing || entered || drawListener != null) return
        // 先画出 DSH 内容再缩小，避免系统把启动图标快照当作小窗内容。
        drawListener = ViewTreeObserver.OnPreDrawListener {
            removeDrawListener()
            window.decorView.post { if (!closing) enterWindow() }
            true
        }.also { window.decorView.viewTreeObserver.addOnPreDrawListener(it) }
        window.decorView.invalidate()
    }

    private fun removeDrawListener() {
        drawListener?.let { if (window.decorView.viewTreeObserver.isAlive) window.decorView.viewTreeObserver.removeOnPreDrawListener(it) }
        drawListener = null
    }

    override fun onUserLeaveHint() {
        if (!closing && entered) enterWindow()
        super.onUserLeaveHint()
    }

    private fun enterWindow() {
        if (closing || isInPictureInPictureMode) return
        val action = PendingIntent.getBroadcast(this, 0,
            Intent(STOP).setPackage(packageName).putExtra("window", state?.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1)).setActions(listOf(
            RemoteAction(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "停止手机操作", "关闭手机操作开关", action)
        ))
        if (Build.VERSION.SDK_INT >= 31) builder.setSeamlessResizeEnabled(false)
        val params = builder.build()
        entered = try { enterPictureInPictureMode(params) } catch (_: RuntimeException) { false }
        if (!entered) state?.stop()
    }

    override fun onPictureInPictureModeChanged(small: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(small, config)
        render()
        if (small) { entered = true; state?.entered() }
    }

    override fun onStop() {
        super.onStop()
        // 系统进入小窗也可能先触发 onStop，等模式通知到达后再判断是否由本人关闭。
        main.postDelayed({
            if (!closing && entered && !isInPictureInPictureMode && !isChangingConfigurations && !hasWindowFocus()) state?.stop()
        }, 300)
    }

    @Deprecated("Android 8–12 的系统返回入口")
    override fun onBackPressed() { state?.stop() }

    override fun onDestroy() {
        removeDrawListener()
        main.removeCallbacksAndMessages(null)
        if (registered) unregisterReceiver(stop)
        state?.detach(this)
        if (!closing && !isChangingConfigurations) state?.stop()
        super.onDestroy()
    }

    internal fun closeWindow() {
        closing = true
        finishAndRemoveTask()
    }

    internal fun owns(id: Int): Boolean {
        if (id < 0) return false
        val node = window.decorView.createAccessibilityNodeInfo()
        return try { node.windowId == id } finally { @Suppress("DEPRECATION") node.recycle() }
    }

    private companion object { const val STOP = "local.dshx.app.STOP_PHONE" }
}
