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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

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
    private val main = Handler(Looper.getMainLooper())
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
        val text = if (dark) Color.rgb(237, 236, 232) else Color.rgb(42, 42, 38)
        val secondary = if (dark) Color.rgb(177, 176, 166) else Color.rgb(108, 108, 100)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(30, 30, 27) else Color.rgb(250, 250, 247))
            }
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.dsh_logo)
            imageTintList = ColorStateList.valueOf(text)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(36), dp(30)).apply { marginEnd = dp(8) })
        heading.addView(TextView(this).apply {
            setText(R.string.phone_window_title); textSize = 14f; setTextColor(text)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        root.addView(heading)
        title = TextView(this).apply {
            textSize = 18f; setTextColor(text); maxLines = 2
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(title)
        detail = TextView(this).apply { textSize = 12f; setTextColor(secondary); maxLines = 2 }
        root.addView(detail)
        controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(Button(this).apply {
            this.text = "停止手机操作"; isAllCaps = false; minimumHeight = dp(48)
            setOnClickListener { state?.stop() }
        })
        controls.addView(Button(this).apply {
            this.text = "收起"; isAllCaps = false; minimumHeight = dp(48)
            setOnClickListener { enterWindow() }
        })
        root.addView(controls)
        setContentView(root)
    }

    internal fun render() {
        if (!::title.isInitialized) return
        title.text = state?.title
        detail.text = state?.detail
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
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
