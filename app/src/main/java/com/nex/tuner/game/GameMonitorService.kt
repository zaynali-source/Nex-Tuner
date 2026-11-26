package com.nex.tuner.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

class GameMonitorService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var activePackage: String? = null

    private val poll = object : Runnable {
        override fun run() {
            runCatching {
                val store = getSharedPreferences("nex_game_space", MODE_PRIVATE)
                if (!store.getBoolean("master", false)) {
                    stopSelf()
                    return
                }
                val foreground = GameForeground.current(this@GameMonitorService)
                val enabled = foreground != null && store.getBoolean("game.$foreground.enabled", false)
                if (enabled && foreground != activePackage) {
                    if (activePackage != null) GameTouchController.restore(this@GameMonitorService)
                    activePackage = foreground
                    if (!GameTouchController.apply(this@GameMonitorService, foreground!!)) {
                        Log.w(TAG, "Touch backend unavailable; skipped for $foreground")
                        activePackage = null
                    }
                } else if (!enabled && activePackage != null) {
                    GameTouchController.restore(this@GameMonitorService)
                    activePackage = null
                }
            }.onFailure { Log.e(TAG, "Game monitor tick failed", it) }
            if (isRunning) handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        thread = HandlerThread(TAG).apply { start() }
        handler = Handler(thread.looper)
        isRunning = true
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        if (::handler.isInitialized) handler.removeCallbacks(poll)
        if (::thread.isInitialized) thread.quitSafely()
        GameTouchController.restore(this)
        activePackage = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game Space", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nex Tuner Game Space")
            .setContentText("Watching enabled apps and applying supported touch settings")
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "NexGameMonitor"
        private const val CHANNEL = "nex_game_space"
        private const val NOTIFICATION_ID = 7314
        @Volatile var isRunning: Boolean = false
            private set
    }
}

object GameForeground {
    fun current(context: android.content.Context): String? = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - 10_000L, now)
        val event = android.app.usage.UsageEvents.Event()
        var result: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                result = event.packageName
            }
        }
        result
    }.getOrNull()
}
