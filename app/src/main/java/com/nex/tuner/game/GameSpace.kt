package com.nex.tuner.game

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val PREFS = "nex_game_space"
private val bg = Color(0xFF151515)
private val muted = Color(0xFF9A9A9A)
private val green = Color(0xFFC7D8B8)

data class GameApp(val packageName: String, val label: String, val isGame: Boolean)

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
private fun key(pkg: String, name: String) = "game.$pkg.$name"

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}

private fun installedApps(context: Context): List<GameApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName }
        .map {
            GameApp(
                it.packageName,
                pm.getApplicationLabel(it).toString(),
                it.category == ApplicationInfo.CATEGORY_GAME
            )
        }
        .sortedWith(compareByDescending<GameApp> { it.isGame }.thenBy { it.label.lowercase() })
}

@Composable
fun GameSpaceScreen() {
    val context = LocalContext.current
    val store = remember { prefs(context) }
    var apps by remember { mutableStateOf(installedApps(context)) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var usageAllowed by remember { mutableStateOf(hasUsageAccess(context)) }
    var monitorOn by remember { mutableStateOf(GameMonitorService.isRunning) }
    var foreground by remember { mutableStateOf("Not detected") }
    var master by remember { mutableStateOf(store.getBoolean("master", false)) }

    LaunchedEffect(Unit) {
        while (true) {
            usageAllowed = hasUsageAccess(context)
            monitorOn = GameMonitorService.isRunning
            if (usageAllowed) foreground = GameForeground.current(context) ?: "No foreground app detected"
            delay(2000)
        }
    }

    val configured = apps.filter { !store.getBoolean(key(it.packageName, "hidden"), false) && store.getBoolean(key(it.packageName, "added"), it.isGame) }
    val activeCount = configured.count { store.getBoolean(key(it.packageName, "enabled"), false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GAME SPACE", color = green, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
            Text("Per-app gaming controls", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Medium)
            Text("Configure touch response for each game. Unsupported touch controls stay disabled.", color = muted, fontSize = 12.sp, lineHeight = 17.sp)
        }

        Card(colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Game Space", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Master switch for automatic per-app tuning", color = muted, fontSize = 12.sp)
                    }
                    Switch(checked = master, onCheckedChange = {
                        master = it
                        store.edit().putBoolean("master", it).apply()
                        if (!it) context.stopService(Intent(context, GameMonitorService::class.java))
                    })
                }
                HorizontalDivider(color = Color(0xFF303030))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Usage access", color = Color.White, fontSize = 14.sp)
                        Text(if (usageAllowed) "Granted — foreground app detection available" else "Required to detect which game is open", color = muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(if (usageAllowed) "MANAGE" else "GRANT") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Foreground app", color = Color.White, fontSize = 14.sp)
                        Text(foreground, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (monitorOn) "MONITOR ON" else "MONITOR OFF", color = if (monitorOn) green else muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (master && usageAllowed) {
                            context.startForegroundService(Intent(context, GameMonitorService::class.java))
                            monitorOn = true
                        }
                    },
                    enabled = master && usageAllowed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color(0xFF1C281B))
                ) { Text(if (monitorOn) "Monitoring enabled" else "Start automatic monitoring") }
                Text("Monitoring runs as a visible foreground service. Stop it by switching Game Space off.", color = muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your games", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("$activeCount enabled · ${configured.size} configured", color = muted, fontSize = 11.sp)
            }
            TextButton(onClick = { apps = installedApps(context); showPicker = true }) { Text("＋ ADD APP") }
        }

        configured.forEach { app ->
            val enabled = store.getBoolean(key(app.packageName, "enabled"), false)
            val expanded = selected == app.packageName
            Card(colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { selected = if (expanded) null else app.packageName }) {
                            Text(app.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(app.packageName, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (enabled) "Touch game mode enabled" else "Tap to configure", color = if (enabled) green else muted, fontSize = 11.sp)
                        }
                        Switch(checked = enabled, onCheckedChange = {
                            store.edit().putBoolean(key(app.packageName, "enabled"), it).apply()
                        })
                    }
                    if (expanded) {
                        HorizontalDivider(color = Color(0xFF303030))
                        var profile by remember(app.packageName) { mutableStateOf(store.getString(key(app.packageName, "profile"), "follow") ?: "follow") }
                        var expert by remember(app.packageName) { mutableStateOf(store.getBoolean(key(app.packageName, "expert"), false)) }
                        var superReport by remember(app.packageName) { mutableStateOf(store.getBoolean(key(app.packageName, "super"), true)) }
                        var preset by remember(app.packageName) { mutableFloatStateOf(store.getInt(key(app.packageName, "preset"), 1).toFloat()) }
                        var up by remember(app.packageName) { mutableFloatStateOf(store.getInt(key(app.packageName, "up"), 3).toFloat()) }
                        var tolerance by remember(app.packageName) { mutableFloatStateOf(store.getInt(key(app.packageName, "tolerance"), 2).toFloat()) }
                        var aim by remember(app.packageName) { mutableFloatStateOf(store.getInt(key(app.packageName, "aim"), 2).toFloat()) }
                        var tap by remember(app.packageName) { mutableFloatStateOf(store.getInt(key(app.packageName, "tap"), 2).toFloat()) }
                        var edge by remember(app.packageName) { mutableFloatStateOf(store.getInt(key(app.packageName, "edge"), 2).toFloat()) }

                        Text("Performance mode preference", color = green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Saved for future verified CPU-profile integration; this alpha applies touch controls only.", color = muted, fontSize = 10.sp)
                        var profileMenu by remember(app.packageName) { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { profileMenu = true }) {
                                Text("Selected: ${profile.replaceFirstChar { it.uppercase() }}  ▾", color = green)
                            }
                            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                                listOf("follow", "battery", "normal", "performance", "extreme").forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.replaceFirstChar { it.uppercase() }) },
                                        onClick = {
                                            profile = mode
                                            store.edit().putString(key(app.packageName, "profile"), mode).apply()
                                            profileMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Pre-tuned touch profile", color = Color.White, fontSize = 13.sp)
                                Text("Uses touch_filters preset levels 1–3", color = muted, fontSize = 10.sp)
                            }
                            Switch(checked = expert, onCheckedChange = { expert = it; store.edit().putBoolean(key(app.packageName, "expert"), it).apply() })
                        }
                        if (expert) {
                            Text("Preset level: ${preset.toInt()}", color = Color.White, fontSize = 12.sp)
                            Slider(value = preset, onValueChange = { preset = it }, onValueChangeFinished = { store.edit().putInt(key(app.packageName, "preset"), preset.toInt()).apply() }, valueRange = 1f..3f, steps = 1)
                        } else {
                            TuningSlider("Touch-up threshold", up, 0f..4f) { up = it; store.edit().putInt(key(app.packageName, "up"), it.toInt()).apply() }
                            TuningSlider("Jitter tolerance", tolerance, 0f..4f) { tolerance = it; store.edit().putInt(key(app.packageName, "tolerance"), it.toInt()).apply() }
                            TuningSlider("Aim sensitivity", aim, 0f..4f) { aim = it; store.edit().putInt(key(app.packageName, "aim"), it.toInt()).apply() }
                            TuningSlider("Tap stability", tap, 0f..4f) { tap = it; store.edit().putInt(key(app.packageName, "tap"), it.toInt()).apply() }
                            TuningSlider("Edge rejection", edge, 0f..3f) { edge = it; store.edit().putInt(key(app.packageName, "edge"), it.toInt()).apply() }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Super report rate", color = Color.White, fontSize = 13.sp)
                                Text("Enable the panel's bump_sample_rate option", color = muted, fontSize = 10.sp)
                            }
                            Switch(checked = superReport, onCheckedChange = { superReport = it; store.edit().putBoolean(key(app.packageName, "super"), it).apply() })
                        }
                        TextButton(onClick = {
                            store.edit().putBoolean(key(app.packageName, "hidden"), false).putBoolean(key(app.packageName, "added"), true).apply()
                            GameTouchController.apply(context, app.packageName)
                        }) { Text("APPLY TOUCH SETTINGS NOW") }
                        TextButton(onClick = {
                            store.edit().putBoolean(key(app.packageName, "hidden"), true).putBoolean(key(app.packageName, "added"), false).putBoolean(key(app.packageName, "enabled"), false).apply()
                            selected = null
                        }) { Text("REMOVE FROM GAME SPACE", color = Color(0xFFFF9A90)) }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val candidates = apps.filter { !store.getBoolean(key(it.packageName, "added"), it.isGame) }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add an app") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(candidates, key = { it.packageName }) { app ->
                        Text(
                            app.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                store.edit().putBoolean(key(app.packageName, "hidden"), false).putBoolean(key(app.packageName, "added"), true).apply()
                                showPicker = false
                            }.padding(vertical = 12.dp),
                            color = Color.White
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("CLOSE") } },
            containerColor = bg,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
