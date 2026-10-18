# Dense-model camera responsiveness (2026-10-18)

## Implementation

Installed executable source: `a6fc8c438991bb3b2d88c2c2b696131fb0473142`.
Objective validation is PASS. Final closure `c8362113f99e` is
READY_WITH_RESIDUAL_RISK, with no blocking reasons and three disclosed boundaries.

Sampling-only JFR of the previous installed renderer found pixel rasterization,
per-frame object setup and lighting dominant. The replacement retains exact
world-space poses/face normals and orientation/material-specific corner colors in
a 64 MiB per-viewport LRU, pruned to active scene objects. Zoom/pan reuse lighting;
orientation, selection, material and pose changes invalidate the appropriate data.
Cancelled partial writes invalidate the old shading key before modifying colors.
The64MiB accounting covers new pose world/face/color arrays, not keys' strong
references to active DisplayMesh/source/normal/state arrays or object overhead.
Active meshes are already held by the scene; pruning removes inactive cache keys.
Scenes larger than the cache evict even active poses and may recompute them on
subsequent frames. The bound is intentional, not an all-active-poses-fit guarantee.

The full physical-resolution bed/shadow/object composite now runs off Main through
one process-wide serialized frame dispatcher. Each viewport has one running and
one conflated pending request. Camera-only supersession permits completed frames
to present, avoiding starvation during continuous motion. Source/size changes
reject obsolete output. Draw, overlays, picking and paint rays use the presented
camera, not a newer requested camera. Native images are immutable snapshots with
explicit ownership, including cancellation-lost results. Main teardown is
nonblocking; the exclusive worker owner disposes its backend after render/joins.

Above 2.5 million depth samples, disjoint 16×16 tiles distribute exact per-pixel
work over at most four numeric workers total (including the frame coordinator).
Every started helper is joined on failure/interruption before scratch reuse.
Helpers are process-wide, lazily created daemon threads; they never own Skia or
Compose state. Original triangle/object ordering, coverage, depth and color math
are preserved. Tile-index retention is capped at two million ints (8 MB), with
exact serial fallback on overflow. Smaller surfaces use the measured serial policy.
Resize drops old pixel/depth storage before allocation and reuses size-independent
scratch. Hidden obsolete snapshots are released before replacement rendering.

## Objective methodology

All source compilation, tests, benchmarks and packages run on `gthost-build01`,
with clean exact source, Java 21.0.12.1, Gradle 8.13, Android SDK `/opt/android-sdk`
and explicit build settings. The pinned engine digest remains
`d66da0a39dcf427079ab09e88b6692f6645e3a662aa0415ebee6c758d2e8d015`.
No local source builds. Raw evidence is retained under
`~/.local/state/nozzle-it-all/acceptance/20261018-dense-interaction`.

The control executable is the previously installed `e3c5f7a0`; its benchmark-only
source `50db5e3b` leaves production behavior unchanged. Each wall-time benchmark
uses five warmups then eleven samples, 450k/1.2M triangles, 1080p/4K and orbit or
zoom/pan. A separate same-JVM alternating-order nine-sample control measures the
cached serial versus tiled policy. These measure full-quality render-pass elapsed
time, not aggregate CPU consumption, display/compositor FPS or a 60Hz guarantee.

Runtime acceptance uses task-owned4K Xvfb96, software rendering, a fresh isolated
NOZZLE_HOME and exact executable/jar/source checks. Each 450k/1.2M plate receives
40 alternating zoom events and 40 empty-space orbit moves, followed by two resizes,
Select All and Save As. The task-selected gate is input dispatch p95 ≤100 ms,
continued frame progress during motion and final requested-frame settlement.
The window is requested at3840×2160; the observed main Canvas is3408×2046
(the UI/sidebar and window constraints consume the remainder). Unit benchmarks
above use the exact stated3840×2160 raster. Zoom injection includes xdotool's
click delay (~6.5 Hz); orbit is about20 Hz.
Heartbeat and input timestamps are metadata-only and enabled explicitly.
Before/after saves compare ordered vertex, triangle (including paint), and build
item placement attributes. Capybara is checked separately underneath, with save
invariants. The owner's normal app/profile/window and all printers are untouched.

## Validation and review status

Exact-source GTHost reports:222 total/183 passed/39 conditional skips/zero failures
or errors; native-focused50/50 passed; all15 frame regressions passed in both.
Both opt-in dense benchmark methods passed. An additional entire native-enabled
suite passed222total/214pass/8opt-in benchmark skips/zero failures; the31 initial
native-availability skips all executed and passed. All39 initial skips are
enumerated in test-inventory.json; six unrelated legacy microbenchmarks were not
selected on this revision. Exact-source CI39170879593 SUCCESS, including Android
native/debug assembly. No Android-device installation or printer action.

## Installed artifact and interaction proof

Owner-approved `pkexec dpkg -i` exited0. dpkg status is installed. Package SHA256
`beb31a74e40d45d82f391b3e16222935ad505cfe1b98d696e376d414cf94c178`;
installed source-bearing jar SHA256
`8b5ec072f1c821076874702bf925cbd5b4f71430d7384ae9c4ead19c881128dd`.
Embedded revision and pinned engine match the clean remote artifact.

Each input category below has40 samples. Installed full-quality frames progress
during motion, and final requested/presented sequence86 settles for both plates.

| Workload | Prior input p95 zoom / orbit | Installed input p95 zoom / orbit |
| --- | ---: | ---: |
| 450k |35.134 /105.927ms|12.213 /15.197ms|
| 1.2M |319.988 /328.610ms|25.689 /20.045ms|

