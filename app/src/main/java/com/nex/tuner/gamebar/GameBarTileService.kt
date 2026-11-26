package com.nex.tuner.gamebar

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Build

class GameBarTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences(GameBarService.PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
        if (!enabled && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
            return
        }
        val next = !enabled
        prefs.edit().putBoolean("enabled", next).apply()
        if (next) {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, GameBarService::class.java))
            else startService(Intent(this, GameBarService::class.java))
        } else {
            stopService(Intent(this, GameBarService::class.java))
        }
        qsTile?.state = if (next) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        val enabled = getSharedPreferences(GameBarService.PREFS, MODE_PRIVATE).getBoolean("enabled", false)
        qsTile?.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
