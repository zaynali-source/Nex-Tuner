package com.nex.tuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nex.tuner.core.DeviceSnapshot
import com.nex.tuner.core.Profile
import com.nex.tuner.core.RootController
import com.nex.tuner.core.UniversalTuningControls
import com.nex.tuner.game.GameSpaceScreen
import com.nex.tuner.gamebar.GameBarScreen
import com.nex.tuner.power.ThermalConfigScreen
import com.nex.tuner.power.ChargingControllerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0A1118)
private val Surface1 = Color(0xFF121F2B)
private val Surface2 = Color(0xFF203342)
private val TextMain = Color(0xFFEAF4FA)
private val TextMuted = Color(0xFF9CB2C1)
private val Accent = Color(0xFFB8E2F5)
private val Warn = Color(0xFFFFB4AB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexApp() {
    val root = remember { RootController() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<DeviceSnapshot?>(null) }
    var status by remember { mutableStateOf("Waiting for hardware scan") }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(Profile.NORMAL) }
    var page by remember { mutableStateOf("Profiles") }
    var showControls by remember { mutableStateOf(false) }

    fun scan() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { root.detect() } }
                .onSuccess { device = it; status = "Hardware scan complete" }
                .onFailure { status = it.message ?: "Root access unavailable" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { scan() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Bg,
            surface = Surface1,
            onSurface = TextMain,
            secondary = TextMuted
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                FoxBottomBar(page = page, onSelect = { page = it })
            }
        ) { inset ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inset),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Nex Tuner",
                            modifier = Modifier.weight(1f),
                            color = TextMain,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = (-0.5).sp
                        )
                        Box(
                            modifier = Modifier.size(46.dp).clip(CircleShape)
                                .clickable { showControls = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Tuning controls", tint = TextMain, modifier = Modifier.size(25.dp))
                        }
                    }
                }

                when (page) {
                    "Profiles" -> {
                        item {
                            SectionCard(title = "Performance profile") {
                                Text("Choose how the device should respond.", color = TextMuted, fontSize = 13.sp)
                                Spacer(Modifier.height(14.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Profile.entries.chunked(2).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { profile ->
                                                ProfilePill(
                                                    profile = profile,
                                                    selected = active == profile,
                                                    enabled = !busy,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    busy = true
                                                    scope.launch {
                                                        runCatching {
                                                            withContext(Dispatchers.IO) {
                                                                val snapshot = device ?: root.detect()
                                                                val base = root.apply(profile, snapshot)
                                                                val tuningPrefs = context.getSharedPreferences("nex_profile_tuning", android.content.Context.MODE_PRIVATE)
                                                                val priorities = snapshot.policies.associate { policy ->
                                                                    policy.path to tuningPrefs.getInt("${profile.name}.priority.${policy.path}", 2).coerceIn(0, 3)
                                                                }
                                                                val threshold = tuningPrefs.getInt("${profile.name}.threshold", root.defaultThreshold(profile, snapshot.ulqKernel)).coerceIn(0, 20)
                                                                val frequencyCaps = snapshot.policies.associate { policy ->
                                                                    policy.path to tuningPrefs.getLong("${profile.name}.freqcap.${policy.path}", root.defaultFrequencyCap(policy, profile)).coerceIn(policy.minKHz, policy.maxKHz)
                                                                }
                                                                val tuning = root.applyProfileTuning(profile, snapshot, priorities, threshold, frequencyCaps)
                                                                listOf(base, tuning.summary()).filter { it.isNotBlank() }.joinToString(" · ")
                                                            }
                                                        }.onSuccess {
                                                            status = it
                                                            active = profile
                                                            runCatching { withContext(Dispatchers.IO) { root.detect() } }.onSuccess { device = it }
                                                        }.onFailure { status = it.message ?: "Could not apply profile" }
                                                        busy = false
                                                    }
                                                }
                                            }
                                            if (row.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            SectionCard(title = "Device") {
                                Text(device?.model ?: "Android device", fontSize = 23.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(5.dp))
                                Text(device?.kernel ?: "Kernel information unavailable", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    InfoPill("${device?.policies?.size ?: 0} CPU policies", Modifier.weight(1f))
                                    InfoPill(if (device?.gpuPath != null) "GPU" else "No GPU", Modifier.weight(1f))
                                    InfoPill(if (device?.ulqKernel == true) "Ulquiorra" else "Generic", Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            SectionCard(title = "System status") {
                                if (busy) LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                                    color = TextMain,
                                    trackColor = Surface2
                                )
                                Text(status, fontSize = 14.sp, color = TextMain)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "Root required  ·  Unsupported nodes are skipped  ·  No kernel module installed",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                        item {
                            Text(
                                "Independent project. Ulquiorra references remain separate from Nex Tuner's generic fallback.",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                color = TextMuted,
                                fontSize = 11.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    "Kernel" -> {
                        item {
                            SectionCard(title = "Per-profile tuning") {
                                UniversalTuningControls(snapshot = device, root = root, profile = active, onStatus = { status = it })
                            }
                        }
                        item {
                            SectionCard(title = "CPU policies") {
                                if (device?.policies.isNullOrEmpty()) {
                                    Text("No readable CPUFreq policies detected.", color = TextMuted, fontSize = 13.sp)
                                } else {
                                    device!!.policies.forEachIndexed { index, policy ->
                                        if (index > 0) HorizontalDivider(color = Surface2, modifier = Modifier.padding(vertical = 10.dp))
                                        Text(policy.path.substringAfterLast('/').uppercase(), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        Spacer(Modifier.height(4.dp))
                                        Text("${policy.minKHz / 1000} – ${policy.maxKHz / 1000} MHz", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                        Text("CPUs: ${policy.relatedCpus}", color = TextMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        item {
                            SectionCard(title = "Graphics") {
                                Text(device?.gpuPath ?: "GPU devfreq interface not detected", color = if (device?.gpuPath == null) TextMuted else TextMain, fontSize = 13.sp)
                                if (device?.gpuPath != null) {
                                    Text("GPU limits: ${device?.gpuMin ?: "?"} – ${device?.gpuMax ?: "?"}", color = TextMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    "Game" -> {
                        item { GameSpaceScreen() }
                    }

                    "GameBar" -> {
                        item { GameBarScreen() }
                    }

                    "Thermals" -> { item { ThermalConfigScreen() } }
                    "Charging" -> { item { ChargingControllerScreen() } }

                    else -> {
                        item {
                            SectionCard(title = "Application") {
                                SettingRow("Hardware scan", "Refresh CPU, GPU and kernel interfaces") {
                                    scan()
                                }
                                HorizontalDivider(color = Surface2)
                                SettingRow("Active profile", active.title, {})
                                HorizontalDivider(color = Surface2)
                                SettingRow("Root access", if (status.contains("complete", true)) "Available for scan" else "Check system status", {})
                                HorizontalDivider(color = Surface2)
                                SettingRow("Thermal Configs", "Ulquiorra sconfig interface") { page = "Thermals" }
                                HorizontalDivider(color = Surface2)
                                SettingRow("Charging Controller", "Supported charge stop threshold") { page = "Charging" }
                            }
                        }
                        item {
                            SectionCard(title = "About Nex Tuner") {
                                Text("Standalone performance manager", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(5.dp))
                                Text("Built independently. Device-specific controls are enabled only when supported interfaces and verified values are available.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
        }

        if (showControls) {
            ModalBottomSheet(
                onDismissRequest = { showControls = false },
                containerColor = Surface1,
                contentColor = TextMain,
                dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                    Text("Quick controls", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(5.dp))
                    Text("Nex Tuner device actions", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    SettingRow("Rescan hardware", "Refresh CPU policies and GPU detection") {
                        showControls = false
                        scan()
                    }
                    HorizontalDivider(color = Surface2)
                    SettingRow("Current profile", active.title, {})
                    HorizontalDivider(color = Surface2)
                    SettingRow("Kernel", device?.kernel ?: "Not detected", {})
                }
            }
        }
    }
}

@Composable
private fun FoxBottomBar(page: String, onSelect: (String) -> Unit) {
    val entries = listOf(
        "Profiles" to Icons.Rounded.Speed,
        "Kernel" to Icons.Rounded.Memory,
        "Game" to Icons.Rounded.Gamepad,
        "GameBar" to Icons.Rounded.Speed,
        "More" to Icons.Rounded.MoreHoriz
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp, top = 6.dp)
            .clip(RoundedCornerShape(30.dp)).background(Color(0xE612202B))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { (label, icon) ->
            val selected = page == label
            Column(
                modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (selected) Accent else Color.Transparent)
                    .clickable { onSelect(label) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = label, tint = if (selected) Color(0xFF10202B) else TextMuted, modifier = Modifier.size(18.dp))
                Text(label, color = if (selected) Color(0xFF10202B) else TextMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Surface1).padding(horizontal = 18.dp, vertical = 17.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title.uppercase(), color = TextMuted, fontSize = 10.sp, letterSpacing = 1.3.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun ProfilePill(profile: Profile, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val icon = when (profile) {
        Profile.BATTERY -> Icons.Rounded.BatteryFull
        Profile.NORMAL -> Icons.Rounded.Tune
        Profile.PERFORMANCE -> Icons.Rounded.Speed
        Profile.EXTREME -> Icons.Rounded.Bolt
    }
    Column(
        modifier = modifier.heightIn(min = 108.dp).clip(RoundedCornerShape(26.dp))
            .background(if (selected) Accent else Surface1)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Color(0xFF10202B) else TextMain, modifier = Modifier.size(22.dp))
        Text(profile.title, color = if (selected) Color(0xFF10202B) else TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            profile.subtitle,
            color = if (selected) Color(0xFF456171) else TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) Text("ACTIVE", color = Color(0xFF456171), fontSize = 9.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InfoPill(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(38.dp).clip(RoundedCornerShape(20.dp)).background(Surface2).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextMain, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 14.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = TextMuted, modifier = Modifier.size(17.dp))
    }
}
