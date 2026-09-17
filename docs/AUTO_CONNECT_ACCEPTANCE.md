# Saved-printer automatic connection

Scope: local Android implementation, validation, and sanitized review. Installation and physical testing require the owner's separate approval.

When the app first enters the foreground, it restores the selected printer and starts independent read-only snapshot polling for every other saved printer. If there is no saved selection, it selects the first valid saved profile. Each saved-printer card shows its own connection state. An unavailable endpoint retries independently without selecting another control target. Selection remains explicit; automatic startup does not send commands.

Backgrounding stops all polling and clears live status. Returning resumes each enabled connection once. Disconnect stops the selected printer for the current ViewModel session; the remaining printers continue monitoring. Selecting that printer explicitly reconnects it. A fresh application session auto-connects saved printers again. Forget removes its monitoring session; address edits retire the old endpoint. Late responses cannot recreate removed sessions.

Validation on 2026-09-08:

- 108 JVM tests passed, zero failures/errors/skips.
- Android lint, debug APK and instrumentation APK builds passed.
- New tests cover all saved printers, independent offline recovery, foreground idempotency, background/resume, explicit disconnect, exact selection, forgetting/editing endpoints, malformed saved addresses, and cancellation of late results.
- The new saved-printer UI instrumentation fixture compiled but has not run on a phone.
- Initial test-fixture compilation missed the image method; fixed. A subsequent run was terminated because its cleanup left secondary monitoring sessions active. Lifecycle cleanup was corrected before the passing full validation.
- APK SHA-256: `f1f40bd7f0ac30e26bacf7df517acae46b55c3a5c76a761d25170bdd58e4d559`.
- Source manifest SHA-256: `fd6759367e6373c81f452648a2d0551fe1cdc469e2c6f846d63c6f8c9d3f51a6`.
- Android evidence: `evidence-85d6c2ad0a9c` in `evidence-cycle-064a2a67c877`.

The profile also invoked an unrelated AI-server check. SSH rejected it locally. Its immutable FAIL record (`evidence-b516b90d02a2`) remains unchanged; owner explicitly excluded that check from this task. Disposition: `evidence-disposition-cd2da1f91e69`, OUT_OF_SCOPE. No retry or SSH configuration change.

Review cycle: `review-cycle-293302116233` (Grok, Gemini, DeepSeek). All three reviewers completed. Grok and Gemini returned no findings. DeepSeek raised one MEDIUM finding about automatic control reconnection after editing the selected address. Codex classified it DISPUTED and rejected the proposed behavior change: the existing test contract requires disconnect on address change; the new endpoint remains automatically monitored read-only while old confirmations are invalidated. The original finding and disposition remain in the ledger.

Device acceptance is pending: owner-approved installation on the Razr 2023, fixture UI verification, then launch with owner-approved read-only connections to saved endpoints. Verify per-printer status, target selection, background/resume, and explicit disconnect. No heater, fan, motion, extrusion, print, or macro commands belong to this acceptance.

Post-review validation: nine AutoConnectTest cases passed, including the new selected-address edit case. Production source and APK are unchanged. Finding `finding-5e3cdef07dbc` documents the retained reviewer disagreement.

## Owner-approved phone installation and read-only acceptance

Owner approved installation and read-only saved-printer verification after local closure `closure-e9b1140ec4f3` (READY). Installed the preserved APK on Motorola Razr 2023 only; the installed base APK hash exactly matched `f1f40bd7f0ac30e26bacf7df517acae46b55c3a5c76a761d25170bdd58e4d559`.

The AutoConnectDeviceTest UI fixture passed on the phone: one test in 3.037 seconds. Normal launch reported COLD. The first UI inspection displayed CONNECTED, standby, and temperature readings without any Connect tap. A subsequent UI inspection lost readings; the saved-printers page displayed automatic retry. Separate workstation read-only server/info requests also failed, including a recorded timeout. The saved list contained one physical endpoint, so simultaneous physical multi-printer acceptance is not claimed.

Explicit Disconnect then persisted after leaving and returning to the same app process. The app was left disconnected while the owner was asked to check printer power/network availability. No printer-control commands were sent. Successful automatic recovery and connected background/resume remain blocked by endpoint availability.

Evidence: artifacts/auto-connect/installed-identity.json, device-fixture.log, printer-reachability.json, disconnected-resume.json, disconnected-resume-ui.txt, and acceptance.json. The initial CONNECTED observation is in this conversation's tool output; cold-start-ui.txt contains the subsequent follow-up inspection after readings disappeared, not the initial successful snapshot.

## Recovery and completed device acceptance

After the owner reported connection restored, GET server/info returned Klippy connected/ready. The Razr 2023 debugging endpoint had changed; mDNS matched the same known device and ADB reconnected to it. The app displayed CONNECTED / Standby.

Observed the activity in STOPPED state, resumed HOT in the same process, and verified CONNECTED / Standby without a Connect tap. Then force-stopped only this app, launched COLD, and again verified CONNECTED / Standby without a Connect tap. Rechecked the installed APK hash against the approved artifact. Final phone state: connected. No printer-control commands were sent.

The first STOPPED-state helper assertion failed because it expected one space in Android's `Hist  #0` output. Inspection showed the actual whitespace; the corrected parser positively verified STOPPED before resume. No app code changed. Earlier connection outages and helper failures are retained as historical evidence.

Final evidence: artifacts/auto-connect/device-acceptance-final.json, connected-resume.json, connected-resume-ui.txt, cold-retest-launch.txt and cold-retest-ui.txt. Approved installation and read-only startup/resume acceptance are complete. Simultaneous multi-printer behavior is covered by JVM/UI fixtures; this phone has one saved physical printer. No isolated live outage-recovery claim is made.
