# Klipper Companion for Android

Original Kotlin / Jetpack Compose local-network MVP. No memberships, advertising, cloud account, or analytics.

## MVP
Connect to a Moonraker base URL; monitor printer state and temperatures; view continuous camera-streamer WebRTC video; browse G-code files; execute listed macros and start/pause/resume/cancel with explicit confirmation. Single printer for this milestone. Foreground monitoring only. No background alerts, general MJPEG video player, file editing, slicing, Spoolman, or remote service integration yet.

Only use a trusted LAN endpoint. HTTP is supported for conventional local Moonraker installations. This MVP rejects URL credentials and does not store passwords or API keys. Moonraker authorization-required responses are shown as unsupported authentication. It does not change printer authorization settings. Camera URLs resolve relative to the configured web frontend address, so prefer the Mainsail/Fluidd base URL when webcams use relative paths.

Mutating requests are never automatically replayed. A lost response is an unknown outcome; inspect the printer before deciding on another action. Macros can move or heat the printer; review their behavior before confirming.

## Build and validate
Install Android SDK 36 and JDK 17. Run `bash scripts/validate.sh` from the repository. Physical device UI checks use `heavy-gradle :app:connectedDebugAndroidTest` only with the exact intended device selected via ANDROID_SERIAL. Actual printer mutation acceptance is owner-operated.

## Sources
Protocol: https://moonraker.readthedocs.io/en/latest/external_api/
Compose: https://developer.android.com/develop/ui/compose

Live camera playback uses an isolated Android WebView with app-owned receive-only WebRTC code and local signaling. It does not load camera-server JavaScript or use external STUN servers. Camera URLs must remain on the configured printer host. Snapshot-only cameras remain explicitly labeled as refreshed snapshots.