private fun TuningSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row {
            Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value.toInt().toString(), color = green, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = (range.endInclusive - range.start).toInt() - 1)
    }
}

object GameTouchController {
    private const val PREFS = "nex_game_space"
    private fun runRoot(command: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) out else null
    }.getOrNull()

    private fun nodeCommand(variable: String, file: String) = "$variable='$file'"

    fun apply(context: Context, pkg: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = listOf("/sys/devices/virtual/touch/touch_dev", "/sys/class/touch/touch_dev")
        val filters = base.map { "$it/touch_filters" }.firstOrNull { runRoot("test -e '$it' && echo yes") == "yes" } ?: return false
        val game = base.map { "$it/game_mode" }.firstOrNull { runRoot("test -e '$it' && echo yes") == "yes" } ?: return false
        val bump = base.map { "$it/bump_sample_rate" }.firstOrNull { runRoot("test -e '$it' && echo yes") == "yes" }
        val backup = p.getBoolean("touch_backup_saved", false)
        if (!backup) {
            val oldFilters = runRoot("cat '$filters'") ?: return false
            val oldGame = runRoot("cat '$game'") ?: return false
            val oldBump = bump?.let { runRoot("cat '$it'") }
            p.edit().putString("touch_backup_filters", oldFilters)
                .putString("touch_backup_game", oldGame)
                .putString("touch_backup_bump", oldBump)
                .putString("touch_backup_filters_path", filters)
                .putString("touch_backup_game_path", game)
                .putString("touch_backup_bump_path", bump)
                .putBoolean("touch_backup_saved", true).apply()
        }
        val expert = p.getBoolean("game.$pkg.expert", false)
        val command = if (expert) {
            "echo '6 ${p.getInt("game.$pkg.preset", 1).coerceIn(1, 3)}' > '$filters'"
        } else {
            val up = p.getInt("game.$pkg.up", 3).coerceIn(0, 4)
            val tol = p.getInt("game.$pkg.tolerance", 2).coerceIn(0, 4)
            val aim = p.getInt("game.$pkg.aim", 2).coerceIn(0, 4)
            val tap = p.getInt("game.$pkg.tap", 2).coerceIn(0, 4)
            val edge = p.getInt("game.$pkg.edge", 2).coerceIn(0, 3)
            "echo '$up $tol $aim $tap $edge 0' > '$filters'"
        }
        val a = runRoot(command) != null
        val b = runRoot("echo 1 > '$game'") != null
        val superRate = if (p.getBoolean("game.$pkg.super", true)) 1 else 0
        val c = if (bump != null) runRoot("echo $superRate > '$bump'") != null else true
        return a && b && c
    }

    fun restore(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("touch_backup_saved", false)) return
        p.getString("touch_backup_filters_path", null)?.let { path ->
            p.getString("touch_backup_filters", null)?.let { value ->
                val safe = value.replace("'", "'\\''")
                runRoot("printf '%s' '$safe' > '$path'")
            }
        }
        p.getString("touch_backup_game_path", null)?.let { path ->
            p.getString("touch_backup_game", null)?.toIntOrNull()?.let { runRoot("echo $it > '$path'") }
        }
        p.getString("touch_backup_bump_path", null)?.let { path ->
            p.getString("touch_backup_bump", null)?.toIntOrNull()?.let { runRoot("echo $it > '$path'") }
        }
        p.edit().remove("touch_backup_saved").remove("touch_backup_filters").remove("touch_backup_game")
            .remove("touch_backup_bump").remove("touch_backup_filters_path").remove("touch_backup_game_path")
            .remove("touch_backup_bump_path").apply()
    }
}
