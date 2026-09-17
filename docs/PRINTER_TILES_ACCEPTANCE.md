# Printer tiles and camera previews

## Current acceptance — installed and verified

The reviewed r2 APK is installed on the Motorola Razr 2023; the installed APK hash matches b8998c1d37da320d6def2c21219a1b6447e150e0114e56f18a5760c546e3f3de. All 18 selected Android device tests pass (artifacts/printer-tiles/device-tests-r2-final.log), in addition to the 118 JVM tests and lint already recorded below.

Both real printers reconnect on cold launch and display live camera tiles. The final screenshot after background/resume shows Snapmaker live video at 24 fps and Elegoo live MJPEG at 6 fps. Each tile opens its own connected full dashboard and All printers returns to the grid. Backgrounding reached STOPPED; a HOT launch resumed the same process and both live previews recovered. Evidence: artifacts/printer-tiles/live-grid-final-r2.png, lifecycle-r2.json, installed-identity-r2.json and acceptance-r2.json.

Two earlier runs each failed one large-text fixture because test navigation addressed an uncomposed lazy-list item. The test helpers now scroll the parent list to the item's tag before selecting it; the final full selected run passes. The failed logs remain preserved. Production code and the reviewed main APK were unchanged during these test-harness corrections. No printer-control commands were sent and no printer configuration was changed. The owner explicitly authorized necessary installation and testing without another permission checkpoint.

The records below describe earlier stages; their pending approval, review and installation statements are superseded by this current acceptance.

The Dashboard now opens to compact connected-printer tiles with live camera previews, print state, filename, progress and temperatures. Tapping a tile or its camera selects that exact printer and opens the existing complete dashboard. All printers returns to the grid. Narrow/large-font layouts use one column; ordinary phone layouts use two; wide layouts use three. Tiles preserve camera choice per printer and exclude disconnected endpoints. Camera renderers are keyed to the printer and stop when off-screen, disposed or backgrounded.

Camera diagnosis: the second connected printer, Elegoo CC (COSMOS) at 192.168.1.x, advertises /webcam/?action=stream using mjpegstreamer-adaptive. Its stream and snapshot endpoints return HTTP 302 to the same printer at port 8080. Read-only probes confirmed valid 200 multipart MJPEG and image/jpeg responses there. The installed app rejects redirects. The new local build follows at most three camera GET redirects, validates each destination against the printer host, rejects credentials/fragments and HTTPS downgrade, and closes intermediate responses. Command requests retain their existing no-redirect policy. No printer configuration was changed.

Validation: 116 JVM tests, zero failures/errors/skips; lint, debug APK, instrumentation APK and source proof passed. New unit tests cover tile data isolation and camera selection, camera redirect success/host rejection/loop limits/downgrade/snapshots. New device fixtures cover tile selection, full details, return navigation and offline overview. Existing dashboard fixtures now enter the detail view before their original assertions. Device tests compiled but have not run for this build. Installation and live camera UI verification are pending owner approval after review.

APK: artifacts/printer-tiles/klipper-companion-printer-tiles-debug.apk
SHA-256: 3335b4190945da77811a885873c0e41efa7ac0d4efa5d805456b917ab15e592f
Source manifest: 94c0e59de8be298b00bcc5835926dcdb5f5a1f670bf9dfcf9b38dc862c9f8695
Evidence: evidence-cycle-3ad437a5a264 / evidence-2a4ae88afa1d (PASS)

Review blocker: control-submit could not create a cycle because the current global broker registry disables DeepSeek and has no enabled reviewer covering concurrency and security. Grok and Gemini are enabled. No reviewer registry change or substitute review was attempted. Required review is incomplete; do not claim local closure or installation readiness. Owner decision is required to restore the reviewer pool or authorize another review scope.

Owner subsequently authorized Claude temporarily in place of DeepSeek. Review cycle `review-cycle-e83d27378052` was accepted with explicit per-cycle reviewers Grok, Gemini and Claude. The global registry was not changed. Review completion is pending.

