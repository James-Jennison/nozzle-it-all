# Non-blocking follow-ups from the Synthetic review

Current installed Linux desktop source is `6a915554`; the performance task preserves the earlier6bf paint/orbit/shading acceptance and adds exact-source performance/runtime validation. These are disclosed limitations, not claims of completed work or new release requirements.

- Maintenance: remove or harden the unused `PrepareState.autoPaintSelected` one-shot entrypoint before introducing any caller. The source call-site scan finds only its declaration. The active UI uses the guarded prepare/send/review/apply flow.
- Diagnostics: normalize a blank-key, relative server URI to a friendlier endpoint message. It currently fails request construction and is caught by `runTool`; it does not crash the app or send credentials. Authenticated non-HTTPS addresses are refused before request construction.
- Accessibility: add keyboard-only orbit/pan/zoom and verify screen-reader interaction separately. Current camera acceptance covers pointer orbit, including underneath, not keyboard navigation.
- Performance measurement completed: see NIA_VIEWPORT_PERFORMANCE.md for450k-triangle4K, sliver, offscreen and high-valence comparisons, exact-source tests and installed CPU samples. Dense4K software rendering still costs about154–160ms median per measured pass; no60Hz/display-FPS guarantee. Any GPU rewrite or adaptive-detail policy needs a separately defined owner workload/quality target. Continuous resizing replaces surface storage and can suppress the opt-in stable-size timing window.
- Maintenance: remove the unused private centroid `hitTest` helper when touching picking code; actual pointer selection uses `pickGrab`, not this stale helper. Inactive weak-cache variants may be recomputed after GC; active prepared meshes remain strongly owned.
- Platform parity: verify Windows settings ACLs and provider-child termination before claiming Windows credential/process-boundary acceptance. This task installed and verified Linux only.

Review evidence is durable in the user-level broker. Earlier paint acceptance remains under `~/.local/state/nozzle-it-all/acceptance/20261018-synthetic-final-6bf84dfe`; new package/reports/saved projects/screenshots/manifest are under `~/.local/state/nozzle-it-all/acceptance/20261018-viewport-performance`.
