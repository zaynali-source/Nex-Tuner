package com.nex.tuner.core

import java.io.BufferedReader
import java.io.InputStreamReader

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

data class TuningResult(
    val applied: List<String>,
    val skipped: List<String>
) {
    fun summary(): String = (applied + skipped).joinToString(" · ")
}

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

    private fun exists(path: String): Boolean = read(path) != null

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
                relatedCpus = read("$path/related_cpus") ?: read("$path/affected_cpus") ?: "?",
                minKHz = min,
                maxKHz = max,
                availableFrequencies = read("$path/scaling_available_frequencies")
                    ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.distinct()?.sorted()
                    ?: emptyList()
            )
        }
        val gpuCandidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq",
            "/sys/class/devfreq"
        )
        var gpuPath: String? = null
        var gpuMin: Long? = null
        var gpuMax: Long? = null
        for (candidate in gpuCandidates) {
            val available = read("$candidate/available_frequencies")
            val max = read("$candidate/max_freq")?.toLongOrNull()
            if (available != null || max != null) {
                gpuPath = candidate
                gpuMin = read("$candidate/min_freq")?.toLongOrNull()
                gpuMax = max
                break
            }
        }
        val ulq = exists("/sys/kernel/ulquiorra/active_mode") &&
            exists("/sys/kernel/ulquiorra/mode") &&
            exists("/sys/kernel/ulquiorra/freq_change_threshold")
        return DeviceSnapshot(model, kernel, policies, gpuPath, gpuMin, gpuMax, ulq)
    }

    fun apply(profile: Profile, snapshot: DeviceSnapshot): String {
        if (snapshot.policies.isEmpty()) throw IllegalStateException("No readable CPUFreq policies found")
        val results = mutableListOf<String>()
        snapshot.policies.forEach { policy ->
            val target = (policy.maxKHz * profile.ceilingPercent / 100L).coerceIn(policy.minKHz, policy.maxKHz)
            val safeTarget = policy.availableFrequencies.filter { it <= target }.maxOrNull()
                ?: policy.availableFrequencies.minOrNull()?.takeIf { it >= policy.minKHz }
                ?: target
            val path = "${policy.path}/scaling_max_freq"
            if (write(path, safeTarget)) results += "${policy.path.substringAfterLast('/')} max=${safeTarget}kHz"
            else results += "${policy.path.substringAfterLast('/')} skipped (not writable)"
        }
        if (snapshot.gpuPath != null) results += "GPU detected; profile mapping not applied"
        return "${profile.title} requested. " + results.joinToString(" · ")
    }

    /**
     * Universal control adapter:
     * - Ulquiorra uses its native ABI unchanged.
     * - Generic kernels map priority to core_ctl min_cpus and threshold to
     *   supported governor rate-limit knobs. Unsupported knobs are reported.
     *
     * This is an adaptive approximation, not a claim that generic kernels
     * implement the Ulquiorra scheduler algorithm.
     */
    fun applyClusterPriority(policy: CpuPolicy, priority: Int, ulqKernel: Boolean): TuningResult {
        require(priority in 0..3)
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        if (ulqKernel) {
            val role = when (policy.path.substringAfterLast("policy")) {
                "0" -> "little"
                "3" -> "big"
                "7" -> "prime"
                else -> null
            }
            if (role == null) skipped += "Ulquiorra role mapping unavailable for ${policy.path}"
            else if (write("/sys/kernel/ulquiorra/${role}_priority", priority.toLong())) applied += "Ulquiorra $role priority=$priority"
            else skipped += "Ulquiorra priority ABI node unavailable; daemon profile mapping required"
            return TuningResult(applied, skipped)
        }

        val cpus = policy.relatedCpus.split(Regex("[,\\s-]+")).mapNotNull { it.toIntOrNull() }.distinct()
        val count = cpus.size
        val coreCtl = cpus.firstOrNull()?.let { "/sys/devices/system/cpu/cpu$it/core_ctl" }
        val minNode = coreCtl?.let { "$it/min_cpus" }
        if (count > 0 && minNode != null && exists(minNode)) {
            val minCpus = when (priority) {
                0 -> 0
                1 -> maxOf(1, (count + 2) / 3)
                2 -> maxOf(1, (count * 2 + 2) / 3)
                else -> count
            }
            if (write(minNode, minCpus.toLong())) applied += "core_ctl min_cpus=$minCpus/$count"
            else skipped += "core_ctl min_cpus is not writable"
        } else {
            skipped += "core_ctl min_cpus unsupported for ${policy.path}"
        }
        return TuningResult(applied, skipped)
    }

    fun applyFrequencyThreshold(value: Int, ulqKernel: Boolean, policies: List<CpuPolicy>): TuningResult {
        require(value in 0..20)
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        if (ulqKernel) {
            if (write("/sys/kernel/ulquiorra/freq_change_threshold", value.toLong())) {
                applied += "Ulquiorra freq_change_threshold=$value"
            } else skipped += "Ulquiorra threshold ABI not writable"
            return TuningResult(applied, skipped)
        }

        // Generic fallback maps 0..20 to a conservative 0..20ms response window.
        // Only existing, writable governor attributes are touched.
        policies.forEach { policy ->
            val governor = read("${policy.path}/scaling_governor") ?: "schedutil"
            val base = if (governor == "schedutil") policy.path else "/sys/devices/system/cpu/cpu0/cpufreq"
            val up = "$base/schedutil/up_rate_limit_us"
            val down = "$base/schedutil/down_rate_limit_us"
            val rateUs = value * 1000L
            var touched = false
            if (exists(up)) {
                if (write(up, rateUs)) { applied += "${policy.path.substringAfterLast('/')} up_rate_limit=${rateUs}us"; touched = true }
                else skipped += "$up not writable"
            }
            if (exists(down)) {
                val downUs = (rateUs * 2L).coerceAtMost(40_000L)
                if (write(down, downUs)) { applied += "${policy.path.substringAfterLast('/')} down_rate_limit=${downUs}us"; touched = true }
                else skipped += "$down not writable"
            }
            if (!touched) skipped += "No supported schedutil rate-limit nodes for ${policy.path}"
        }
        return TuningResult(applied, skipped)
    }

    fun applyProfileTuning(
        profile: Profile,
        snapshot: DeviceSnapshot,
        priorities: Map<String, Int>,
        threshold: Int
    ): TuningResult {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        snapshot.policies.forEach { policy ->
            val result = applyClusterPriority(policy, priorities[policy.path] ?: 2, snapshot.ulqKernel)
            applied += result.applied
            skipped += result.skipped
        }
        val thresholdResult = applyFrequencyThreshold(threshold, snapshot.ulqKernel, snapshot.policies)
        applied += thresholdResult.applied
        skipped += thresholdResult.skipped
        return TuningResult(applied, skipped)
    }
}
