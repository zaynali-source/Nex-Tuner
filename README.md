# Nex Tuner

Independent Android performance manager inspired by the original Ulquiorra manager workflow. This repository is standalone and is **not merged with or dependent on Fox Tuner**.

> **Status: 0.1.0-alpha — early source scaffold.** No APK release has been built or device-tested yet.

## Current implementation

- Jetpack Compose dashboard with Battery, Normal, Performance and Extreme profile selectors.
- Root-mediated runtime CPUFreq policy discovery.
- Conservative adaptive CPU maximum-frequency caps based on each detected policy's reported hardware ceiling.
- Runtime checks for the Ulquiorra custom sysfs ABI.
- Missing/unwritable CPU controls are reported rather than blindly written.

## Important limitations

- The current alpha only applies CPUFreq maximum-frequency caps. GPU, thermal, scheduler, core_ctl and Ulquiorra-specific mode switching are detected/planned but **not yet applied**.
- The supplied Ulquiorra `profiles.conf` and daemon are reference material for implementing the original profile behavior. This alpha does not claim to reproduce the original daemon or all original tuning values.
- Thermal `sconfig` mappings must be verified against Ulquiorra or RedFox source before implementation; Fox Tuner values are not used.
- Generic profile percentages are Nex Tuner fallback behavior, not original Ulquiorra presets.
- Kernel-specific features that require a driver cannot be emulated by an app. Unsupported controls remain unavailable.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35, then run `:app:assembleDebug`. A Gradle wrapper and CI build workflow have not yet been added.

## Safety

Root access is required to apply CPU tuning. This is an early development build: keep a known-good boot image/kernel and test conservatively. Nex Tuner does not flash kernels or install kernel modules.

## Project separation

Application ID: `com.nex.tuner`. This repository is independent of `zaynali-source/Fox-Tuner`.


## Universal CPU tuning backend (in progress)

The current implementation adds runtime CPU policy discovery and a per-profile tuning panel:

- CPU policy frequency steps are read from each policy's `scaling_available_frequencies`; the app does not ship a hardcoded device frequency table.
- CPU cluster membership is parsed from `related_cpus` (falling back to `affected_cpus`), including CPU ranges such as `0-3`.
- Per-profile CPU frequency caps use the live `scaling_available_frequencies` table when present; values are snapped to a real supported step. If the table is absent, the app uses the detected min/max range.
- Generic cluster priority (0–3) adapts to a detected cluster's `core_ctl/min_cpus` control when present. The mapping is an adaptive approximation, not Ulquiorra's proprietary scheduler behavior.
- Generic frequency threshold (0–20) maps only to verified schedutil `up_rate_limit_us` and `down_rate_limit_us` nodes. Other governors are reported as unsupported rather than receiving guessed writes.
- On an Ulquiorra kernel, profile selection is sent through `/sys/kernel/ulquiorra/mode`, and threshold changes use the verified `/sys/kernel/ulquiorra/freq_change_threshold` ABI. The daemon's original profile configuration remains responsible for per-cluster Ulquiorra priority values; a custom per-cluster priority sysfs ABI has not been verified, so the app does not invent one.
- Frequency caps, priorities and thresholds are persisted separately for Battery, Normal, Performance and Extreme profiles and are applied when a profile is selected.

**Not yet complete:** GPU profile application, Charging Controller, per-game CPU/GPU switching, and a fully verified generic scheduler adapter beyond the supported nodes above. The current repo does not yet include a build wrapper/CI workflow, so these commits have not been APK-build-tested.


## GameBar

Nex Tuner includes an independently implemented floating GameBar section inspired by the feature set of [kenway214/packages_apps_GameBar](https://github.com/kenway214/packages_apps_GameBar) (Apache-2.0). This is not a direct import of its privileged ROM application: Nex Tuner is a normal APK and therefore uses user-granted overlay permission, Usage Access/Game Space app selection for auto-show, and root-readable sysfs/procfs telemetry where available.

Current overlay capabilities:
- Floating, draggable and collapsible live panel.
- FPS when the display driver exposes a readable measured-FPS node.
- CPU utilization, policy clock, GPU utilization/clock, CPU/GPU thermal-zone temperatures, RAM utilization and battery temperature.
- Configurable metric visibility and 500/1000/2000 ms sampling.
- Optional auto-show for apps already enabled in Game Space.

Metrics without a supported readable source display N/A. The ROM GameBar project requires a privileged/system build and platform permissions; those privileged-only features (SurfaceFlinger FPS APIs, QS tile, screenshot/recording integrations and ROM Settings integration) are not falsely represented as available in this standalone APK. See the upstream [README](https://github.com/kenway214/packages_apps_GameBar) and [user guide](https://github.com/kenway214/packages_apps_GameBar/blob/lineage-23.2/GAMEBAR_USER_GUIDE.md).
