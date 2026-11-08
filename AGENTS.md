# Agent instructions for HueSync

Use this file as a navigation map, not as a substitute for the source code. Keep changes focused and preserve the existing Android TV and Hue Entertainment behavior.

## Find the relevant code first

- Pairing flow, discovery, capture consent: `app/src/main/java/com/snapps91/huesync/MainActivity.kt`; controls and status rendering: `MainScreen.kt`
- Foreground lifecycle, DTLS scheduling, ownership checks: `SyncService.kt`; VirtualDisplay and capture fallback: `CaptureCoordinator.kt`
- GPU shader: `GpuColorSampler.kt`; RGBA capture: `CompatibilityColorSampler.kt`; RGBA kernel: `RgbaMacroSampler.kt`; frame pacing: `FrameCadence.kt`
- Channel and status models: `SyncModels.kt`; HueStream packet layout: `HuePacket.kt`; color filter: `ColorFilter.kt`
- Bridge HTTPS API and certificate checks: `HueApi.kt`; mDNS discovery: `BridgeDiscovery.kt`; DTLS transport: `HueStream.kt`
- Encrypted credentials and preferences: `SyncSettings.kt`; user-facing error text: `UserMessage.kt`; manifest and build configuration: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`
- Tests: `app/src/test`, `app/src/sharedTest`, `app/src/androidTest`

Start with `rg` to locate the affected symbol, then read only the relevant files or sections. Avoid dumping the entire repository, generated build output, or `dist/` unless the task requires them. Read `README.md` when changing public behavior or documentation.

## Behavioral constraints

- Capture the TV's own display only with Android MediaProjection consent. One session uses one VirtualDisplay; GPU is the initial sampler and ImageReader RGBA is its single fallback.
- Keep frame analysis separate from DTLS sending. Publish complete color snapshots; do not add a frame backlog or mutate a snapshot while the network thread reads it.
- Preserve channel IDs, the maximum of 20 channels, and the HueStream 2.0 packet format when changing sampling or color math.
- Keep Bridge communication local: verified Hue HTTPS for the API and DTLS 1.2 PSK for the stream. Keep pairing secrets encrypted with Android Keystore and out of logs and documentation.
- Do not claim DRM, secure content, HDMI, or other devices can be captured through this app.
- Keep all app-owned user-facing text in English, including controls, status, notifications, capture diagnostics, and errors. Android system prompts follow the TV's language.

## Working efficiently

- Make the smallest change that satisfies the task. Inspect nearby tests and run only checks relevant to the changed behavior when verification is useful or requested.
- Treat `README.md` as the public, current description. Keep machine-specific IPs, device diagnostics, and version history out of it.
- Update this `AGENTS.md` in the same change whenever file responsibilities, architecture, critical invariants, or build/test entry points change. Replace outdated guidance instead of appending a changelog; keep the file short. Routine implementation edits do not require updating it.