During450k motion,40 zoom-window/19 orbit-window frames presented;1.2M had29/8.
Max measured100ms-heartbeat gaps during motion were171.514/125.233ms and
143.333/124.962ms respectively. Final orbit settlement after input stopped was
143.582ms at450k and411.497ms at1.2M. These are not input-dispatch numbers and
not a promise of every injected camera pose being displayed. Selection and resize
screenshots were recorded. Ordered saved vertices/triangle attributes including
paint/build-item placements match before/after450k,1.2M and95,934face Capybara.
The Capybara underneath image was inspected; source-copy hash remains unchanged.
All acceptance app processes were task-owned, exact-path/display verified and
stopped afterwards. Owner's existing window was not restarted or manipulated.

Local validator PASS seals evidence with SHA256 manifest
`bf17ef95db759d7d189b0dcb6fe2f9b5b7e2db00e59f6a7c6959a05c37b52aed`.
The initial evidence cycle `c225f90ac5f7` is PASS/intact and remains immutable.
A stronger supplementary capture waits for400ms of stable matching request/frame
sequence plus healthy Main ticks, fixing a screenshot-timing race that could show
selection loading before its new frame appeared. Its first attempt timed out due
to an overescaped regex in the harness (retained); the corrected repeat passed
80 further installed1.2M inputs, p95 zoom16.153ms/orbit24.806ms, saved invariants
unchanged, with all8 selected objects and handles visibly present. No renderer
source changed for this test correction. Supplemental manifest SHA256
`186dee629b160754194e9147533bd2bb43d5f10c61264a7359a1aa0485774a77`.
Final evidence-cycle `5204a01833a1` runs original validation plus the supplemental
sealed check and diff-format gate: all3 PASS/intact. No prior failure rewritten.

## Render-pass benchmarks

Full-quality4K median render-pass wall time (five warmups, eleven samples):

| Plate / camera | Previous renderer | Installed source |
| --- | ---: | ---: |
| 450k orbit |277.810ms|158.210ms|
| 450k zoom/pan |269.701ms|125.734ms|
| 1.2M orbit |708.249ms|373.982ms|
| 1.2M zoom/pan |693.418ms|286.266ms|

Same-JVM alternating-order4K controls (nine samples) supported the tiled policy:
450k orbit171.874→159.555ms, zoom165.818→132.336ms;1.2M orbit460.583→405.316ms,
zoom382.215→304.759ms. Shared-host variation is visible across repeated runs:
the earlier139 paired1.2M orbit p95 was essentially unchanged (401.938→402.176ms),
so tiling is not claimed to improve every workload/sample. Cache reuse and moving
the expensive pass off Main remain distinct improvements.
Initial139 Synthetic systems/front-end/adversarial cycles completed independently:
`fb0293090515`, `dd618fb26753`, `5812e19ac0fa`. Nineteen immutable findings have
separate Codex dispositions. Missing excerpts were package omissions, not absent
compilable code. JDK21 FutureTask clears interruption before throwing; the cache
hit path reads per-pose colors, not shared lighting scratch. Targeted regressions
and clearer structure were added rather than applying incorrect proposed fixes.

The first refinement build failed test compilation because the frozen oracle
requires DrawScope. The next failed the stronger cancellation test because yaw
alone does not change a planar normal. Both failures are retained; neither is
PASS evidence. Current tests provide DrawScope and tilt the fixture, cancel only
after actual color mutation, then prove exact restoration and null stale key.

## Synthetic convergence

Current-source source/closure reviews `c0da4c5cab91` (systems GLM5.3),
`0215fd2cec64` (frontend KimiK3), `ceccd4d7bf7b` (adversarial DeepSeekV4.1Flash)
and completed-evidence clarifications `7dfb44bf4d4e`, `c0438d6b38ab`,
`8b2613031cae` all COMPLETE. Frontend focused narrowing `334b94ade654` explicitly
withdraws its incorrect missing4K-artifact-reference claim and distinguishes the
100ms Main heartbeat diagnostic from frame cadence. Actual requested and returned
model IDs were checked for all7 cycles; no substitution/provider/privacy change.
All were first-attempt complete. Reviewers did not execute tests or access source
outside sanitized packages, and their conclusions do not replace objective gates.

The19 initial139 findings and32 current findings remain immutable, with separate
Codex dispositions. Missing package/CI/installed proof claims were valid at their
snapshot and expressly withdrawn after completed evidence. No critical/high
current defect or accepted critical/high risk remains. Three residuals cover
software render/settlement cost, cache/GC/fallback boundaries, and test/platform/
unchanged-scope limits. Convergence `ab5db1232000`; closure `c8362113f99e` for
`nia-dense-interaction-a6fc8c43` = READY_WITH_RESIDUAL_RISK, reasons empty.
Later seal commits change documentation only, not executable/build/dependency inputs.

## Remaining boundaries

Full-quality software frames still take material time on dense geometry; pending
camera poses coalesce, and completed same-source frames may lag input. This change
keeps input dispatch responsive rather than promising every injected pose displays.
One noncooperative worker can delay the next result until return, but cannot publish
obsolete source/size output. Import/Save As serialization remains outside measured
camera-input windows. The 64MiB/8MB caps cover named caches, not process memory or
arbitrary inputs; JVM/Skia snapshots and GC timing can cause transient resize peaks.
Best-effort display simplification is unchanged. Shared-host/JIT variance and Xvfb
software evidence are not physical GPU/compositor certification. No external AI
paint call or new semantic paint-quality claim, device install, merge or release.
