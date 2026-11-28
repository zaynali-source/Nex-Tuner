package com.nex.tuner.gamebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.nex.tuner.game.GameForeground
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val battery: String = "N/A",
    val frameTime: String = "N/A",
    val ramSpeed: String = "N/A",
    val ramTemp: String = "N/A",
    val batteryLevel: String = "N/A",
    val powerWatt: String = "N/A",
    val appRamUsage: String = "N/A"
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
    private var lastForeground: String? = null
    private val cpuHistory = ArrayDeque<Int>()
    private val gpuHistory = ArrayDeque<Int>()
    private val fpsHistory = ArrayDeque<Int>()
    private var fpsPathCache: String? = null
    private fun remember(history: ArrayDeque<Int>, value: String) {
        value.removeSuffix("%").toIntOrNull()?.let {
            history.addLast(it.coerceIn(0, 100))
            while (history.size > 24) history.removeFirst()
        }
    }
    private fun graph(history: ArrayDeque<Int>): String {
        val bars = "▁▂▃▄▅▆▇█"
        return history.joinToString("") { bars[it.coerceIn(0, 100) * 7 / 100].toString() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val gamePrefs = getSharedPreferences("nex_game_space", MODE_PRIVATE)
            GameForeground.current(this@GameBarService)?.let { lastForeground = it }
            val foreground = lastForeground
            val shouldShow = prefs.getBoolean("enabled", false) ||
                (prefs.getBoolean("auto", false) && foreground != null && gamePrefs.getBoolean("game.$foreground.enabled", false))
            if (shouldShow && overlay == null) showOverlay()
            if (!shouldShow) {
                overlay?.let { view -> runCatching { windowManager.removeView(view) } }
                overlay = null
                layout = null
            }
            if (shouldShow) {
                val stats = readSnapshot(foreground.orEmpty())
                if (prefs.getBoolean("recording", false)) appendLog(stats, foreground.orEmpty())
                remember(cpuHistory, stats.cpu)
                remember(gpuHistory, stats.gpu)
                remember(fpsHistory, stats.fps)
                val lines = mutableListOf("NEX TUNER  /  GAMEBAR")
                if (prefs.getBoolean("show_fps", true)) {
                    lines += "FPS  ${stats.fps}   FRAME ${stats.frameTime}"
                    if (fpsHistory.isNotEmpty()) lines += "FPS  ${graph(fpsHistory)}"
                }
                if (prefs.getBoolean("show_cpu", true)) {
                    lines += "CPU  ${stats.cpu}   CLK  ${stats.cpuClock}"
                    if (cpuHistory.isNotEmpty()) lines += "CPU  ${graph(cpuHistory)}"
                }
                if (prefs.getBoolean("show_temps", true)) lines += "CPU TEMP  ${stats.cpuTemp}"
                if (prefs.getBoolean("show_gpu", true)) {
                    lines += "GPU  ${stats.gpu}   CLK  ${stats.gpuClock}"
                    if (gpuHistory.isNotEmpty()) lines += "GPU  ${graph(gpuHistory)}"
                }
                if (prefs.getBoolean("show_temps", true)) lines += "GPU TEMP  ${stats.gpuTemp}   BAT  ${stats.battery}"
                lines += "RAM  ${stats.ram}   SPEED  ${stats.ramSpeed}   TEMP  ${stats.ramTemp}"
                lines += "BATTERY  ${stats.batteryLevel}%   POWER  ${stats.powerWatt}W   APP RAM  ${stats.appRamUsage} MB"
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

    private fun readNode(path: String): String? {
        val local = runCatching {
            val file = File(path)
            if (file.isFile && file.canRead()) file.readText().trim() else null
        }.getOrNull()
        return local?.takeIf { it.isNotEmpty() } ?: root("cat '$path'")
    }

    private fun readSnapshot(packageName: String): GameBarSnapshot {
        val cpuLines = readNode("/proc/stat")?.lineSequence()?.firstOrNull()?.trim()?.split(Regex("\\s+"))
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

        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val policies = policyRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("policy") }.orEmpty()
        val clocks = policies.mapNotNull { readNode("${it.absolutePath}/scaling_cur_freq")?.toLongOrNull() }.filter { it > 0 }
        val cpuClock = clocks.maxOrNull()?.let { "${it / 1000} MHz" } ?: "N/A"

        val thermalRoots = listOf(File("/sys/class/thermal"), File("/sys/devices/virtual/thermal"))
        val thermal = mutableListOf<Pair<String, Double>>()
        thermalRoots.flatMap { it.listFiles()?.toList().orEmpty() }.distinctBy { it.absolutePath }.forEach { zone ->
            val type = readNode("${zone.absolutePath}/type") ?: return@forEach
            val raw = readNode("${zone.absolutePath}/temp")?.toDoubleOrNull() ?: return@forEach
            thermal += type.lowercase(Locale.US) to raw
        }
        fun temperature(matches: List<String>): String {
            val raw = thermal.firstOrNull { (type, _) -> matches.any { type.contains(it) } }?.second ?: return "N/A"
            val magnitude = kotlin.math.abs(raw)
            val divider = when {
                magnitude in 20_000.0..150_000.0 -> 1000.0
                magnitude in 2_000.0..15_000.0 -> 100.0
                magnitude in 200.0..1_500.0 -> 10.0
                else -> 1.0
            }
            val celsius = raw / divider
            return if (celsius in -30.0..150.0) String.format(Locale.US, "%.1f°C", celsius) else "N/A"
        }
        val cpuTemp = temperature(listOf("cpu_therm", "cpuss", "cpu-1-0-usr", "soc-thermal", "cpu", "cluster"))
        val gpuTemp = listOf("/sys/class/kgsl/kgsl-3d0/temp").firstNotNullOfOrNull { path ->
            val raw = readNode(path)?.toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            val magnitude = kotlin.math.abs(raw)
            val divider = when {
                magnitude in 20_000.0..150_000.0 -> 1000.0
                magnitude in 2_000.0..15_000.0 -> 100.0
                magnitude in 200.0..1_500.0 -> 10.0
                else -> 1.0
            }
            val celsius = raw / divider
            if (celsius in -30.0..150.0) String.format(Locale.US, "%.1f°C", celsius) else null
        } ?: temperature(listOf("gpuss", "gpu", "adreno", "mali", "sgx"))
        val ramTemp = temperature(listOf("ddr", "ram", "dram", "mch", "pop"))

        val devfreqDirs = File("/sys/class/devfreq").listFiles()?.filter { it.isDirectory }.orEmpty()
        val gpuBase = (devfreqDirs + File("/sys/class/kgsl/kgsl-3d0")).firstOrNull { dir ->
            val label = (readNode("${dir.absolutePath}/name") ?: dir.name).lowercase(Locale.US)
            listOf("gpu", "adreno", "mali", "kgsl", "panfrost", "powervr").any { label.contains(it) || dir.name.lowercase(Locale.US).contains(it) }
        }?.absolutePath
        val gpuFreqPath = listOfNotNull(
            gpuBase?.let { "$it/cur_freq" },
            gpuBase?.let { "$it/devfreq/cur_freq" },
            "/sys/class/kgsl/kgsl-3d0/gpuclk"
        ).firstOrNull { readNode(it)?.toLongOrNull()?.let { value -> value > 0 } == true }
        val gpuFreqRaw = gpuFreqPath?.let { readNode(it)?.toLongOrNull() }
        val gpuFreq = gpuFreqRaw?.let { raw ->
            val mhz = when { raw > 100_000_000L -> raw / 1_000_000L; raw > 100_000L -> raw / 1_000L; else -> raw }
            "$mhz MHz"
        } ?: "N/A"

        val gpuUsagePaths = listOfNotNull(
            gpuBase?.let { "$it/gpu_busy_percentage" },
            gpuBase?.let { "$it/gpubusy" },
            gpuBase?.let { "$it/load" },
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy"
        ).distinct()
        val gpuLoad = gpuUsagePaths.firstNotNullOfOrNull { path ->
            val raw = readNode(path) ?: return@firstNotNullOfOrNull null
            val nums = Regex("\\d+").findAll(raw).mapNotNull { it.value.toLongOrNull() }.toList()
            val percent = when {
                raw.contains("%") && nums.isNotEmpty() -> nums.first()
                nums.size >= 2 && nums[1] > 0 -> nums[0] * 100 / nums[1]
                nums.size == 1 -> nums[0]
                else -> return@firstNotNullOfOrNull null
            }
            percent.takeIf { it in 0L..100L }?.let { "$it%" }
        } ?: "N/A"

        // Known DRM/DSI FPS nodes observed in SleepX Manager are optional fallbacks only.
        // Runtime DRM discovery remains first-class; unavailable nodes are skipped on other ROMs/SoCs.
        val fpsCandidates = mutableListOf(
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/sde-crtc-1/measured_fps",
            "/sys/class/drm/sde-crtc-2/measured_fps",
            "/sys/class/drm/sde-crtc-3/measured_fps",
            "/sys/class/drm/card0-DSI-1/measured_fps",
            "/sys/class/drm/card0/card0-sde-crtc-0/measured_fps",
            "/sys/class/drm/card0/card0-sde-crtc-1/measured_fps",
            "/sys/class/drm/card0/card0-sde-crtc-2/measured_fps",
            "/sys/class/drm/card0/card0-sde-crtc-3/measured_fps",
            "/sys/class/drm/card0/fbc/fps",
            "/sys/class/graphics/fb0/measured_fps",
            "/sys/devices/virtual/graphics/fb0/measured_fps",
            "/sys/class/graphics/fb0/fps",
            "/sys/devices/platform/soc/soc:qcom,dsi-display/dfps"
        )
        fpsPathCache?.let { fpsCandidates.add(0, it) }
        if (fpsPathCache == null) {
            val drm = File("/sys/class/drm")
            drm.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name == "measured_fps" }?.absolutePath?.let {
                fpsCandidates.add(0, it)
                fpsPathCache = it
            }
        }
        val fpsRaw = fpsCandidates.distinct().firstNotNullOfOrNull { path ->
            readNode(path)?.let { Regex("[0-9]+(?:\\\\.[0-9]+)?").find(it)?.value?.toDoubleOrNull() }?.takeIf { it in 1.0..360.0 }
        }
        val fps = fpsRaw?.let { String.format(Locale.US, "%.0f", it) } ?: "N/A"
        val frameTime = fpsRaw?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2fms", 1000.0 / it) } ?: "N/A"

        val mem = readNode("/proc/meminfo")?.lines().orEmpty()
        val total = mem.firstOrNull { it.startsWith("MemTotal:") }?.filter { it.isDigit() }?.toLongOrNull()
        val available = mem.firstOrNull { it.startsWith("MemAvailable:") }?.filter { it.isDigit() }?.toLongOrNull()
        val ram = if (total != null && available != null && total > 0) "${((total - available) * 100 / total)}%" else "N/A"

        val ramFreqCandidates = mutableListOf("/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq")
        devfreqDirs.forEach { dir ->
            val label = (readNode("${dir.absolutePath}/name") ?: dir.name).lowercase(Locale.US)
            if (listOf("ddr", "dram", "memory", "mem").any { label.contains(it) }) {
                ramFreqCandidates += "${dir.absolutePath}/cur_freq"
            }
        }
        val ramFreqRaw = ramFreqCandidates.firstNotNullOfOrNull { path -> readNode(path)?.toLongOrNull()?.takeIf { it > 0 } }
        val ramSpeed = ramFreqRaw?.let { raw ->
            val mhz = when { raw > 100_000_000L -> raw / 1_000_000L; raw > 100_000L -> raw / 1_000L; else -> raw }
            "$mhz MHz"
        } ?: "N/A"

        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryTempBroadcast = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val batteryTemp = if (batteryTempBroadcast != null && batteryTempBroadcast != Int.MIN_VALUE) {
            String.format(Locale.US, "%.1f°C", batteryTempBroadcast / 10.0)
        } else {
            temperature(listOf("battery", "bms"))
        }
        val batteryLevel = batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) "${(level * 100f / scale).toInt().coerceIn(0, 100)}" else "N/A"
        } ?: "N/A"
        val batteryManager = getSystemService(BatteryManager::class.java)
        val currentUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val powerWatt = if (currentUa != Int.MIN_VALUE && currentUa != 0 && voltageMv > 0) {
            String.format(Locale.US, "%.3f", kotlin.math.abs(currentUa) / 1_000_000.0 * voltageMv / 1000.0)
        } else "N/A"

        val appRamUsage = runCatching {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pids = manager.runningAppProcesses?.filter { it.processName == packageName }?.map { it.pid }?.toIntArray()
            if (pids == null || pids.isEmpty()) "N/A" else {
                val pssKb = manager.getProcessMemoryInfo(pids).sumOf { it.totalPss }
                if (pssKb > 0) (pssKb / 1024).toString() else "N/A"
            }
        }.getOrDefault("N/A")

        return GameBarSnapshot(
            fps = fps, cpu = cpuUsage, cpuClock = cpuClock, cpuTemp = cpuTemp,
            gpu = gpuLoad, gpuClock = gpuFreq, gpuTemp = gpuTemp, ram = ram,
            battery = batteryTemp, frameTime = frameTime, ramSpeed = ramSpeed,
            ramTemp = ramTemp, batteryLevel = batteryLevel, powerWatt = powerWatt,
            appRamUsage = appRamUsage
        )
    }

    private fun appendLog(stats: GameBarSnapshot, packageName: String) {
        runCatching {
            val dir = File(filesDir, "GameBarLogs").apply { mkdirs() }
            val name = prefsLogName()
            val file = File(dir, name)
            if (!file.exists()) file.writeText("timestamp,package,fps,frame_time,battery_temp,cpu_usage,cpu_clock,cpu_temp,ram_usage,ram_speed,ram_temp,gpu_usage,gpu_clock,gpu_temp,battery_level,power_watt,app_ram_usage\n")
            val values = listOf(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                packageName, stats.fps, stats.frameTime, stats.battery, stats.cpu, stats.cpuClock, stats.cpuTemp,
                stats.ram, stats.ramSpeed, stats.ramTemp, stats.gpu, stats.gpuClock, stats.gpuTemp,
                stats.batteryLevel, stats.powerWatt, stats.appRamUsage
            ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
            file.appendText(values + "\n")
        }
    }

    private fun prefsLogName(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString("log_file", null)
        if (existing != null && File(filesDir, "GameBarLogs/$existing").exists()) return existing
        val name = "GameBar_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        prefs.edit().putString("log_file", name).apply()
        return name
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
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
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
