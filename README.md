# Nex Tuner

Independent Android performance manager inspired by the original Ulquiorra manager workflow. This repository is standalone and is **not merged with or dependent on Fox Tuner**.

> **Status: 0.1.0-alpha — development scaffold.** Not a release-ready APK. Build and device testing are required before daily use.

## Current implementation

- Jetpack Compose dashboard with Battery, Normal, Performance and Extreme profiles.
- Root-mediated runtime CPUFreq policy discovery.
- Conservative adaptive CPU frequency caps based on detected policy ceilings.
- KGSL/devfreq GPU discovery and adaptive maximum-frequency control.
- Optional thermal `sconfig` values read from the supplied Ulquiorra `profiles.conf` (Battery/Normal: 0; Performance/Extreme: 6).
- Missing nodes are skipped; writes are bounded by detected limits.

## Compatibility

Nex Tuner does not assume every kernel exposes the Ulquiorra custom ABI. On kernels without `/sys/kernel/ulquiorra/*`, it uses generic interfaces where available. Kernel-specific functionality that requires a driver cannot be emulated by an app and is reported as unavailable.

The supplied Ulquiorra profile file describes a fixed `3_4_1` CPU topology. This alpha does not blindly apply its absolute peridot frequency caps to other topologies. Generic profiles use conservative percentages of detected hardware ceilings; this is Nex Tuner fallback behavior, not a claim that these are original Ulquiorra values.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35, then run `:app:assembleDebug`.

## Safety

Root access is required to apply tuning. This is an early development build: keep a known-good boot image/kernel and test conservatively. Nex Tuner does not flash kernels or install kernel modules.

## Project separation

Application ID: `com.nex.tuner`. This repository is independent of `zaynali-source/Fox-Tuner`.
