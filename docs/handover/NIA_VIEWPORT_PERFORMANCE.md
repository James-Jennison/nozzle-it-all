# Viewport performance — 2026-10-18

The benchmark exercises deliberately adverse cases, not an exhaustive bound over every possible mesh. All compilation, tests and package assembly run on GTHost. CPU/offscreen-Skia pass timings below are **not display/compositor FPS**, input latency or slicing time.

## Source and method

- Baseline: `427e6a400d6ebc5427f5a4b0a0a18e5804114cbd`; the then-installed renderer plus opt-in measurements and internal test visibility only.
- Final source: `6a9155547c60e3aba8623bc342a4a811b161d2c6`. Includes mixed-size shadow scratch reuse, short cache-lock sections, per-viewport synchronized timing, arithmetic median, and expanded correctness regressions. Final5-method benchmark rerun is bound to this source. The slow paired creased-fan control and warmed distributions ran in `f97cb40f`; normal/reference/benchmark source blob identities are asserted unchanged against6a rather than silently representing old measurements as a new execution.
- GTHost AMD EPYC 7452, 64 logical CPUs, Java 21.0.12.1, Gradle 8.13, test heap 4 GiB. Shared host, no CPU reservation; timings vary with load/JIT/GC.
- Three closed tori, each 150,000 triangles, on a 256 mm bed. Production bed, sampled shadow, per-pixel depth/object shading and image presentation at 1920×1080 and 3840×2160. Three warmups and nine measured orbit frames per scenario. At nine samples, reported p95 is the maximum; no confidence interval is claimed.
- Additional adversarial fixtures: 64 near-degenerate full-diagonal slivers; planar and creased fans up to 150,000 faces incident on one vertex; completely offscreen objects. Initial fan numbers are single no-explicit-warmup observations, not cold-JVM guarantees or stable latency distributions; other cases may already have warmed the JVM. Slivers use one warmup and three samples.
- A same-JVM paired control uses frozen original object/shadow/depth kernels and three differently rotated/nonuniformly scaled objects, preventing identical poses from artificially benefiting lighting reuse.

## Measured CPU results

| Scenario | Baseline median | Optimized median | Change |
| --- | ---: | ---: | ---: |
| 450k triangles, 1080p full pass | 213.582 ms | 126.970 ms | 41% lower |
| 450k triangles, 4K full pass | 270.038 ms | 154.447 ms | 43% lower |
| 450k triangles, 4K offscreen | 160.061 ms | 10.964 ms | 93% lower |
| 64 diagonal slivers, 4K | 856.232 ms | 23.700 ms | 36× faster |
| 150k-face planar fan normal preparation | 157,702.825 ms | 96.930 ms | 1,627× faster; single observation |
| Same-JVM distinct poses, 4K | 257.918 ms | 160.106 ms | 38% lower |
| Java thread allocation/full frame | 36,260,160 bytes | 2,088 bytes | 99.994% lower |
| Same-JVM creased150k fan normal preparation | 215,593.609 ms | 1,020.912 ms | 211× faster; single observation |

Raw baseline/intermediate/final XMLs and imported GTHost reports are retained under `~/.local/state/nozzle-it-all/acceptance/20261018-viewport-performance`. Allocation is the measured Java thread allocation only; it excludes native Skia allocation and is not a heap/RSS claim.

Final dense4K p95/max was162.119ms versus270.586ms baseline (9 samples). The same-JVM distinct-pose p95/max was160.767ms versus272.910ms. The creased-fan evidence gap is closed by the paired reference above, but it remains a single no-explicit-warmup comparison, not a distribution-based speedup guarantee. A separate optimized3-warmup/5-sample run measured planar median10.431ms/p9511.119ms and creased median867.473ms/p95878.389ms. This illustrates JIT/GC variance; do not mix cold/no-warmup and warmed numbers into one ratio. The weak-cache probe observed zero of eight released mesh wrappers retained after three requested GCs; this observation is not a deterministic GC guarantee.

## Bottlenecks corrected

1. Quadratic incident-face normal scans: CSR adjacency, a proven common-cone fast path, and bounded-depth direction BVH queries for high-valence creases. Original 40° face filtering remains; no unconditional vertex averaging that would recreate cap artifacts.
2. Per-corner projection, lighting and arrays: project shared vertices once, share exact-normal lighting, directly pack the original opaque sRGB rounding, and reuse viewport-owned primitive scratch buffers. Material and per-face paint remain independent.
3. Empty work: conservative object/triangle offscreen rejection, with no box rejection across the perspective clamp plane.
4. Long/sliver raster bounding boxes: conservative row spans, retaining the original final Float coverage, perspective depth and colour equations. Small triangles bypass span overhead.
5. Shadow boxing/full-viewport compositing: reusable primitive buffers and footprint-bounded transparency layers with antialias padding. Final scratch uses one grow-only largest buffer, not a small size-keyed LRU that can thrash on mixed-size plates; unused tail vertices are zeroed into degenerate triangles. Original triangle sampling/opacity/coverage preserved.
6. Display cache retention/keying: weak values break value-to-key retention, and budget/kind are both in the bounded cache key.

Frozen pre-change kernels provide byte-exact image/coverage regression tests across paint, mirrors, nonuniform scales, orbit poles, clipping and shared geometry. High-valence normals are compared against exhaustive original filtering with numerical tolerance; floating-point accumulation order is not claimed bit-identical.

