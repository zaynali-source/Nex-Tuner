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
