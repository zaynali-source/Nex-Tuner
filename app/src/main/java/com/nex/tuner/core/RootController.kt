package com.nex.tuner.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

enum class Profile(val title: String, val subtitle: String, val ceilingPercent: Int) {
    BATTERY("Battery", "Reduced peak frequency", 65),
    NORMAL("Normal", "Balanced adaptive limits", 85),
    PERFORMANCE("Performance", "Higher available headroom", 95),
    EXTREME("Extreme", "Highest detected headroom", 100)
}

data class CpuPolicy(
    val path: String,
    val relatedCpus: String,
    val minKHz: Long,
    val maxKHz: Long,
    val availableFrequencies: List<Long>
)

data class DeviceSnapshot(
    val model: String,
    val kernel: String,
    val policies: List<CpuPolicy>,
    val gpuPath: String?,
    val gpuMin: Long?,
    val gpuMax: Long?,
    val ulqKernel: Boolean
)

class RootController {
    private fun shell(command: String): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException(output.ifBlank { "Root command failed ($code)" })
        return output.trim()
    }

    private fun read(path: String): String? = runCatching {
        shell("test -r '$path' && cat '$path'")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun write(path: String, value: Long): Boolean = runCatching {
        shell("test -w '$path' && echo '$value' > '$path'")
        true
    }.getOrDefault(false)

    fun detect(): DeviceSnapshot {
        val model = read("/sys/devices/soc0/machine")
            ?: read("/proc/device-tree/model")?.replace("\u0000", "")
            ?: android.os.Build.MODEL
        val kernel = shell("uname -r")
        val policyPaths = shell("ls -d /sys/devices/system/cpu/cpufreq/policy[0-9]* 2>/dev/null || true")
            .lines().filter { it.startsWith("/sys/devices/system/cpu/cpufreq/policy") }
        val policies = policyPaths.mapNotNull { path ->
            val min = read("$path/cpuinfo_min_freq")?.toLongOrNull()
                ?: read("$path/scaling_min_freq")?.toLongOrNull()
            val max = read("$path/cpuinfo_max_freq")?.toLongOrNull()
                ?: read("$path/scaling_max_freq")?.toLongOrNull()
            if (min == null || max == null || max < min) null else CpuPolicy(
                path = path,
                relatedCpus = read("$path/related_cpus") ?: "?",
                minKHz = min,
                maxKHz = max,
                availableFrequencies = read("$path/scaling_available_frequencies")
                    ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            )
        }
        val gpuCandidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq",
            "/sys/class/devfreq"
        )
        var gpuPath: String? = null
        var gpuMin: Long? = null
        var gpuMax: Long? = null
        val kgsl = gpuCandidates.first()
        if (read("$kgsl/available_frequencies") != null || read("$kgsl/max_freq") != null) {
            gpuPath = kgsl
            gpuMin = read("$kgsl/min_freq")?.toLongOrNull()
            gpuMax = read("$kgsl/max_freq")?.toLongOrNull()
        }
        val ulq = read("/sys/kernel/ulquiorra/active_mode") != null &&
            read("/sys/kernel/ulquiorra/mode") != null
        return DeviceSnapshot(model, kernel, policies, gpuPath, gpuMin, gpuMax, ulq)
    }

    fun apply(profile: Profile, snapshot: DeviceSnapshot): String {
        if (snapshot.policies.isEmpty()) throw IllegalStateException("No readable CPUFreq policies found")
        val results = mutableListOf<String>()
        snapshot.policies.forEach { policy ->
            val target = (policy.maxKHz * profile.ceilingPercent / 100L)
                .coerceIn(policy.minKHz, policy.maxKHz)
            val path = "${policy.path}/scaling_max_freq"
            if (write(path, target)) results += "${policy.path.substringAfterLast('/')} max=${target}kHz"
            else results += "${policy.path.substringAfterLast('/')} skipped (not writable)"
        }
        // GPU and thermal controls are intentionally not changed until per-kernel
        // capability metadata and source-verified safe profile values are available.
        if (snapshot.gpuPath != null) results += "GPU detected; unchanged pending validated profile mapping"
        if (snapshot.ulqKernel) results += "Ulquiorra ABI detected; custom mode protocol not invoked in this alpha"
        return "${profile.title} requested. " + results.joinToString(" · ")
    }
}
