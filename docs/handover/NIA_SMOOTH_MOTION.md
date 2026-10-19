# Smooth viewport motion — 2026-10-18

Installed executable source: `9f068d34c862c1ec0f7255371f1d68e44d22ddf8`.
Later documentation commits do not change the executable/build inputs. This is a
Linux desktop installation, not a merge, release, printer or Android deployment.

## Changes

- Optional packaged Linux x64 EGL/GLES3 backend caches full-detail mesh VBOs and
  executes transforms, crease-normal lighting and per-fragment depth on the GPU.
  Camera motion does not re-upload unchanged geometry/materials. The existing
  preparation budget is unchanged; no additional motion-time decimation or
  resolution reduction is used. CPU fallback remains available.
- Worker rendering no longer waits for a Main-thread round trip between frames.
  One running request, one conflated pending request and one atomic waiting image
  bound the pipeline. Replaced/rejected images are closed; source/size changes
  cannot publish stale results. Camera-only updates may show an already completed
  frame to avoid starvation. Picking/painting uses the displayed camera.
- Fresh immutable BGRA image ownership avoids reusable-buffer corruption. Opaque
  bed/background composition avoids a full-screen Main-thread alpha blend.
  Native context ownership, revision checks, teardown and failure demotion are
  explicit. The VBO cache limit is 256 MiB per viewport, not a process memory cap.
- A default-off, doubly opt-in camera motion probe permits real-window cadence
  measurement where Wayland rejects XTest input. It changes camera state only.

## Provenance and tests

All source compilation, tests and packaging ran on `gthost-build01` from clean,
exact source9f. Java21.0.12.1, Gradle8.13, Android SDK `/opt/android-sdk`, G++15.2.0.
Exact-source CI39177291985 completed SUCCESS.

| Gate | Result |
| --- | --- |
| Desktop + verifyPaxxBaseline | 235 total;195 passed;40 conditional skips;0 failures/errors |
| Entire native-enabled desktop suite | 235 total;226 passed;9 opt-in skips;0 failures/errors |
| EGL API quality/performance suite on GTHost llvmpipe | 10/10 passed |
| Frame ownership/concurrency regressions | 18/18 passed within both full suites |
| Explicit CPU-fallback composite regression | 1/1 passed |
| Current GTHost-compiled GPU tests against installed NVIDIA library/jar | 10/10 passed |

The 10 GPU tests include frozen CPU-oracle quality comparisons across16
orientation/mirror/selection cases, real-depth/object-order checks, immutable
snapshots, resize/cache/lifetime/thread-migration, invalid-camera recovery and
opaque/layer-only output. API microbenchmark timing on GTHost llvmpipe is not
NVIDIA window-presentation cadence.

Installed and candidate package/jar/native/engine hashes match:

| Artifact | SHA256 |
| --- | --- |
| Debian package | `9a317da160e76524a175f4608172d5b5a31631011d0d87180614ca67e70a31ea` |
| Application jar | `40ea463a200ede920981b0c8a04e487c0f1fd26cd50702897f036d5f2211aa61` |
| Native viewport library | `65c4981ff18ee1906725e39d6074ff427f7f699f5503977925437ec711aa8a25` |
| Unchanged slicing engine | `d66da0a39dcf427079ab09e88b6692f6645e3a662aa0415ebee6c758d2e8d015` |

Desktop authentication was approved by the owner; `pkexec dpkg -i` exited0.
`dpkg-query`, embedded source revision, installed binary hashes, dependencies and
GNU runtime copyright were checked separately. No ambiguous old build was used.

## Installed actual-desktop motion

Fresh task-owned processes used `/opt/nozzle-it-all/bin/nozzle-it-all`, isolated
profiles and PID/executable-verified windows. Actual dual1080p desktop root is
3840x1080; the owned1920x1010 window had a1488x896 viewport. Skiko UI requested
OPENGL, and native viewport reported NVIDIA RTX3050. The owner's existing window
was not restarted or manipulated and can still hold the older loaded build.

| Scene/motion | Complete image-draw tickets/s | Cursor-free visible pixel updates/s | p95 complete gap | p95 request→draw |
| --- | ---: | ---: | ---: | ---: |
| 450k orbit | 59.19 | 59.2 | 17.77ms | 18.07ms |
| 450k zoom | 57.81 | 57.6 | 18.39ms | 17.97ms |
| 1.2M orbit | 57.53 | 57.2 | 19.78ms | 19.47ms |
| 1.2M zoom | 57.90 | 57.8 | 17.86ms | 17.68ms |

Each mode completed360 attempted camera updates at a60Hz target. Counts derive
from matching unique BEGIN/END metadata; cancellation cannot emit END. Clamping
and coalescing mean360 attempts are not360 distinct displayed frames. Independent
60Hz owned-window capture (mouse disabled) measures actual changing pixels over
the inner5sec. Complete-ticket diagnostics separately prevent partial-blit/cursor
activity from masquerading as a complete model frame. This is not physical
scanout/VSYNC certification, a locked60fps guarantee, or Wayland OS-injected input
proof. Actual pointer handler/dispatch tests run separately on Xvfb. The largest
installed450k orbit gap was70.41ms despite its17.77ms p95; rare hitches remain.
Final installed settlements were17.6–19.2ms, excluding the stability wait.

