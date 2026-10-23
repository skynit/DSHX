package local.dshx.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/** 普通 Android 能力，与界面节点服务分开；异步读取和弹窗返回各自的清理函数。 */
@Suppress("DEPRECATION")
internal class PhoneDevice private constructor(private val context: Context) {
    private val settings = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    val files = PhoneFiles(context)
    private var activity = WeakReference<Activity>(null)
    private var overlay: TextView? = null
    private var torch: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var overlayOwner: (() -> Boolean)? = null
    private var torchOwner: (() -> Boolean)? = null
    private val watcher = object : Runnable {
        override fun run() {
            if (overlay != null && !runCatching { overlayOwner?.invoke() == true }.getOrDefault(false)) hideOverlay()
            if (torch != null && !runCatching { torchOwner?.invoke() == true }.getOrDefault(false)) stopTorch()
            if (overlay != null || torch != null) main.postDelayed(this, 250)
        }
    }
    private fun watch() { main.removeCallbacks(watcher); main.postDelayed(watcher, 250) }

    fun attach(value: Activity) { activity = WeakReference(value) }
    fun detach(value: Activity) { if (activity.get() === value) activity.clear() }
    fun allowed(name: String): Boolean = settings.getBoolean(name, name == "overlay")
    fun setAllowed(name: String, enabled: Boolean) {
        require(name in permissions)
        settings.edit().putBoolean(name, enabled).apply()
        if (!enabled) {
            if (name == "files") files.revoke()
            if (name == "overlay") hideOverlay()
            if (name == "torch") stopTorch()
        }
    }

