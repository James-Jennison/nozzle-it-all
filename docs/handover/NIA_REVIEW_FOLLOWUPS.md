# Non-blocking follow-ups from the Synthetic review

Current acceptance is the Linux desktop build from `6bf84dfe`. These are disclosed limitations, not claims of completed work or new release requirements.

- Maintenance: remove or harden the unused `PrepareState.autoPaintSelected` one-shot entrypoint before introducing any caller. The source call-site scan finds only its declaration. The active UI uses the guarded prepare/send/review/apply flow.
- Diagnostics: normalize a blank-key, relative server URI to a friendlier endpoint message. It currently fails request construction and is caught by `runTool`; it does not crash the app or send credentials. Authenticated non-HTTPS addresses are refused before request construction.
- Accessibility: add keyboard-only orbit/pan/zoom and verify screen-reader interaction separately. Current camera acceptance covers pointer orbit, including underneath, not keyboard navigation.
- Performance: measure worst-case 4K multi-object frame time and high-valence first-display cost. Frame buffers are reused and bounded to approximately four million samples, but no FPS or first-display timing guarantee has been established.
- Platform parity: verify Windows settings ACLs and provider-child termination before claiming Windows credential/process-boundary acceptance. This task installed and verified Linux only.

Review evidence is durable in the user-level broker; installed package, remote reports, saved-project assertions, screenshots, and compiled null-safety/call-site evidence are retained under `~/.local/state/nozzle-it-all/acceptance/20261018-synthetic-final-6bf84dfe`.
