# Off-UI viewport preparation acceptance — 2026-10-18

Installed executable source: `e3c5f7a00a0a30b20a7233c4837d4daf1ad94bd7`. Later documentation-only commits do not change that runtime identity.

## Changes

`DisplayPreparation.kt` owns one process-wide, limited-parallelism CPU dispatcher. Compose starts preparation in a lifecycle-cancelled effect keyed by distinct Mesh identity, paint kind and display budget. Expansion, simplification, corner normals, bounds and initial lighting-group construction run off the Swing UI thread. Cancellation checkpoints interrupt production CPU stages; queued cancelled requests do not start computation.

Results carry their complete source-key snapshot. Drawing and painting synchronously reject a mismatched result, including the composition before effect cancellation runs. Cancellable dispatcher return and explicit post-compute checks prevent a cancelled/noncooperative predecessor from publishing. Disposal cancels the viewport request. No stale-mesh fallback is rendered while waiting. Placement, selection, colors and instances bind from the latest objects without restarting unchanged geometry preparation. Pending paint batches are dropped if their source mesh/input changes.

A visible preparation message leaves camera/selection/edit controls usable. Ordinary preparation exceptions expose a clickable retry prompt; Compose's packaged clickable implementation includes focus/key handling. JVM Errors are intentionally not disguised as routine retryable failures. Empty pending frames do not allocate or rasterize a depth surface.

## Objective validation

All compilation, source tests and packaging ran on **gthost-build01**, Java21.0.12.1, Gradle8.13, Android SDK `/opt/android-sdk`, from a clean exact-revision checkout with configuration-cache disabled for validation. Package script explicitly selected the pinned native engine. No workstation source builds.

- Full desktop + verifyPaxxBaseline: **205 total,168 passed,37 conditional skips,0 failures/errors**. Seven new preparation tests all pass.
- Native-enabled AutoPaintTest, AutoPaintReviewTest and DisplayPreparationTest: **35/35 passed**. These overlap the full suite; do not add totals as unique tests.
- New deterministic tests cover real Swing dispatch while worker is blocked, noncooperative cancelled result rejection, cancelled queued work/disposal, source/kind/removal/reorder guards, latest presentation/instances, failure recovery and CPU cancellation.
- Exact-source CI **39165583995 SUCCESS**, including Android native/debug assembly. No device installation or printer command.
- Linux package SHA256: `5d89c20df430fc97cadf74137a2aeb3746ca1c73e935e7c683b4c6ee27797350`.
- Installed source-bearing jar SHA256: `fd16dcc80dfff699983e993a72b0f1f6ced53c503216cdac525586b6683cc0ed`; embedded revision matches. `dpkg` reports installed. Pinned engine SHA256 remains `d66da0a39dcf427079ab09e88b6692f6645e3a662aa0415ebee6c758d2e8d015`.

The owner approved desktop authentication; installation exited0. Candidate and installed acceptance used isolated Xvfb97 and fresh NOZZLE_HOME profiles. The owner's normal app/window was not restarted or controlled. An old task-owned Xvfb98 process was preserved, not substituted as new-build evidence.

`scripts/make_preparation_fixture.py` generated six distinct creased150k-face fans,900,000 triangles total, on GTHost. This is real pathological CPU work, not a production artificial-delay hook, and not a printable/visual-quality reference mesh. Candidate root and installed app processed zoom and SelectAll while preparation remained unpublished; installed screenshot shows6selected objects alongside the preparation message. Delete removed the viewport, cancelled the worker and produced **no obsolete publication**. Undo restored it and started a fresh request. The final installed request took **3.658 seconds**; the maximum observed Main heartbeat gap was **102.670ms**, with a100ms diagnostic interval. This is one local run, not a worst-case latency guarantee or display/compositor FPS measurement.

Installed camera-orbit input was also recorded. Before/after saved projects preserve all ordered vertex attributes, triangle attributes (including paint), and build-item placement attributes exactly:900,000triangles in the generated fixture and95,934triangles in the supplied Capybara. The Capybara source-copy SHA256 remains `f99a7d237a277273fd22df3d7e9f8d70accde073d0ef221eccb293921d36270d`; initial and underneath screenshots were inspected. No external semantic auto-paint call or claim of new semantic paint quality.

Evidence folder: `/home/jjennison/.local/state/nozzle-it-all/acceptance/20261018-async-preparation`. Its28-artifact manifest SHA256 is `3c6b2c55beb82fd92984b7014084070ea319fb0b416414f68239ce05990aab0b`. The validator checks package/jar/source/engine identity, remote reports, installed event ordering/thread ownership/heartbeat gap, save invariants and every manifest digest. Evidence cycle `evidence-cycle-d6a2dfd78c35`: both checks PASS with artifact integrity intact.

## Review and limits

Synthetic initial reviews completed independently on exact pins: systems `hf:zai-org/GLM-5.3`, frontend `hf:moonshotai/Kimi-K3`, adversarial `hf:deepseek-ai/DeepSeek-V4.1-Flash`. No provider/model/privacy changes or fallback. All6 initial/targeted closure cycles COMPLETE. Their49 original findings and separate Codex dispositions are retained under change `nia-async-preparation-e3c5f7a0`; no agreement-by-vote or claim reviewers executed commands. Frontend's first closure response was rejected by the read-only output guard; one same-job retry completed. Adversarial's first closure response was INCOMPLETE; one same-job retry completed. No output safeguard was weakened or bypassed.

Convergence `convergence-9f14a877b138`; closure `closure-48b1f8d2ebfb`: **READY_WITH_RESIDUAL_RISK**, no blocking reasons,3 explicit residual-risk records. No surviving current-source blocker/high defect. Rejected frontend's remaining MEDIUM error-persistence proposal: a changed key automatically starts a new preparation; any new failure restores retry for that source. Keeping an old error against different content would misrepresent current state. Its original claim and the separate Codex disposition remain visible. Full keyboard camera access and unrelated rendering/maintenance improvements were not silently imposed as new owner requirements.

Explicit trade-offs and boundaries:

- One worker bounds simultaneous CPU/allocation. Arbitrary noncooperative old work can delay the newest preparation until it returns; production stages cooperate, UI remains live, and stale publication is rejected. No latency independent of arbitrary old work is promised.
- Existing dense software-frame rendering, synchronous file import parsing and best-effort triangle simplification remain unchanged. This task does not certify60Hz, hard150k output size, arbitrary input-memory bounds, or responsive XML import parsing.
- Key order is conservative: order-only changes can request a cheap cached preparation round trip. Selection/transform changes keep stable keys. Inactive weak-cache entries may recompute after GC.
- Ordinary worker failure recovery is unit-tested; forced failure/retry in the installed UI and full screen-reader/keyboard3D camera navigation are not certified. Full keyboard camera access is pre-existing separate feature scope; clickable retry's keyboard/focus contract was verified against packaged Compose bytecode.
- Initial86e full run failed only a new test's latch scheduling; raw failure is retained. Corrected e3c tests passed. First candidate harness expected an empty request although deleting all objects disposes the viewport; corrected acceptance verifies disposal. Nested extracted launchers hit the known pre-JVM path-length crash; an actual short application-root copy launched, and installed standard path launched. Failed attempts are not relabeled PASS. No unrelated artifacts were deleted.

No PR merge, release, hardware acceptance, printer action or owner-window restart was performed.
