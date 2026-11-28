package com.nex.tuner.gamebar

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.ceil

private val GameBarInk = Color(0xFFEAF4FA)
private val GameBarMuted = Color(0xFF9CB2C1)
private val GameBarPanel = Color(0xFF142330)
private val GameBarBlue = Color(0xFF9AD8F2)

private data class GameBarSample(
    val timestamp: String,
    val packageName: String,
    val fps: Double?,
    val frameTime: Double?,
    val cpu: Double?,
    val cpuClock: Double?,
    val cpuTemp: Double?,
    val gpu: Double?,
    val gpuClock: Double?,
    val gpuTemp: Double?,
    val ram: Double?,
    val batteryTemp: Double?,
    val ramSpeed: Double?,
    val ramTemp: Double?,
    val batteryLevel: Double?,
    val powerWatt: Double?,
    val appRamUsage: Double?
)

private data class GameBarSession(
    val file: File,
    val samples: List<GameBarSample>
) {
    val averageFps: Double? get() = samples.mapNotNull { it.fps }.takeIf { it.isNotEmpty() }?.average()
    val duration: String get() = if (samples.size < 2) "Short session" else "${samples.size} samples"
}

private data class GameBarMetric(val label: String, val key: String, val suffix: String)

private val gameBarMetrics = listOf(
    GameBarMetric("FPS", "fps", " FPS"),
    GameBarMetric("Frame time", "frameTime", " ms"),
    GameBarMetric("CPU load", "cpu", "%"),
    GameBarMetric("CPU clock", "cpuClock", " MHz"),
    GameBarMetric("CPU temperature", "cpuTemp", " °C"),
    GameBarMetric("GPU load", "gpu", "%"),
    GameBarMetric("GPU clock", "gpuClock", " MHz"),
    GameBarMetric("GPU temperature", "gpuTemp", " °C"),
    GameBarMetric("RAM usage", "ram", "%"),
    GameBarMetric("RAM speed", "ramSpeed", " MHz"),
    GameBarMetric("RAM temperature", "ramTemp", " °C"),
    GameBarMetric("Battery temperature", "batteryTemp", " °C"),
    GameBarMetric("Battery level", "batteryLevel", "%"),
    GameBarMetric("Battery power", "powerWatt", " W"),
    GameBarMetric("App RAM", "appRamUsage", " MB")
)

private fun parseCsv(line: String): List<String> {
    val values = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                cell.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                values += cell.toString()
                cell.clear()
            }
            else -> cell.append(char)
        }
        index++
    }
    values += cell.toString()
    return values
}

private fun parseNumber(value: String?): Double? {
    if (value.isNullOrBlank() || value.equals("N/A", true)) return null
    return Regex("-?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
}

private fun loadGameBarSessions(context: android.content.Context): List<GameBarSession> {
    val dir = File(context.filesDir, "GameBarLogs")
    return dir.listFiles { file -> file.isFile && file.extension.equals("csv", true) }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { file ->
            runCatching {
                val lines = file.readLines()
                if (lines.size < 2) return@runCatching null
                val header = parseCsv(lines.first()).map { it.trim().lowercase(Locale.US) }
                fun get(cells: List<String>, name: String, oldIndex: Int? = null): String? {
                    val index = header.indexOf(name)
                    return cells.getOrNull(if (index >= 0) index else oldIndex ?: -1)
                }
                val samples = lines.drop(1).mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val cells = parseCsv(line)
                    GameBarSample(
                        timestamp = get(cells, "timestamp", 0).orEmpty(),
                        packageName = get(cells, "package", 1).orEmpty(),
                        fps = parseNumber(get(cells, "fps", 2)),
                        frameTime = parseNumber(get(cells, "frame_time", 3)),
                        cpu = parseNumber(get(cells, "cpu_usage", if (header.contains("frame_time")) 4 else 3)),
                        cpuClock = parseNumber(get(cells, "cpu_clock", if (header.contains("frame_time")) 5 else 4)),
                        cpuTemp = parseNumber(get(cells, "cpu_temp", if (header.contains("frame_time")) 6 else 5)),
                        gpu = parseNumber(get(cells, "gpu_usage", if (header.contains("frame_time")) 7 else 6)),
                        gpuClock = parseNumber(get(cells, "gpu_clock", if (header.contains("frame_time")) 8 else 7)),
                        gpuTemp = parseNumber(get(cells, "gpu_temp", if (header.contains("frame_time")) 9 else 8)),
                        ram = parseNumber(get(cells, "ram_usage", if (header.contains("frame_time")) 10 else 9)),
                        batteryTemp = parseNumber(get(cells, "battery_temp", if (header.contains("frame_time")) 4 else 10)),
                        ramSpeed = parseNumber(get(cells, "ram_speed")),
                        ramTemp = parseNumber(get(cells, "ram_temp")),
                        batteryLevel = parseNumber(get(cells, "battery_level")),
                        powerWatt = parseNumber(get(cells, "power_watt")),
                        appRamUsage = parseNumber(get(cells, "app_ram_usage"))
                    )
                }
                GameBarSession(file, samples)
            }.getOrNull()
        }
}