## Retained worst-case limit and acceptance decision

The separate4K virtual-display stress uses a3408x2046 viewport, Skiko SOFTWARE UI
and the NVIDIA native renderer. Current1.2M orbit/zoom rates are24.23/27.69
complete frames/s,26.6/33.4 visible updates/s; p95 complete gaps65.78/62.64ms.
The self-selected30fps stress gate is **NOT_MET**. Baselinea6 under the same
cursor-free stress setup was3.24/4.01 complete frames/s. No comparable old
actual-desktop camera-probe baseline exists; do not infer its physical FPS.

The owner supplied a qualitative smoothness requirement, not a numeric display
target. An explicit choice is pending: accept actual accelerated desktop results
with this4K software limitation disclosed, or require the4K software stress case
also to reach30fps. No silent waiver or completed milestone is claimed while
that decision remains pending.

## Review, runtime invariants and evidence

Current-source Synthetic reviews completed independently on pinned
`hf:zai-org/GLM-5.3`, `hf:moonshotai/Kimi-K3` and
`hf:deepseek-ai/DeepSeek-V4.1-Flash`:
`review-cycle-9004ec442e1b`, `review-cycle-f8b6c010a844`,
`review-cycle-02722fdd3640`. Their17 findings have separate durable Codex
dispositions; no reviewer agreement substitutes for tests. Three MEDIUM findings
refer to the same pending owner acceptance choice. A claimed CPU-test portability
defect was rejected with exact projected-bed geometry and forced-CPU1/1 proof;
measurement-count/semantics clarifications were implemented in the harness.
Installed-evidence closure reviews also completed on the same exact model pins:
`review-cycle-fdfd83aa9a92` (systems), `review-cycle-fd1d149126dd` (frontend),
`review-cycle-8b3969d213b9` (adversarial). Requested/returned IDs match and all
finished normally. Their14 additional findings retain the criterion question,
withdraw pending-installed-proof notices and confirm/document existing limits;
no new source defect was demonstrated. All31 current-change findings have
separate dispositions. The three original MEDIUM acceptance-choice findings
remain unresolved; duplicate closure findings link to them rather than conceal
the pending decision. No reviewer unanimity or milestone approval is inferred.

Installed Xvfb pointer handler/dispatch checks completed40zoom+40orbit inputs
per scene.450k p95 zoom/orbit3.60/29.52ms;1.2M3.73/28.84ms. Resize, SelectAll,
stable screenshots and SaveAs passed. Saved before/after ordered attributes are
equal (vertices, triangle indices/paint and build placements):

| Scene | Triangles | SHA256 of canonical ordered attributes |
| --- | ---: | --- |
| Three objects | 450000 | `48fdd1f64184d5b8acbbaf1f1e63b8b47694d358ac0811a902a5cc6b6fdc4f72` |
| Eight objects | 1200000 | `56a1eb9f6f53ce5a0b8c12346eb09cd1e6a5588719ce17ed86cc68c0291d042a` |
| Capybara | 95934 | `1c988ab7f1e9eb75e62c26e31bcb7393ac3a2b61781e91e59b4d3aeb16cb6031` |

All8-selected and flat Capybara underside images were visually inspected. Flat
caps no longer show the earlier artificial triangle-fan shading. The initial
standalone NVIDIA JUnit invocation raced test-class extraction and failed class
discovery; that harness failure is preserved. After extraction, the same current
GTHost-compiled10 tests passed against the exact installed native/jar. No source
was compiled on the workstation and no test assertion was weakened.

Evidence lives at
`/home/jjennison/.local/state/nozzle-it-all/acceptance/20261018-smooth-motion`.
Sealed artifact manifest SHA256:
`9866d2002c655d264c22a0f389e98ddcbfad84abb20d715e9912a2173b383c7c`.
Objective local validation and broker `evidence-cycle-e20f3c3eaedf` both PASS;
both broker checks have intact locked log artifacts. Their PASS certifies the
source/build/install/actual-desktop/invariant evidence, **not** a4Kstress waiver.
Convergence `convergence-90fd123b83e9`; closure `closure-758ab9e29dbd` is
**NOT_READY**, solely for the three original MEDIUM findings representing the
same outstanding owner criterion decision. All6 required current-source review
cycles are COMPLETE;31 findings have separate dispositions. Four residuals are
recorded: the unaccepted4Kcriterion limitation and three disclosed lower-severity
measurement/cache/engine boundaries. Nothing is silently converted to READY.

Raw source-bound logs, reports, package, recordings, screenshots and saved-project
comparisons are retained, including failed prototypes. The unchanged slicing
engine had one superseded AFC test failure before graphics JNI initialization;
isolated and later full native suites passed, but its root cause is unknown. It
is not rewritten as PASS or claimed fixed by this rendering change.
The original failed suite is retained as `nia-smooth-direct-nativeall.log/.tgz`;
the two isolated passing AFC methods as `nia-smooth-afc-isolated.log/.tgz`.

CPU fallback/arbitrary hardware, oversized active VBO cache thrash, material
re-upload, Windows/Android graphics and semantic paint-color correctness are not
30fps claims. The Capybara fresh profile has a different filament palette from
the owner's brown palette; saved canonical paint attributes, not that palette's
semantic color appearance, are the relevant invariant.
