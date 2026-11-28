# Nex Tuner architecture

Nex Tuner is a standalone, ROM-independent Android tuning app. Its compatibility model is capability-driven: the app discovers what the running device exposes and enables only controls with a verified read/write contract. "Universal" is an engineering target, not a claim that every kernel exposes every feature.

## Layers

- **Compose UI:** presentation only. UI controls consume capability/state models and never construct sysfs paths themselves.
- **Device discovery:** runtime inventory of CPUFreq policies, CPU topology, frequency tables, governors, devfreq/GPU, thermal zones, charging nodes and optional vendor interfaces.
- **Capability registry:** each control records detected path, source, readable/writable state, accepted range/enum, validation and fallback policy. Missing or ambiguous controls are Unsupported, not guessed.
- **Generic Linux/Android adapters:** standard kernel interfaces only (CPUFreq, devfreq, cpuset/core_ctl, governor attributes, power-supply and thermal interfaces) and only after exact node/range validation.
- **Ulquiorra adapter:** isolated compatibility layer for verified Ulquiorra ABI. Original profile/daemon semantics and configuration remain authoritative on Ulquiorra; generic approximations must never be labelled as Ulquiorra behavior.
- **Vendor/ROM adapters:** optional adapters for ROM-specific interfaces. They must be independently sourced and versioned; SConfig/thermal mappings must come from Ulquiorra or RedFox, never Fox Tuner.
- **Apply/rollback engine:** snapshot current values, validate all proposed writes, apply in a controlled order, read back results, and rollback changed nodes if a write fails.

## Universal compatibility rules

1. Never hardcode CPU/GPU frequency tables, cluster counts, CPU IDs, thermal-zone names or vendor paths as universal defaults.
2. Discover CPUFreq policy directories and parse `related_cpus` / `affected_cpus` at runtime. Read available frequency steps where exposed; otherwise use only validated min/max bounds.
3. Discover GPU/devfreq instances by inspecting candidate class entries and their attributes, not by assuming a single GPU driver path.
4. Identify governor and supported attributes per policy. Do not map Ulquiorra's 0–20 threshold to schedutil rate limits unless the mapping is explicitly labelled as a generic approximation and the target nodes/ranges are verified.
5. Do not map Ulquiorra Cluster Priority 0–3 to `core_ctl/min_cpus` and present it as equivalent. A generic core_ctl control may be offered separately with its own label and semantics.
6. Treat root, SELinux, permissions, read-only nodes, absent drivers and ROM restrictions as capability outcomes. Never silently report success.
7. Store per-device discovered capabilities and profile values separately; invalidate/re-scan them after kernel or ROM changes.
8. Unsupported controls stay visible as unavailable with a reason. No fake FPS, GPU, thermal or charging support.

## Current implementation status

- Compose dashboard and four profile selectors exist.
- CPUFreq policy discovery and live frequency-step discovery exist.
- CPU max-frequency caps and limited profile controls exist.
- Ulquiorra ABI detection and selected ABI writes exist, but full original daemon/profile parity is not complete.
- Generic Cluster Priority and Frequency Threshold are approximations and must remain clearly distinguished from original Ulquiorra behavior.
- GPU profile application, verified thermal/SConfig adapter, Charging Controller completeness, transactional rollback and broad device validation remain outstanding.

## UI direction

Native Jetpack Compose; soft light-blue tonal accent; restrained surfaces, clear hierarchy, compact device telemetry and responsive layouts. Fox Tuner 4.0 is a UI architecture reference only. No Fox Tuner tuning presets, SConfig mappings or backend assumptions are imported.

## Safety model

Never assume a sysfs node exists. Read and validate before writing, snapshot old values, verify writes, report partial failures and rollback where possible. The app does not flash kernels or install kernel modules.


## GameBar telemetry

- The standalone overlay records one CSV file per explicit recording session.
- Session history parses CSV into time-series data and displays charts for available metrics plus a scrollable raw sample preview and SAF export.
- FPS average/min/max and 1% / 0.1% low estimates are computed from the captured samples.
- The peridot GameBarOverlayPeridot commit is a hardware-specific ROM resource overlay, not a universal sysfs profile. Its SM8635/Adreno 735 paths are reference data only; runtime discovery must remain the default for other devices.
- Privileged ROM-only APIs and features are not claimed by the normal APK. Metrics unavailable through readable interfaces are reported as N/A.