private fun GameBarSample.metric(key: String): Double? = when (key) {
    "fps" -> fps
    "frameTime" -> frameTime
    "cpu" -> cpu
    "cpuClock" -> cpuClock
    "cpuTemp" -> cpuTemp
    "gpu" -> gpu
    "gpuClock" -> gpuClock
    "gpuTemp" -> gpuTemp
    "ram" -> ram
    "ramSpeed" -> ramSpeed
    "ramTemp" -> ramTemp
    "batteryTemp" -> batteryTemp
    "batteryLevel" -> batteryLevel
    "powerWatt" -> powerWatt
    "appRamUsage" -> appRamUsage
    else -> null
}

@Composable
fun GameBarRecordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<GameBarSession>>(emptyList()) }
    var selected by remember { mutableStateOf<GameBarSession?>(null) }
    var selectedMetric by remember { mutableStateOf("fps") }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) { loadGameBarSessions(context) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val file = selected?.file
        if (uri != null && file != null && file.exists()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A1118)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { if (selected != null) selected = null else onBack() }) {
                Text("BACK")
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (selected == null) "GameBar records" else selected!!.file.nameWithoutExtension, color = GameBarInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("Session graphs and raw CSV data", color = GameBarMuted, fontSize = 11.sp)
            }
        }

        if (selected == null) {
            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No sessions yet. Start CSV recording from GameBar, play for a while, then stop it.", color = GameBarMuted)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sessions, key = { it.file.absolutePath }) { session ->
                        Card(
                            onClick = { selected = session },
                            colors = CardDefaults.cardColors(containerColor = GameBarPanel),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(session.file.nameWithoutExtension, color = GameBarInk, fontWeight = FontWeight.SemiBold)
                                Text(session.samples.firstOrNull()?.packageName?.ifBlank { "Unknown app" } ?: "Unknown app", color = GameBarMuted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Avg FPS  ${session.averageFps?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"}", color = GameBarBlue, fontSize = 12.sp)
                                    Text(session.duration, color = GameBarMuted, fontSize = 12.sp)
                                }
                                Text("Tap to open charts and CSV preview", color = GameBarMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        } else {
            val session = selected!!
            val metric = gameBarMetrics.first { it.key == selectedMetric }
            val points = session.samples.mapNotNull { sample -> sample.metric(metric.key) }
            val average = points.takeIf { it.isNotEmpty() }?.average()
            val minimum = points.minOrNull()
            val maximum = points.maxOrNull()
            val sortedPoints = points.sorted()
            val onePercentLow = sortedPoints.take(max(1, ceil(sortedPoints.size * 0.01).toInt())).takeIf { it.isNotEmpty() }?.average()
            val pointOnePercentLow = sortedPoints.take(max(1, ceil(sortedPoints.size * 0.001).toInt())).takeIf { it.isNotEmpty() }?.average()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                gameBarMetrics.forEach { item ->
                    if (selectedMetric == item.key) {
                        Button(onClick = { selectedMetric = item.key }) { Text(item.label) }
                    } else {
                        OutlinedButton(onClick = { selectedMetric = item.key }) { Text(item.label) }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = GameBarPanel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(metric.label, color = GameBarInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (points.isEmpty()) {
                        Text("This metric was not available in this session (N/A).", color = GameBarMuted, fontSize = 12.sp)
                    } else {
                        Text("AVG  ${String.format(Locale.US, "%.1f", average ?: 0.0)}${metric.suffix}     MIN  ${String.format(Locale.US, "%.1f", minimum ?: 0.0)}     MAX  ${String.format(Locale.US, "%.1f", maximum ?: 0.0)}", color = GameBarMuted, fontSize = 11.sp)
                        GameBarLineChart(values = points, suffix = metric.suffix)
                        if (selectedMetric == "fps") {
                            Text("1% LOW  ${String.format(Locale.US, "%.1f", onePercentLow ?: 0.0)} FPS     0.1% LOW  ${String.format(Locale.US, "%.1f", pointOnePercentLow ?: 0.0)} FPS", color = GameBarMuted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("CSV preview", color = GameBarInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Button(onClick = { exportLauncher.launch(session.file.name) }) { Text("EXPORT CSV") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = GameBarPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("Time", "Package", "FPS", "Frame ms", "Battery °C", "CPU %", "CPU MHz", "CPU °C", "RAM %", "RAM MHz", "RAM °C", "GPU %", "GPU MHz", "GPU °C", "Battery %", "Power W", "App RAM MB").forEach { title ->
                                Text(title, color = GameBarBlue, fontSize = 10.sp, modifier = Modifier.width(88.dp))
                            }
                        }
                    }
                    items(session.samples.take(250)) { sample ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(
                                sample.timestamp.substringAfterLast(" ").takeLast(8),
                                sample.packageName,
                                sample.fps?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.frameTime?.let { "%.2f".format(Locale.US, it) } ?: "N/A",
                                sample.batteryTemp?.let { "%.1f".format(Locale.US, it) } ?: "N/A",
                                sample.cpu?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.cpuClock?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.cpuTemp?.let { "%.1f".format(Locale.US, it) } ?: "N/A",
                                sample.ram?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.ramSpeed?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.ramTemp?.let { "%.1f".format(Locale.US, it) } ?: "N/A",
                                sample.gpu?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.gpuClock?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.gpuTemp?.let { "%.1f".format(Locale.US, it) } ?: "N/A",
                                sample.batteryLevel?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                                sample.powerWatt?.let { "%.2f".format(Locale.US, it) } ?: "N/A",
                                sample.appRamUsage?.let { "%.0f".format(Locale.US, it) } ?: "N/A"
                            ).forEach { value ->
                                Text(value, color = GameBarInk, fontSize = 10.sp, modifier = Modifier.width(88.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
            Text("Showing up to 250 samples in preview. Export CSV contains the full recording.", color = GameBarMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun GameBarLineChart(values: List<Double>, suffix: String) {
    val clean = values.filter { it.isFinite() }
    if (clean.size < 2) {
        Text("Not enough samples to draw a graph.", color = GameBarMuted, fontSize = 12.sp)
        return
    }
    val minValue = clean.minOrNull() ?: 0.0
    val maxValue = clean.maxOrNull() ?: 1.0
    val range = max(1.0, maxValue - minValue)
    Canvas(Modifier.fillMaxWidth().height(176.dp).padding(top = 10.dp, bottom = 8.dp)) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(Color(0xFF2A4050), Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val path = Path()
        clean.forEachIndexed { index, value ->
            val x = left + (right - left) * index / (clean.size - 1).toFloat()
            val y = bottom - ((value - minValue) / range).toFloat() * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, GameBarBlue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        val avg = clean.average()
        val avgY = bottom - ((avg - minValue) / range).toFloat() * (bottom - top)
        drawLine(Color(0xFF8BCB9B), Offset(left, avgY), Offset(right, avgY), 1.5.dp.toPx())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("MIN ${String.format(Locale.US, "%.1f", minValue)}", color = GameBarMuted, fontSize = 10.sp)
        Text("AVG ${String.format(Locale.US, "%.1f", clean.average())}", color = Color(0xFF8BCB9B), fontSize = 10.sp)
        Text("MAX ${String.format(Locale.US, "%.1f", maxValue)}", color = GameBarMuted, fontSize = 10.sp)
    }
}
