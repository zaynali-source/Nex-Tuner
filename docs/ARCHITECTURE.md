# Architecture

- `MainActivity`: Compose dashboard and profile selection.
- `RootController`: root-mediated runtime sysfs discovery and guarded writes.
- Profile source: supplied Ulquiorra `profiles.conf` for profile names and known sconfig values. Generic adaptive caps are Nex Tuner fallback logic, not original Ulquiorra presets.

## Planned work

1. Profile editor and a parser for the original profile format.
2. Per-control capability registry with read/write/validation metadata.
3. Restore-point capture and rollback after failed writes.
4. Optional Ulquiorra ABI adapter (`mode`, `active_mode`, `freq_change_threshold`, `request_seq`) only when the kernel exposes the expected interface.
5. RedFox thermal mapping adapter after the source and exact mapping table are verified.
6. Instrumentation tests and peridot/Evolution X device validation.

## Safety model

Never assume a sysfs node exists. Detect values and supported ranges at runtime, avoid topology-specific absolute values on unknown layouts, and skip unsupported controls. No kernel flashing or module installation is performed by the app.
