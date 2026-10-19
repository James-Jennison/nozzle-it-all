# Govee Home names and normal Razr Play update

Status: IN PROGRESS, not normal-app acceptance or release completion.

## Owner authority and data boundary

The Owner authorized the existing Play Closed testing–Alpha update and installation
in the normal `com.nozzleitall.app`, preserving existing data. The Owner then
explicitly authorized read-only Govee Home name import with private key entry in
NIA. The Owner has obtained a key; Codex has not received, inspected or handled it.
No control commands, cloud readings, background lookup, new protocol, procurement
or qualification program is included.

Normal package baseline: Play0.1.2/code275, public signer
`ce6bba61258a3e407a0fb27e5a03342d34b44cdd2bf910844af36169f449c3f3`.
Hash-only full-list baseline: two saved printer entries and one saved project.
No uninstall, reset, normal private-data export, or unrelated device operation.
Installed version/UID/first-install time and these identities must be checked after
the in-place update; this record does not yet claim that check passed.

## Implementation and security

Current candidate: `90053bde7099d014bbbe6b0b7bfe30dd5cd71c56`.
Owner additionally requested desktop parity and a proper dock icon. Desktop now
has session-only password entry and explicit imported-name selection/rename using
the shared read-only client; no key persistence or screenshot-protection claim.
Installed raw jpackage Deb lacks standard launcher/themed icons. Corrected path is
scripts/package_linux.sh, now validating shell syntax/refresh hooks/WM class/icons
before producing the integrated Deb; installation/runtime confirmation pending.

Explicit, fixed GET to Govee's
[device-list API](https://developer.govee.com/reference/get-you-devices), which
documents `deviceName` as the Govee Home name. No redirect, retry, control endpoint
or measurement lookup. Default platform TLS; response bounded to2MiB/1000entries,
nesting32/structure20000 before JSON allocation. A20s deadline requests disconnect
even during header wait; OS/DNS scheduling is not a hard realtime guarantee, and
late responses are rejected. Errors do not echo keys, response bodies or exceptions.

Key dialog: SecureOn, password input, autofill opt-out, no reveal, prefill,
saveable-instance state or key export. Optional Remember uses a dedicated encrypted
preference file/keyset wrapped by the app's existing Android Keystore MasterKey;
printer credential files are not reused or altered. Unchecked keys are not stored.
Imported directory stays in process memory; added names persist in existing sensor
metadata. Existing labels are never overwritten without an explicit local rename.

Only complete six-byte MAC plus model can auto-match a discovered sensor. Eight-byte
or opaque cloud IDs never suffix-match; the user explicitly selects a same-model
Govee name when identity cannot be established automatically. No claim that the
Govee API returns every BLE model or account device. Per-sensor independent drafts,
actual discovery readings/RSSI/radio-reception time, unavailable values, and
Add/Already-added presentation replace the prior ambiguous discovery list.

Credential reads/writes are serialized. If cancellation races optional persistence,
the previous credential is restored before returning cancellation. Publication uses
only a short lock with no disk IO. Main-safe requestForgetAsync invalidates the request,
clears names and reserves cleanup before IO can be queued; new imports cannot
overtake it. ATOMIC app-owned finite cleanup survives navigation and is attempted
independently of environmental-history deletion. No automatic refresh or resume.
Failed deletion or rollback revokes saved-key reuse until explicit successful
cleanup or a successful explicit new remembered credential. Disk failure is never
claimed to have deleted a key. Typed errors retain only sanitized typed causes.

## Evidence so far — not substituted for normal-app verification

- Sourcec416 full1278results and three Synthetic reviews remain historical only.
- Actual generated Room schema7→9 synthetic fixture preservation on the authorized
  Razr: PASS1test0.343s. It never opened the normal app's private database.
- Sourcec93 full GTHost validation/build SUCCESS3m46s; source54d SUCCESS4m48s.
  Cached tasks are not claimed re-executed. Later source requires fresh validation.
- Source54f full run FAILED on the new deadline test: a timer could disconnect
  slightly before the nanosecond deadline and a buffered response still returned.
  Fixed with an explicit timed-out flag before disconnect; assertion not weakened.
  Original log/XML archive retained in private normal-Razr evidence directory.
- Source6a39 FAILED incremental Android compile: unresolved top-level discovery
  function despite its source and compiled class being present. Current12c is
  explicitly building nonincrementally without deleting/resetting any build tree.
  No success claim until that command completes.
- Initial Synthetic names cyclec73b5c5b36d4 all3COMPLETE. Remediation7df247d37457
  systemsCOMPLETE; frontend/adversarialFAILED before inference due Synthetic queue
  budget. Failures retained; no paid fallback/model substitution. Current-source
  remediationb294582b41e8 pending.
- v0.1.3 and v0.1.4 tags remain immutable and were never uploaded to Play. v0.1.3
  built/attested/uploaded CI artifacts, but its final GitHub-draft step FAILED
  because GTHost has no gh executable. Repository step now uses SHA-pinned official
  github-script; no host installation or credential handling was performed.
  Superseded owned CI runs cancelled; their results are not relabeled PASS.

Remaining actual acceptance: current build/test/review; signed successor artifact
and existing Alpha submission; normal-package in-place install/signature/source/
data checks; private Owner key entry; real account name import and foreground BLE
monitoring/history/no-auto-resume in that normal app. No additional gates inferred.

Private evidence: `/home/jjennison/.local/state/nozzle-it-all/acceptance/20261019-normal-razr`.

## Recovery boundary

On any real upgrade/data mismatch, do not uninstall, clear data, downgrade blindly,
or replace the signing identity. Preserve evidence and the on-device profile; halt
this update's rollout if needed and produce a forward-fix with the same Play signer
and a higher version code. The retained public prior APK is not an app-data backup.
No claim of a byte-for-byte private database backup or guaranteed rollback.