## Final r2 build after reviewer remediation

Grok returned no findings. Gemini's HIGH allegation that Moonraker.close permanently closes the client was withdrawn in review-cycle-da0d4cc5d3cf after the actual cancellation/connection-eviction implementation and exact image reuse test were supplied. Original finding finding-150d1464be3f remains immutable, with rejected disposition finding-disposition-95629b1d724b.

Claude's MEDIUM close-exception isolation finding was confirmed with narrower scope as an interface-level robustness risk; no production close failure was observed. SavedPrinterMonitor now catches Exception from a client close after cancelling its job, so state cleanup and other client removals continue. A two-client test throws from the first close and verifies both closes, empty state, and no further polling. Finding finding-c3e114e93b23 is resolved by finding-disposition-e95f9cf9725f.

The final build passes 118 JVM tests, lint, both APK builds and artifact identity. Change mobile-klipper-printer-tiles-20260908-r2 supersedes the initial change. Evidence: evidence-cycle-6c10d45e5129 / evidence-12ed6559916c. Final APK is artifacts/printer-tiles/klipper-companion-printer-tiles-r2-debug.apk with SHA-256 b8998c1d37da320d6def2c21219a1b6447e150e0114e56f18a5760c546e3f3de. Source manifest: 1388f7c60a7c10e142df1f9e6ad6e2edb08c557c2ba4fbc5bc59f7aee4b86054.

Closure review cycle review-cycle-0206b9768453 is pending with Grok, Gemini and owner-authorized temporary reviewer Claude. Device UI/camera acceptance and installation remain pending owner approval; no new build has been installed.

Final closure: `closure-44e599006f87`, READY for local scope. Grok, Gemini and Claude all completed final review with no findings. The fixed cleanup finding was confirmed resolved by Claude, and the withdrawn client-reuse finding was independently confirmed in closure review. Installation and device visual/live camera acceptance remain unperformed pending owner approval.

## 2026-09-13 dashboard display follow-up

Owner requirements: match compact card/camera-caption sizes; show no active filename and 0% when no job is active; show Standby when a ready printer retains a completed/cancelled last-job result.

Implemented equal weighted cells and per-row natural-content height alignment, bodySmall camera captions including fallback paths, and derived activeFilename/activeProgress/displayState properties used in tiles and print detail. Printing and paused jobs retain active progress; errors remain distinct. Raw telemetry and printer controls are unchanged.

Final source proof: `5858e94dd83c4f3c39286df95db5b3cc3b66130a0ab5a4bae8df75e378bf7eb0`; APK SHA-256 `9242a37dbcc74d6faa3b62145a67ea27687d704a8206853ab2bbe35cd7f9d7cb`. Installed hash verified on the Razr 2023. Protected validation passed 147 JVM tests, lint and both debug packages. Six phone tests passed, including completion-to-standby/0% in tile and detail, paused/new-job transitions, tile routing, unequal content at 320/360dp, large text layout, and lazy dashboard camera activation.

Actual phone screenshot `artifacts/tile-sizing/live-r7.png` confirms both live camera images and FPS captions, equal font sizing, and both printers showing Standby / No active file / 0%. Fresh UI bounds show both cards 482x794px with identical top/bottom. Camera caption descendants are omitted by UIAutomator beneath the existing tap overlay; earlier missing-node assertions were not evidence of a video rendering failure. Dedicated TalkBack caption exposure remains unverified; visual acceptance is backed by the screenshot and Compose tests. No polling or temporary viewport diagnostics remain.

Evidence: `artifacts/tile-sizing/acceptance-r7.json`, `device-tests-r7.log`, `dashboard-fixes.diff`; engineering change `mobile-klipper-dashboard-idle-20260913-r7`, build evidence `evidence-a454a560033d`. Earlier failed layout tests remain retained and are superseded only by the final exact-source validation. Full M2 and physical-printer testing remain paused.
