# Evidence bundle format (`nozzle.evidence` 1.0) and redaction rules

An evidence bundle is a ZIP produced by Test Mode (or by the CLI for simulated runs). It is immutable once exported:
any change to any file is detected by `BundleReader` (`test-grid/.../Evidence.kt`).

```bash
./gradlew -q :test-grid:cli --args="verify bundle.zip"
./gradlew -q :test-grid:cli --args="preview bundle.zip"
```

## Files

| Path | Content |
|---|---|
| `evidence.json` | The run, canonical JSON, redacted |
| `logs/run.log` | The run log (times relative to the run start), redacted |
| `attachments/<test>/<evidence>.<ext>` | Required photos and files: JPEG/PNG with metadata removed, or redacted text/JSON |
| `integrity.json` | Integrity manifest (below) |

Canonical JSON (`Canon.write`): keys sorted, two-space indent, `\n` line ends, trailing newline, numbers in plain
decimal without trailing zeros. The same content gives the same bytes on Android and the JVM. ZIP entries are sorted
and carry a fixed timestamp.

## `evidence.json`

| Field | Content |
|---|---|
| `format`, `version` | `nozzle.evidence`, `[1, 0]` |
| `run` | `runId` (UUID), `startedAt`, `completedAt`, `maxSafetyLevel`, `restarts`, `supersedes` |
| `producer` | `app`, `platform` (`android`, `jvm-cli`), `version` (Nozzle version), `build` (build type), `applicationId`, `sourceRevision` (git commit when available) |
| `engine` | `name`, `commit` (the pinned nozzle-engine commit), `version` (reported by the native engine), `binarySha256` (SHA-256 of the engine library in the installed APK), `pinSha256`, `simulated` |
| `suite` | `id`, `version`, `formatVersion`, `digest` (SHA-256 of the canonical suite), `coverage`, `title` |
| `target` | `kind` (`physical` or `simulated`), `manufacturer`, `model`, `printerKind`, `adapter`, `protocol`, `firmware` (`family`, `app`, `version`, `detail`), `capabilities`, `hardware`, `slicingModel`, `profile` (`id`, `name`, `sha256` of the profile pack), `nozzle.diametersMm`, `materials` |
| `inputs` | Per slice: `model`, `modelParts` (file, SHA-256), `profile`, `gcode.sha256`, `gcode.bytes`, `simulatedSlicer` |
| `tests` | Per test: `id`, `title`, `category`, `scope`, `safetyLevel`, `state`, `result`, `reason`, timestamps, `preconditions`, `expectedObservations`, `steps` (status, detail, data, confirmation action and time), `evidence`, `missingEvidence` |
| `grades` | Per scope and category: `result` (UNVERIFIED for simulated runs), `recordedResult` (what the run itself recorded), `tests` |
| `requirements` | Every SKIPPED, BLOCKED or UNVERIFIED test with its reason |
| `interventions` | Operator actions outside the plan: declines, interrupts, unmet preconditions, unknown-outcome reviews, restarts, retries, timeouts |
| `redaction` | `rulesVersion` and how many of each rule fired |

Not included, ever: printer address or saved name, hostname, API key or access code, serial, camera URLs, camera
images (only size and hash), local file paths, the G-code itself (only its hash), photo metadata.

## `integrity.json`

```json
{
  "algorithm": "sha-256",
  "bundleDigest": "<sha-256 of the canonical map path → file sha-256>",
  "contentDigest": "<sha-256 of canonical evidence.json with volatile fields removed>",
  "files": { "evidence.json": "…", "logs/run.log": "…", "attachments/…": "…" },
  "format": "nozzle.evidence-integrity",
  "signing": { "status": "unsigned", "extension": "nozzle.evidence-signature", "extensionVersion": 1, "signatures": [] },
  "version": [1, 0]
}
```

- `bundleDigest` identifies one exact bundle. The evidence store files bundles under it; the report uses it for
  provenance and to collapse duplicates.
- `contentDigest` compares outcomes: it leaves out volatile keys (`runId`, `startedAt`, `completedAt`, `approvedAt`,
  `observedAt`, `recordedAt`, `finishedAt`, `generatedAt`, `exportedAt`, `remotePath`, `reviewedAt`, `durationMillis`,
  `at`). Two runs with the same recorded outcomes have the same content digest (tested in `EvidenceTest`).
