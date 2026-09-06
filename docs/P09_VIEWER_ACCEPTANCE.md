# P09 — Read-only toolpath viewer improvements

Scope: local G-code viewing during an active print. No printer commands or file
mutations are added. Live position tracking remains pending: the temporary workspace
has no verified active-file identity or byte-position mapping. Manual highlighting
is explicitly labelled as a selected displayed path, not live progress.

Implemented:
- Layer slider plus previous/next buttons with endpoint guards.
- Stable extrusion bounds across layers; pinch zoom and buttons (1–12×), bounded
  drag panning, viewport clipping and Fit model reset.
- Optional dashed linear travel paths, solid extrusion and a highlighted selected
  displayed path with endpoint marker. Selection resets on layer changes.
- Travel coordinates honor units, absolute/relative modes and G92 XY offsets.
  Travel belongs to the preceding extrusion layer, with initial travel on layer 1.
  Travel arcs are omitted and disclosed instead of drawn as straight chords.
- Existing approximate XY extrusion/IJ arc parsing limits remain. At most 120,000
  extrusion and 60,000 travel segments are retained, with sampling under load;
  each layer draws at most 5,000 of each type. Both sampling stages are disclosed.
  File size, line length, coordinate, layer and arc budgets remain enforced.
- Parser cancellation now checks each input line as well as each buffer/arc step.

Validation:
- Protected build, lint, packaging and source proof passed; 67 JVM tests with zero
  failures, errors or skips. Independent broker evidence also passed.
- Five final Razr instrumentation tests passed (37.313s): viewer controls and
  selection reset, local fixture download/preview cleanup, real Dragon-file
  download/preview and Android shared-file import.
- Actual MainActivity import, preview, travel toggle, zoom and Fit model were
  exercised through app-scoped UI controls; screenshot inspected.
- Existing 12,503,499-byte Dragon file was downloaded read-only and rendered as
  303 extrusion-height layers. Layer 2, dashed travels, extrusion, selected segment
  and sampling warnings were visually inspected on the Razr. Screenshot:
  artifacts/p09/dragon-layer2.png. No printer file or print-state mutation occurred.
- Installed APK identity matches validated source:
  86d6c02e4f75a2a8a632bc8b123ff568a81e6af4d5a50f0507199b55b32fe5b6.

Review remediation: non-extruding arcs now bypass center/radius/step validation
before omission, with endpoint bounds retained. Extruding R/helical/inconsistent
arcs retain the existing explicit-rejection contract and regression tests.
Screenshots precede this parser-only correction; UI source is unchanged and the
final device suite validates the corrected build.

Claude, Gemini and DeepSeek completed final targeted review with no findings
(review-cycle-5e967cf99e5d). The invalid-phase submission remains a failed record,
explicitly superseded by the corrected completed cycle. Final closure: READY,
closure-e8d981103f31. Independent final evidence: evidence-cycle-848576e9b0e0.
Evidence is in artifacts/p09. Full P09 live tracking and full M2 remain pending.