## Remaining limitations

The dense 450k-triangle software path remains expensive (approximately154–160ms median per measured4K pass). This work does not claim smooth60Hz rendering. A GPU renderer or explicit interaction-level quality policy would be a separate architectural change; this optimization does not silently reduce triangle budgets, paint detail or depth correctness. No owner-defined absolute frame-time target was supplied, and no reviewer-proposed60Hz requirement is silently adopted. Arbitrary adversarial normal distributions may still visit many BVH nodes; measured fixtures do not establish a universal asymptotic bound. Windows, GPU compositor and physical printer acceptance are not covered.

`NOZZLE_VIEWPORT_TIMING=1` opts into local metadata-only Canvas pass summaries, every 30 frames. Default is off; no model contents, network transmission or persistent telemetry. `NIA_VIEWPORT_BENCHMARK=1` enables the opt-in JUnit suite. `scripts/viewport_benchmark_report.py` compares successful XMLs without estimating FPS; `scripts/make_viewport_fixture.py` assembles the isolated 450k-triangle 3MF on GTHost.

The diagnostic reports complete same-size windows only; continuous resizing resets the window and can suppress output until 30 stable-size frames are drawn. Operators may explicitly capture stdout; that is not built-in persistent telemetry. Per-frame lighting scratch is intentionally invalidated each frame, while exact normal groups and active prepared display meshes remain strongly owned by the viewport. Inactive weak-cache variants may be recomputed after collection.

Resize allocates replacement colour/depth storage for the new canvas size (up to about32MB combined), rather than retaining unbounded old-size buffers. The steady-size allocation measurements do not cover a resize sweep. Dead weak-cache slots count toward the eight-entry budget until replacement/eviction; this can evict inactive variants, but cannot collect an actively held prepared mesh. The pre-existing unused private centroid `hitTest` helper is not the pointer selection path; removal is maintenance follow-up, not a performance prerequisite.

Culling safety: if all box corners are clamped, the affine toward coordinate means the entire box is clamped and perspective factor is constant5, so screen extrema occur at corners. If none are clamped, the perspective denominator is positive across the box; x/y are linear-fractional functions whose extrema over a convex box occur at vertices. Mixed clamp boxes bypass rejection. This proof depends on the current Projector formula; a different projection/clamp needs fresh validation.

## Installed Linux acceptance

Package SHA256 `f50cb92c2597ab9562300fa75f49a3b1f3ecbd881d510cf852117e4bc160e0f7`; installed embedded revision6a, jar SHA256 `b3381c5a848f0162d23aaa7d44ae9f6f80f71cc5ac96b28a9497d9a6f73df48d`, and unchanged pinned engine digest verified after owner-authenticated `pkexec dpkg` exit0. The extracted candidate was visually inspected first. No owner window was restarted or manipulated; acceptance used isolated profiles and Xvfb98.

The installed binary completed two30frame stress-plate samples at3408×2046 Canvas: arithmetic medians88.171/88.387ms, p95120.986/108.073ms. Capybara95,934triangle samples measured37.274/35.523/35.697ms median. These are workstation software-Skia Canvas passes, not GTHost comparisons, physical display FPS or hardware-GPU acceptance. Pointer orbit and underneath/zoom screenshots were inspected. Ordered canonical vertex, triangle(includingpaint), and placement attributes match before/after for both450,000triangle stress plate and95,934triangle Capybara. Original supplied model hash unchanged. No printer commands or external semantic paint calls.

Exact6a full desktop/verifyPaxxBaseline:198total,161passed,37conditional skips(including6opt-in benchmark methods),zero failures/errors. Native-enabled focused paint/review run:28/28PASS. Current-source CI39163041293 SUCCESS, including unit/lint and Android native arm64/debug assembly; superseded own CI runs were cancelled, not represented as passing. No merge/release or device install.

The evidence validator rechecks package/jar/engine identity, imported remote XML, exact unchanged creased-control source blobs, saved-project invariants and SHA256 artifact manifest. Manifest SHA256 `55b15ff4a4ca4d63bead44e676c60c6c8d023584fc5a1a3b0652cdd1bb670429`. Broker evidence and independent Synthetic review closure are recorded in the work log; do not infer approval from a still-running reviewer or from older source.

Final broker change `nia-viewport-performance-6a915554`, expanded-manifest evidence `95b42c94b05b` PASS/intact, closure `e29c16a8f8fa` **READY_WITH_RESIDUAL_RISK**, no blocking reasons. Systems GLM5.3, frontend KimiK3 and adversarial DeepSeekV4.1Flash current-source closure cycles COMPLETE; targeted clarification withdrew the final two MEDIUM current-defect claims.21 current findings and separate dispositions retained, plus39 prior findings on explicitly superseded sources; agreement is not substituted for objective validation. Residual risks are the measured dense software cost, benchmark variance and resize/cache/diagnostic behavior, not missing benchmark evidence.

DisplayMesh identity is not globally canonical across explicit cache eviction/weak collection; callers retain active prepared results and may later receive equivalent recomputed data. ShadowScratch's only production caller derives size from sampling; maximum119997vertices occurs at39999triangles. Optional future internal assertions and dead-code cleanup are not current production defect fixes.