- **Signing:** bundles are content-hashed, not signed. The `signing` object is a versioned extension point
  (`nozzle.evidence-signature` 1, `signatures: []`). Nothing may claim a bundle is signed or attributable until a
  key-management and verification design exists. A future signature would cover `bundleDigest`.

## Verification (`BundleReader`)

A bundle is rejected, with reasons, when it is not a readable ZIP; has unsafe or duplicate paths; exceeds 64 MB;
lacks `integrity.json` or `evidence.json`; lists a missing file; contains a file the manifest doesn't cover; has any
hash, bundle-digest or content-digest mismatch; has an unsupported major version; or is incomplete (run not completed,
no run id, no target kind or firmware family, no suite, no or malformed test results, no Nozzle version, a listed
attachment missing).

## Redaction rules (version 1)

Applied by `Redactor` to every string in `evidence.json`, to the log and to text attachments. The redactor is given
the run's known-private values (printer address and host, saved name, API key/access code, serial, hostname read from
the printer, app-private paths), which are masked wherever they appear as `[private]`. A value that is part of the
run's public vocabulary (the suite's id, title, maker, model and firmware family, test titles, step and category names;
never text the printer reported) is not masked: a printer saved as "cosmos" would otherwise erase "cosmos" from the
suite id and firmware family. Then:

| Rule | Replacement |
|---|---|
| JSON keys `password`, `passwd`, `pass`, `secret`, `token`, `apiKey`, `accessCode`, `authorization`, `cookie`, `serial`, `sn`, `address`, `host`, `hostname`, `ip`, `url`, `cameraUrl`, `snapshotUrl`, `streamUrl`, `label`, `email`, `privateKey`, `certificate` (any case, `-`/`_` ignored) | value `[redacted]` |
| PEM private keys | `[private-key]` |
| `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `X-Access-Token` headers | value `[redacted]` |
| `Bearer`/`Basic`/`Digest`/`token` credentials, JWTs | `[redacted]`, `[token]` |
| `password=`, `token:`, `api_key=`, `access_code`, `secret`, `session_id`, `serial`, `pin`, `psk`, … key/value pairs | value `[redacted]` |
| Camera URLs (webcam, camera, stream, snapshot, webrtc, mjpeg, video, rtsp) | `[camera-url]` |
| URLs to local or IP hosts | `[local-url]` |
| Other URLs | credentials, query and fragment removed |
| Email addresses, MAC addresses | `[email]`, `[mac]` |
| IPv4 (all) and IPv6 addresses | `[ip]` |
| `.local`, `.lan`, `.home`, `.internal`, `.localdomain`, `.home.arpa`, `.ts.net`, `.intranet`, `.corp` hostnames | `[local-host]` |
| `/home/<user>`, `/Users/<user>`, `C:\Users\<user>`, `/storage/emulated/N`, `/sdcard`, `/data/user/N/<app>`, `/mnt/<volume>`, `/root` | `[home]`, `[device-storage]`, `[app-data]`, `[mount]` |

Firmware versions such as `1.6.0.267_20260815150420` and SHA-256 digests are left intact (tested).

Photos: JPEG APP1 to APP15 segments (EXIF, GPS, XMP, ICC, maker notes) and comments are removed; PNG `tEXt`, `zTXt`,
`iTXt`, `eXIf` and `tIME` chunks are removed. Only JPEG, PNG, plain text and JSON can be attached (15 MB each).

**Leak gate:** after redaction the builder scans every text file for known-private values, IP addresses, local
hostnames, user paths, emails, key material, tokens and unredacted authentication headers. If anything is found the
export is refused (`LeakDetected`); nothing is written.

Tests: `EvidenceTest.representativeSecretsAndEndpointsAreRemoved`, `noPrivateValueSurvivesIntoTheBundle`,
`sensitiveJsonFieldsAreDroppedWholesale`, `jpegMetadataIsStripped`, `theLeakGateFlagsWhatRulesMissOrWereNotApplied`.
