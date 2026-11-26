package com.nex.tuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nex.tuner.core.*
import kotlinx.coroutines.launch

private val Ink = Color(0xFF0A1018)
private val Panel = Color(0xFF131E2A)
private val Blue = Color(0xFFA8D8FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexApp() }
    }
}

@Composable
private fun NexApp() {
    val root = remember { RootController() }
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<DeviceSnapshot?>(null) }
    var status by remember { mutableStateOf("Scanning device interfaces…") }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(Profile.NORMAL) }

    LaunchedEffect(Unit) {
        runCatching { root.detect() }
            .onSuccess { device = it; status = "Hardware scan complete" }
            .onFailure { status = it.message ?: "Root access unavailable" }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Ink, surface = Panel, onSurface = Color(0xFFEAF3FC))) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("NEX TUNER", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("PERFORMANCE CONTROL", color = Blue, fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                        Icon(Icons.Rounded.Tune, null, tint = Blue, modifier = Modifier.size(28.dp))
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DEVICE OVERVIEW", color = Blue, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
                            Text(device?.model ?: "Android device", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(device?.kernel ?: "Kernel information unavailable", color = Color.LightGray, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = {}, label = { Text("${device?.policies?.size ?: 0} CPU policies") })
                                AssistChip(onClick = {}, label = { Text(if (device?.gpuPath != null) "GPU detected" else "GPU unavailable") })
                                AssistChip(onClick = {}, label = { Text(if (device?.ulqKernel == true) "Ulquiorra ABI" else "Generic kernel") })
                            }
                        }
                    }
                }
                item { Text("PERFORMANCE PROFILE", color = Blue, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Profile.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { profile ->
                                    val selected = active == profile
                                    Card(
                                        onClick = {
                                            if (!busy) {
                                                busy = true
                                                scope.launch {
                                                    runCatching { root.apply(profile, device ?: root.detect()) }
                                                        .onSuccess { status = it; active = profile; device = root.detect() }
                                                        .onFailure { status = it.message ?: "Could not apply profile" }
                                                    busy = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF20384C) else Panel),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Icon(
                                                when (profile) {
                                                    Profile.BATTERY -> Icons.Rounded.BatteryFull
                                                    Profile.NORMAL -> Icons.Rounded.Tune
                                                    Profile.PERFORMANCE -> Icons.Rounded.Speed
                                                    Profile.EXTREME -> Icons.Rounded.Bolt
                                                },
                                                null, tint = if (selected) Blue else Color.LightGray, modifier = Modifier.size(24.dp)
                                            )
                                            Text(profile.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                            Text(profile.subtitle, color = Color.LightGray, fontSize = 11.sp)
                                            if (selected) Text("SELECTED", color = Blue, fontSize = 9.sp, letterSpacing = 1.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("SYSTEM STATUS", color = Blue, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(status, color = Color(0xFFD5E2EF), fontSize = 13.sp)
                            Text("Root required · Unsupported nodes are skipped · No kernel module is installed", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }
                item {
                    Text(
                        "Independent project. Original Ulquiorra references are kept separate from Nex Tuner's generic fallback behavior.",
                        color = Color.Gray, fontSize = 10.sp
                    )
                }
            }
        }
    }
}