    fun reset() { hideOverlay(); stopTorch() }
    private fun hideOverlay() { overlay?.let { runCatching { context.getSystemService(WindowManager::class.java).removeViewImmediate(it) } }; overlay = null }
    private fun stopTorch() { torch?.let { runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(it, false) } }; torch = null }

    fun permission(name: String): Boolean = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    fun status(): JSONObject = JSONObject().apply {
        for (name in permissions) put(name, allowed(name))
        put("locationPermission", permission(Manifest.permission.ACCESS_COARSE_LOCATION) || permission(Manifest.permission.ACCESS_FINE_LOCATION))
        put("cameraPermission", permission(Manifest.permission.CAMERA))
        put("stepsPermission", Build.VERSION.SDK_INT < 29 || permission(Manifest.permission.ACTIVITY_RECOGNITION))
        put("notificationsPermission", (Build.VERSION.SDK_INT < 33 || permission(Manifest.permission.POST_NOTIFICATIONS)) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        put("overlayPermission", Settings.canDrawOverlays(context))
        put("directoryReady", files.ready()).put("directoryWritable", files.ready(write = true))
        put("clipboardForeground", activity.get()?.hasWindowFocus() == true)
    }

    fun validate(method: String, args: JSONObject) {
        val required = when (method) {
            "read_file", "export" -> setOf("path")
            "notify" -> setOf("title", "text")
            "toast", "overlay" -> setOf("text")
            "ask" -> setOf("question", "options")
            "clipboard" -> setOf("mode")
            "share" -> emptySet()
            "open_url" -> setOf("url")
            "sensor" -> setOf("name")
            "torch" -> setOf("on")
            else -> emptySet()
        }
        val optional = when (method) {
            "export" -> setOf("name")
            "clipboard" -> setOf("text")
            "share" -> setOf("text", "path")
            "vibrate" -> setOf("ms")
            "location" -> setOf("fresh")
            else -> emptySet()
        }
        fun invalid(): Nothing = throw PhoneFailure("invalid_args", "参数缺失、类型错误或包含多余字段")
        if (required.any { !args.has(it) } || args.keys().asSequence().any { it !in required + optional }) invalid()
        for (key in args.keys()) {
            when (key) {
                "on", "fresh" -> if (args.opt(key) !is Boolean) invalid()
                "ms" -> if (args.opt(key) !is Number || args.getDouble(key) != args.getInt(key).toDouble() || args.getInt(key) !in 1..3000) invalid()
                "options" -> {
                    val options = args.optJSONArray(key) ?: invalid()
                    if (options.length() !in 1..3) invalid()
                    val values = (0 until options.length()).map { options.opt(it) }
                    if (values.any { it !is String || it.isBlank() || it.length > 80 } || values.distinct().size != values.size) invalid()
                }
                else -> { val value = args.opt(key) as? String ?: invalid(); PhoneService.requireInput(value) }
            }
        }
        if (method == "clipboard" && (args.getString("mode") !in setOf("read", "write") ||
                (args.getString("mode") == "write") != args.has("text"))) invalid()
        if (method == "share" && (args.has("text") == args.has("path"))) invalid()
        if (method == "open_url") {
            val uri = android.net.Uri.parse(args.getString("url"))
            if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) invalid()
        }
        if (method == "ask" && args.getString("question").isBlank()) invalid()
    }

    /** 从 PhoneControl 的主线程任务进入；不会在此自动弹系统权限请求。 */
    fun execute(method: String, args: JSONObject, check: () -> Unit, dispatch: () -> Unit, finish: (JSONObject) -> Unit, live: () -> Boolean): (() -> Unit)? {
        val gate = capability(method, args)
        fun current() { check(); if (gate != null && !allowed(gate)) throw PhoneFailure("capability_disabled", "请在 DSHX 设置中开启该项设备能力") }
        current()
        val value = when (method) {
            "device" -> device()
            "clipboard" -> clipboard(args, ::current, dispatch)
            "notify" -> notify(args, ::current, dispatch)
            "toast" -> { current(); dispatch(); Toast.makeText(context, args.getString("text"), Toast.LENGTH_LONG).show(); PhoneControl.success().put("submitted", true) }
            "vibrate" -> {
                val vibrator = context.getSystemService(Vibrator::class.java)
                if (!vibrator.hasVibrator()) throw PhoneFailure("unsupported", "设备没有振动器")
                current(); dispatch(); vibrator.vibrate(VibrationEffect.createOneShot(args.optLong("ms", 300), VibrationEffect.DEFAULT_AMPLITUDE))
                PhoneControl.success().put("submitted", true)
            }
            "open_url" -> { launch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(args.getString("url"))), ::current, dispatch); PhoneControl.success().put("submitted", true) }
            "share" -> {
                if (args.has("path")) return files.execute("share", args, ::current, dispatch) { result ->
                    main.post {
                        try {
                            current()
                            if (!result.optBoolean("ok")) finish(result)
                            else finish(shareFile(android.net.Uri.parse(result.getString("uri")), result.getString("mime"), ::current, dispatch))
                        } catch (error: Exception) { finish(PhoneControl.errorResult(error)) }
                    }
                }
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.getString("text"))
                launch(Intent.createChooser(intent, "分享助手内容"), ::current, dispatch)
                PhoneControl.success().put("submitted", true).put("requiresUserChoice", true)
            }
            "overlay" -> showOverlay(args.getString("text"), ::current, dispatch).also { overlayOwner = live; watch() }
            "sensors" -> sensors()
            "torch" -> torch(args.getBoolean("on"), ::current, dispatch).also { torchOwner = live; watch() }
            "read_file", "export" -> return files.execute(method, args, ::current, dispatch, finish)
            "location" -> return location(args.optBoolean("fresh", false), ::current, dispatch, finish)
            "sensor" -> return sensor(args.getString("name"), ::current, dispatch, finish)
            "ask" -> return ask(args, ::current, dispatch, finish)
            else -> throw PhoneFailure("unknown_method", "不支持的设备操作")
        }
        current(); finish(value)
        return null
    }

    private fun device(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val storage = StatFs(context.filesDir.absolutePath)
        val size = android.graphics.Point().also { context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealSize(it) }
        val network = context.getSystemService(ConnectivityManager::class.java)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return PhoneControl.success().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("api", Build.VERSION.SDK_INT).put("batteryPercent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0).put("networkConnected", network.activeNetwork != null)
            .put("screenWidth", size.x).put("screenHeight", size.y).put("memoryTotal", memory.totalMem).put("memoryAvailable", memory.availMem)
            .put("storageTotal", storage.totalBytes).put("storageAvailable", storage.availableBytes)
    }

    private fun clipboard(args: JSONObject, check: () -> Unit, dispatch: () -> Unit): JSONObject {
        if (activity.get()?.hasWindowFocus() != true) throw PhoneFailure("foreground_required", "请将 DSHX 切到前台后再读写剪贴板")
        val manager = context.getSystemService(ClipboardManager::class.java)
        check()
        if (args.getString("mode") == "write") { dispatch(); manager.setPrimaryClip(ClipData.newPlainText("DSHX", args.getString("text"))); return PhoneControl.success().put("written", true) }
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) return PhoneControl.success().put("text", "").put("empty", true)
        // 只读实际文字，不调用 coerceToText 去打开剪贴板中的文件或外部内容 URI。
        val text = clip.getItemAt(0).text?.toString() ?: throw PhoneFailure("not_text", "剪贴板当前不是文字内容")
        return PhoneControl.success().put("text", text.take(2000)).put("truncated", text.length > 2000)
    }

    private fun notify(args: JSONObject, check: () -> Unit, dispatch: () -> Unit): JSONObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !permission(Manifest.permission.POST_NOTIFICATIONS) || !manager.areNotificationsEnabled()) throw PhoneFailure("permission_required", "请在 DSHX 设置中允许通知")
        manager.createNotificationChannel(NotificationChannel("phone-results", "助手设备通知", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel("phone-results")?.importance == NotificationManager.IMPORTANCE_NONE) throw PhoneFailure("permission_required", "请在系统通知设置中开启助手设备通知")
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, "phone-results").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(args.getString("title").take(160)).setContentText(args.getString("text"))
            .setStyle(Notification.BigTextStyle().bigText(args.getString("text"))).setContentIntent(intent).setAutoCancel(true).build()
        check(); dispatch(); manager.notify(7301, notification)
        return PhoneControl.success().put("submitted", true).put("notificationId", 7301)
    }

    internal fun launch(intent: Intent, check: () -> Unit, dispatch: () -> Unit) {
        val host = activity.get()?.takeIf { it.hasWindowFocus() } ?: PhoneService.current
            ?: throw PhoneFailure("foreground_required", "请将 DSHX 切到前台，或连接无障碍服务后再打开系统页面")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) throw PhoneFailure("unsupported", "没有可处理此请求的应用")
        check(); dispatch(); host.startActivity(intent)
    }

    private fun shareFile(uri: android.net.Uri, mime: String, check: () -> Unit, dispatch: () -> Unit): JSONObject {
        val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("DSHX", uri)
        launch(Intent.createChooser(intent, "分享助手内容"), check, dispatch)
        return PhoneControl.success().put("submitted", true).put("requiresUserChoice", true)
    }

    private fun showOverlay(text: String, check: () -> Unit, dispatch: () -> Unit): JSONObject {
        if (!Settings.canDrawOverlays(context)) throw PhoneFailure("permission_required", "请在 DSHX 设置中允许悬浮提示")
        check(); dispatch()
        if (text.isEmpty()) { hideOverlay(); return PhoneControl.success().put("visible", false) }
        val manager = context.getSystemService(WindowManager::class.java)
        val view = overlay ?: TextView(context).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xE6202220.toInt()); textSize = 14f; setPadding(24, 16, 24, 16)
            maxLines = 6; maxWidth = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
        }.also {
            val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 100 }
            manager.addView(it, layout); overlay = it
        }
        view.text = text
        return PhoneControl.success().put("visible", true)
    }

    private fun ask(args: JSONObject, check: () -> Unit, dispatch: () -> Unit, finish: (JSONObject) -> Unit): () -> Unit {
        val service = PhoneService.current
        val host: Context = activity.get()?.takeIf { it.hasWindowFocus() } ?: service
            ?: throw PhoneFailure("foreground_required", "请将 DSHX 切到前台后回答助手问题")
        val options = args.getJSONArray("options")
        var dialog: AlertDialog? = null
        val builder = AlertDialog.Builder(host).setTitle("助手提问").setMessage(args.getString("question"))
        fun answer(index: Int) {
            try { check(); finish(PhoneControl.success().put("index", index).put("answer", options.getString(index))) }
            catch (error: Exception) { finish(PhoneControl.errorResult(error)) }
        }
        builder.setPositiveButton(options.getString(0)) { _, _ -> answer(0) }
        if (options.length() > 1) builder.setNegativeButton(options.getString(1)) { _, _ -> answer(1) }
        if (options.length() > 2) builder.setNeutralButton(options.getString(2)) { _, _ -> answer(2) }
        check(); dispatch()
        dialog = builder.create().apply {
            if (host === service) window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            setOnCancelListener { finish(PhoneControl.failure("user_dismissed", "用户关闭了提问，没有选择选项").put("execution_state", "completed")) }
            show()
        }
        return { dialog?.setOnCancelListener(null); dialog?.dismiss(); Unit }
    }

    private fun sensors(): JSONObject {
        val manager = context.getSystemService(SensorManager::class.java)
        val values = JSONArray()
        for ((name, type) in sensorTypes) manager.getDefaultSensor(type)?.let {
            values.put(JSONObject().put("name", name).put("type", type).put("label", it.name.take(160)).put("vendor", it.vendor.take(160)))
        }
        return PhoneControl.success().put("sensors", values)
    }

    private fun sensor(name: String, check: () -> Unit, dispatch: () -> Unit, finish: (JSONObject) -> Unit): () -> Unit {
        val type = sensorTypes[name] ?: throw PhoneFailure("invalid_args", "请使用 phone_sensors 返回的传感器名称")
        if (name == "steps" && Build.VERSION.SDK_INT >= 29 && !permission(Manifest.permission.ACTIVITY_RECOGNITION)) throw PhoneFailure("permission_required", "读取步数需要身体活动权限")
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(type) ?: throw PhoneFailure("unsupported", "设备没有该传感器")
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                manager.unregisterListener(this)
                try { check(); finish(PhoneControl.success().put("name", name).put("values", JSONArray(event.values.take(16))).put("accuracy", event.accuracy)) }
                catch (error: Exception) { finish(PhoneControl.errorResult(error)) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        check(); dispatch()
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))) throw PhoneFailure("unavailable", "无法读取该传感器")
        return { manager.unregisterListener(listener) }
    }

    private fun location(fresh: Boolean, check: () -> Unit, dispatch: () -> Unit, finish: (JSONObject) -> Unit): (() -> Unit)? {
        val fine = permission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !permission(Manifest.permission.ACCESS_COARSE_LOCATION)) throw PhoneFailure("permission_required", "请在 DSHX 设置中允许位置权限")
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = manager.getProviders(true).filter { it in setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) && (fine || it != LocationManager.GPS_PROVIDER) }
        if (providers.isEmpty()) throw PhoneFailure("location_unavailable", "系统定位未开启或没有可用位置提供程序")
        fun result(value: Location) = PhoneControl.success().put("latitude", value.latitude).put("longitude", value.longitude)
            .put("accuracyMeters", value.accuracy.toDouble()).put("approximate", !fine).put("ageMs", maxOf(0, (SystemClock.elapsedRealtimeNanos() - value.elapsedRealtimeNanos) / 1000000))
        if (!fresh) {
            val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.filter {
                val age = SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos; age in 0..300000000000L
            }.maxByOrNull { it.elapsedRealtimeNanos }
            if (cached != null) { check(); finish(result(cached)); return null }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                try { check(); finish(result(location)) } catch (error: Exception) { finish(PhoneControl.errorResult(error)) }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        check(); dispatch()
        try { providers.forEach { manager.requestSingleUpdate(it, listener, Looper.getMainLooper()) } }
        catch (error: Exception) { manager.removeUpdates(listener); throw error }
        return { manager.removeUpdates(listener) }
    }

    private fun torch(on: Boolean, check: () -> Unit, dispatch: () -> Unit): JSONObject {
        if (!permission(Manifest.permission.CAMERA)) throw PhoneFailure("permission_required", "手电筒需要相机权限")
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = torch ?: manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw PhoneFailure("unsupported", "设备没有可用闪光灯")
        check(); dispatch(); manager.setTorchMode(camera, on); torch = if (on) camera else null
        return PhoneControl.success().put("on", on)
    }

    companion object {
        fun capability(method: String, args: JSONObject): String? = when (method) {
            "clipboard" -> "clipboard"
            "read_file", "export" -> "files"
            "share" -> if (args.has("path")) "files" else null
            "location" -> "location"
            "sensors", "sensor" -> "sensors"
            "torch" -> "torch"
            "overlay" -> "overlay"
            else -> null
        }
        val methods = setOf("device", "clipboard", "read_file", "export", "notify", "toast", "ask", "vibrate", "share", "open_url", "overlay", "location", "sensors", "sensor", "torch")
        val permissions = listOf("clipboard", "files", "location", "sensors", "torch", "overlay")
        private val sensorTypes = linkedMapOf("light" to Sensor.TYPE_LIGHT, "accel" to Sensor.TYPE_ACCELEROMETER, "gyro" to Sensor.TYPE_GYROSCOPE,
            "magnet" to Sensor.TYPE_MAGNETIC_FIELD, "pressure" to Sensor.TYPE_PRESSURE, "proximity" to Sensor.TYPE_PROXIMITY,
            "gravity" to Sensor.TYPE_GRAVITY, "rotation" to Sensor.TYPE_ROTATION_VECTOR, "steps" to Sensor.TYPE_STEP_COUNTER,
            "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY, "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE)
        @Volatile private var instance: PhoneDevice? = null
        fun get(context: Context): PhoneDevice = synchronized(this) {
            instance?.takeIf { it.context === context.applicationContext }
                ?: PhoneDevice(context.applicationContext).also { instance = it }
        }
    }
}
