package com.nex.tuner.core

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UniversalTuningControls(
    snapshot: DeviceSnapshot?,
    root: RootController,
    profile: Profile,
    onStatus: (String) -> Unit
) {
    val device = snapshot ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("nex_profile_tuning", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var threshold by remember(profile) {
        mutableIntStateOf(prefs.getInt("${profile.name}.threshold", if (device.ulqKernel) 6 else 8).coerceIn(0, 20))
    }
    val priorities = remember(profile, device.policies) {
        mutableStateMapOf<String, Int>().apply {
            device.policies.forEach { policy ->
                put(policy.path, prefs.getInt("${profile.name}.priority.${policy.path}", 2).coerceIn(0, 3))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))) {
            Column(Modifier.fillMaxWidth().then(Modifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CLUSTER PRIORITY", color = Color.White, fontSize = 12.sp)
                Text("Adaptive core_ctl mapping on generic kernels · native Ulquiorra profile ABI when available", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                device.policies.forEach { policy ->
                    val value = priorities[policy.path] ?: 2
                    Text("${policy.path.substringAfterLast('/')} · CPUs ${policy.relatedCpus} · Priority $value", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { priorities[policy.path] = it.toInt().coerceIn(0, 3) },
                        valueRange = 0f..3f,
                        steps = 2
                    )
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FREQUENCY THRESHOLD", color = Color.White, fontSize = 12.sp)
                Text("$threshold / 20", color = Color.White, fontSize = 22.sp)
                Text(if (device.ulqKernel) "Ulquiorra native threshold ABI" else "Generic adaptive scheduler rate-limit mapping", color = Color(0xFF9A9A9A), fontSize = 11.sp)
                Slider(value = threshold.toFloat(), onValueChange = { threshold = it.toInt().coerceIn(0, 20) }, valueRange = 0f..20f, steps = 19)
            }
        }
        Button(
            onClick = {
                prefs.edit().putInt("${profile.name}.threshold", threshold).apply()
                priorities.forEach { (path, value) -> prefs.edit().putInt("${profile.name}.priority.$path", value).apply() }
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        root.applyProfileTuning(profile, device, priorities.toMap(), threshold)
                    }
                    onStatus(result.summary().ifBlank { "No compatible tuning nodes found" })
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("APPLY ${profile.title.uppercase()} TUNING") }
        Text("Values are saved per profile. Generic mappings are adaptive equivalents, not the Ulquiorra scheduler algorithm.", color = Color(0xFF9A9A9A), fontSize = 10.sp)
    }
}
