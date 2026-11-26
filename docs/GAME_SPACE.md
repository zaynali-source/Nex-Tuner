# Game Space

Nex Tuner's Game Space is an independent Kotlin implementation informed by two references:

- Fox Tuner: `module/bin/ptext.sh` and `module/bin/ptlib.sh` for the Ulquiorra ROM's touch sysfs paths, touch-filter write format, game-mode switch, and sample-rate node.
- NotGameTurbo (AdarshGrewal, LineageOS branch): per-app game detection, UsageStats foreground events, app configuration, expert preset and manual touch-range concepts. The upstream project is Apache-2.0; this document records it as a design reference. No NotGameTurbo application source is bundled into Nex Tuner.

## Current implementation

- Lists launchable installed apps and automatically includes apps marked as games.
- Allows adding/removing custom apps and configuring each app separately.
- Stores per-app enablement, follow/Battery/Normal/Performance/Extreme preference, expert preset, manual touch values, and super-report preference.
- Requests Android Usage Access and starts an explicit foreground monitoring service only after the user enables Game Space and presses Start.
- Detects foreground packages and applies settings for enabled apps.
- Captures the original touch-filter, game-mode and sample-rate node values before the first write; restores them when leaving the enabled app or stopping the monitor.
- Probes the Fox/ROM touch node path and the NotGameTurbo sysfs path. Missing nodes are skipped; no unsupported paths are created.

## Values and paths

The touch-filter format follows Fox Tuner's `touch_commit` implementation:

- Expert preset: `6 <level>`, level 1–3.
- Manual: `<up> <tolerance> <aim> <tap> <edge> <orientation>`.
- Game mode: `1` while enabled, `0` when restored.
- Super report: writes the saved per-app boolean to `bump_sample_rate` if the node exists.

Manual ranges/defaults follow NotGameTurbo's `TouchFeatureManager`: up threshold 0–4 (default 3), tolerance 0–4 (default 2), aim sensitivity 0–4 (default 2), tap stability 0–4 (default 2), edge filter 0–3 (default 2); expert preset 1–3 (default 1). These are not claimed to be supported on every kernel.

## Not yet enabled

- TouchFeature HAL backend: sysfs backend only in this alpha.
- Dynamic display-orientation updates: current manual touch write uses orientation 0.
- CPU/GPU per-game profile switching: preference is stored in the UI, but not applied. It will only be connected after original Ulquiorra profile values and mode protocol are verified from the manager/config sources.
- Runtime behavior has not yet been tested on the POCO F6/peridot. A successful CI build is not proof that the device exposes compatible touch nodes or grants root access.

## Safety

Game Space is opt-in. It requires Usage Access, root, and a compatible writable touch sysfs interface. It saves the previous node contents and restores them on service exit. If the node layout or write format differs on a device, the feature must remain unavailable rather than guessing.
