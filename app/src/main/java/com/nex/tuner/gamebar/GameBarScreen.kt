package com.nex.tuner.gamebar

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File

@Composable
fun GameBarScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(GameBarService.PREFS, android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var auto by remember { mutableStateOf(prefs.getBoolean("auto", false)) }
    var fps by remember { mutableStateOf(prefs.getBoolean("show_fps", true)) }
    var cpu by remember { mutableStateOf(prefs.getBoolean("show_cpu", true)) }
    var gpu by remember { mutableStateOf(prefs.getBoolean("show_gpu", true)) }
    var temps by remember { mutableStateOf(prefs.getBoolean("show_temps", true)) }
    var interval by remember { mutableStateOf(prefs.getLong("interval", 1000L)) }
    var recording by remember { mutableStateOf(prefs.getBoolean("recording", false)) }
    var showRecords by remember { mutableStateOf(false) }

    if (showRecords) {\n        GameBarRecordsScreen(onBack = { showRecords = false })
        return
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val name = prefs.getString("log_file", null)
            val source = name?.let { File(context.filesDir, "GameBarLogs/$it") }
            if (source != null && source.exists()) runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            }
        }
    }

    fun requestPermission() {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun launchService() {
        if (!Settings.canDrawOverlays(context)) { requestPermission(); return }
        ContextCompat.startForegroundService(context, Intent(context, GameBarService::class.java))
    }
    fun start() {
        prefs.edit().putBoolean("enabled", true).apply()
        enabled = true
        launchService()
    }
    fun stop() {
        prefs.edit().putBoolean("enabled", false).apply()
        enabled = false
        if (!auto) context.stopService(Intent(context, GameBarService::class.java))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LIVE PERFORMANCE OVERLAY", color = Color(0xFF8F969F), fontSize = 10.sp, letterSpacing = 1.2.sp)
                Text("GameBar", color = Color.White, fontSize = 27.sp)
                Text("FPS, CPU/GPU load and clocks, thermal zones, RAM and battery temperature. Hardware-dependent values show N/A when the kernel does not expose a readable source.", color = Color(0xFF9A9A9A), fontSize = 12.sp, lineHeight = 18.sp)
                Text(if (Settings.canDrawOverlays(context)) "Overlay permission granted" else "Overlay permission required", color = if (Settings.canDrawOverlays(context)) Color(0xFF8ED7A2) else Color(0xFFFFB86B), fontSize = 12.sp)
                Button(onClick = { if (enabled) stop() else start() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (enabled) "STOP GAMEBAR" else "ENABLE GAMEBAR")
                }
                Button(onClick = { requestPermission() }, modifier = Modifier.fillMaxWidth()) { Text("GRANT / CHECK OVERLAY ACCESS") }\n                Button(onClick = { showRecords = true }, modifier = Modifier.fillMaxWidth()) { Text("VIEW GRAPHS & CSV SESSIONS") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BEHAVIOUR", color = Color(0xFF8F969F), fontSize = 10.sp, letterSpacing = 1.2.sp)
                ToggleLine("Auto show for Game Space apps", "Uses the enabled app list in Game Space", auto) {
                    auto = it; prefs.edit().putBoolean("auto", it).apply()
                    if (it) launchService() else if (!enabled) context.stopService(Intent(context, GameBarService::class.java))
                }
                ToggleLine("FPS counter", "Uses a readable display FPS sysfs source", fps) { fps = it; prefs.edit().putBoolean("show_fps", it).apply() }
                ToggleLine("CPU metrics", "CPU load and maximum policy clock", cpu) { cpu = it; prefs.edit().putBoolean("show_cpu", it).apply() }
                ToggleLine("GPU metrics", "KGSL load and clock when exposed", gpu) { gpu = it; prefs.edit().putBoolean("show_gpu", it).apply() }
                ToggleLine("Temperature metrics", "CPU, GPU and battery temperatures", temps) { temps = it; prefs.edit().putBoolean("show_temps", it).apply() }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SESSION RECORDING", color = Color(0xFF8F969F), fontSize = 10.sp, letterSpacing = 1.2.sp)
                Text(if (recording) "Recording live telemetry to a CSV session" else "Record FPS, CPU/GPU, temperatures and RAM samples", color = Color.White, fontSize = 13.sp)\n                Text("Each recording is saved as a separate session. Open Graphs & CSV to inspect charts, sample rows and export the raw file.", color = Color(0xFF9A9A9A), fontSize = 11.sp, lineHeight = 16.sp)
                Button(onClick = {
                    if (recording) {
                        recording = false
                        prefs.edit().putBoolean("recording", false).apply()
                    } else {
                        prefs.edit().remove("log_file").putLong("recording_started", System.currentTimeMillis()).putBoolean("recording", true).putBoolean("enabled", true).apply()
                        recording = true
                        enabled = true
                        launchService()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (recording) "STOP RECORDING" else "START CSV RECORDING") }
                Button(onClick = {
                    val name = prefs.getString("log_file", null) ?: "Nex_GameBar.csv"
                    exportLauncher.launch(name)
                }, modifier = Modifier.fillMaxWidth(), enabled = prefs.getString("log_file", null) != null) { Text("EXPORT LAST CSV SESSION") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("REFRESH RATE", color = Color(0xFF8F969F), fontSize = 10.sp, letterSpacing = 1.2.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500L, 1000L, 2000L).forEach { value ->
                        Button(onClick = { interval = value; prefs.edit().putLong("interval", value).apply() }) {
                            Text(if (interval == value) "${value}ms ✓" else "${value}ms")
                        }
                    }
                }
                Text("The overlay can be dragged. Tap it to collapse/expand. Root access is used only to read kernel metrics; no tuning values are changed by GameBar.", color = Color(0xFF9A9A9A), fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ToggleLine(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFF9A9A9A), fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
