package com.nex.tuner.gamebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max

data class GameBarSnapshot(
    val fps: String = "N/A",
    val cpu: String = "N/A",
    val cpuClock: String = "N/A",
    val cpuTemp: String = "N/A",
    val gpu: String = "N/A",
    val gpuClock: String = "N/A",
    val gpuTemp: String = "N/A",
    val ram: String = "N/A",
    val battery: String = "N/A"
)

class GameBarService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var windowManager: WindowManager
    private var overlay: TextView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var previousCpu: List<Long>? = null
    private var running = false
    private var collapsed = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val gamePrefs = getSharedPreferences("nex_game_space", MODE_PRIVATE)
            val foreground = GameForeground.current(this)
            val shouldShow = prefs.getBoolean("enabled", false) ||
                (prefs.getBoolean("auto", false) && foreground != null && gamePrefs.getBoolean("game.$foreground.enabled", false))
            if (shouldShow && overlay == null) showOverlay()
            if (!shouldShow && overlay != null) {
                runCatching { windowManager.removeView(overlay) }
                overlay = null
                layout = null
            }
            if (shouldShow) {
                val stats = readSnapshot()
                val lines = mutableListOf("NEX TUNER  /  GAMEBAR")
                if (prefs.getBoolean("show_fps", true)) lines += "FPS  ${stats.fps}"
                if (prefs.getBoolean("show_cpu", true)) lines += "CPU  ${stats.cpu}   CLK  ${stats.cpuClock}"
                if (prefs.getBoolean("show_temps", true)) lines += "CPU TEMP  ${stats.cpuTemp}"
                if (prefs.getBoolean("show_gpu", true)) lines += "GPU  ${stats.gpu}   CLK  ${stats.gpuClock}"
                if (prefs.getBoolean("show_temps", true)) lines += "GPU TEMP  ${stats.gpuTemp}   BAT  ${stats.battery}"
                lines += "RAM  ${stats.ram}"
                lines += "Tap: collapse  •  Drag: move"
                overlay?.text = if (collapsed) "NEX  •  ${stats.fps} FPS" else lines.joinToString("\n")
            }
            handler.postDelayed(this, prefs.getLong("interval", 1000L).coerceIn(500L, 5000L))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        worker = HandlerThread("NexGameBar").apply { start() }
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay == null) showOverlay()
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun showOverlay() {
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(18, 14, 18, 14)
            background = GradientDrawable().apply {
                setColor(0xE91B2028.toInt())
                cornerRadius = 26f
                setStroke(1, 0x5548B7FF)
            }
            setOnClickListener { collapsed = !collapsed }
            setOnTouchListener(DragTouchListener())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 150
        }
        overlay = view
        layout = params
        runCatching { windowManager.addView(view, params) }.onFailure { overlay = null; layout = null }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = layout ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 5) moved = true
                    p.x = max(0, startX - dx)
                    p.y = max(0, startY + dy)
                    runCatching { windowManager.updateViewLayout(v, p) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    return true
                }
            }
            return false
        }
    }

    private fun root(command: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText().trim() }
        if (p.waitFor() == 0) out else null
    }.getOrNull()

    private fun readSnapshot(): GameBarSnapshot {
        val cpuLines = root("cat /proc/stat")?.lineSequence()?.firstOrNull()?.trim()?.split(Regex("\\s+"))
        val cpuNow = cpuLines?.drop(1)?.mapNotNull { it.toLongOrNull() }
        var cpuUsage = "N/A"
        if (cpuNow != null && cpuNow.size >= 4) {
            val old = previousCpu
            if (old != null && old.size >= 4) {
                val totalNow = cpuNow.sum()
                val totalOld = old.sum()
                val idleNow = cpuNow[3] + (cpuNow.getOrNull(4) ?: 0L)
                val idleOld = old[3] + (old.getOrNull(4) ?: 0L)
                val totalDelta = totalNow - totalOld
                val idleDelta = idleNow - idleOld
                if (totalDelta > 0) cpuUsage = "${((totalDelta - idleDelta) * 100 / totalDelta).coerceIn(0, 100)}%"
            }
            previousCpu = cpuNow
        }
        val policies = root("ls -d /sys/devices/system/cpu/cpufreq/policy[0-9]* 2>/dev/null")?.lines().orEmpty()
        val clocks = policies.mapNotNull { path -> root("cat '$path/scaling_cur_freq'")?.toLongOrNull() }.filter { it > 0 }
        val cpuClock = clocks.maxOrNull()?.let { "${it / 1000} MHz" } ?: "N/A"
        val zones = root("for d in /sys/class/thermal/thermal_zone*; do [ -r \"$d/type\" ] && [ -r \"$d/temp\" ] && echo \"$(cat $d/type):$(cat $d/temp)\"; done")?.lines().orEmpty()
        fun temp(match: String): String {
            val value = zones.firstOrNull { it.substringBefore(":").contains(match, true) }?.substringAfter(":")?.toDoubleOrNull() ?: return "N/A"
            val c = when { value > 1000 -> value / 1000.0; value > 200 -> value / 10.0; else -> value }
            return String.format(java.util.Locale.US, "%.1f°C", c)
        }
        val gpuBase = "/sys/class/kgsl/kgsl-3d0"
        val gpuFreq = root("cat '$gpuBase/devfreq/cur_freq'")?.toLongOrNull()?.takeIf { it > 0 }?.let { "${it / 1000000} MHz" } ?: "N/A"
        val gpuLoadRaw = root("cat '$gpuBase/gpubusy'")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
        val gpuLoad = if (gpuLoadRaw != null && gpuLoadRaw.size >= 2 && gpuLoadRaw[1] > 0) "${(gpuLoadRaw[0] * 100 / gpuLoadRaw[1]).coerceIn(0,100)}%" else "N/A"
        val fps = listOf("/sys/class/drm/sde-crtc-0/measured_fps", "/sys/class/drm/card0-DSI-1/measured_fps")
            .firstNotNullOfOrNull { path -> root("cat '$path'")?.toDoubleOrNull()?.takeIf { it in 1.0..360.0 } }
            ?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "N/A"
        val mem = root("cat /proc/meminfo")?.lines().orEmpty()
        val total = mem.firstOrNull { it.startsWith("MemTotal:") }?.filter { it.isDigit() }?.toLongOrNull()
        val available = mem.firstOrNull { it.startsWith("MemAvailable:") }?.filter { it.isDigit() }?.toLongOrNull()
        val ram = if (total != null && available != null && total > 0) "${((total-available)*100/total)}%" else "N/A"
        val batteryTemp = root("cat /sys/class/power_supply/battery/temp")?.toDoubleOrNull()?.let {
            String.format(java.util.Locale.US, "%.1f°C", if (it > 200) it / 10 else it)
        } ?: "N/A"
        return GameBarSnapshot(fps, cpuUsage, cpuClock, temp("cpu"), gpuLoad, gpuFreq, temp("gpu"), ram, batteryTemp)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "GameBar overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("Nex Tuner GameBar")
        .setContentText("Live performance overlay is active")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        if (overlay != null) runCatching { windowManager.removeView(overlay) }
        overlay = null
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.nex.tuner.gamebar.STOP"
        const val PREFS = "nex_gamebar"
        private const val CHANNEL = "nex_gamebar_overlay"
        private const val NOTIFICATION_ID = 7351
    }
}
