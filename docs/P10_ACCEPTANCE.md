# P10 — Dashboard appearance

Scope: local Android customization, part of M2. Full M2 remains unfinished.

Implemented:
- Dark, Light and System themes; Mint, Blue and Lavender accents.
- Saved visibility and ordering of Camera, Print, Temperatures and Quick tools.
- Default and Print focused layout presets, plus Reset appearance.
- Global preferences on this phone; bounded decoding repairs unknown/duplicate cards
  and restores defaults for invalid choices. Missing cards are appended for upgrades.
- Connection header and command Snackbar remain outside configurable cards.
- Hiding the camera removes its composable and releases its player. Fullscreen
  retains the existing exclusive camera layout and close/back behavior.

Entry: Dashboard or Printers → Customize dashboard. Changes save automatically.

Validation:
- Final protected build/lint/package/source proof PASS; 45 JVM tests passed.
- Initial Razr suite: OK (21 tests), including actual printer read-only camera and
  file preview. Final focused device suite: OK (3 tests), covering light card/page
  separation, hidden-card status feedback and editor changes.
- Final installed APK SHA-256 matches the built APK:
  `5665f6ad7ccfa7c73a249c6098cf9bf6151fda5d901bf9bd5c077f3b91476c21`.
- Light/Blue, Print-first ordering and hidden Quick tools survived process restart
  on the Razr. Focus-checked screenshots were visually inspected. The UI harness
  initially inspected a text label instead of the chip's checked parent; its failed
  assertion is preserved separately from the corrected passing result.
- Light cards are white against a slate-tinted page. Explicit WindowCompat system
  bar appearance produces dark status icons on the settled light screen.
  API reference: https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat
- Phone focus guards stopped before taps when Companion was unavailable; testing
  resumed only after the owner made the phone available. No unrelated screen was
  captured. No printer commands were sent.

All three reviewers completed targeted reviews. The light surface issue was fixed;
DeepSeek retracted the feedback-hiding concern after full source/device evidence.
One LOW residual risk is accepted: a possible transient system-bar icon change
before first composition during cold start; zero-flash startup is not claimed.
Initial closure is superseded by the final system-bar source and evidence cycle
`evidence-cycle-25f3f1b96d24`. Raw earlier failures remain preserved.

Reviewer ledger: mobile-klipper-p10-20260906. Raw cycles and evidence are preserved
under artifacts/p10 and the machine-global broker. No printer mutation is authorized
or needed for this feature. No AI or shared infrastructure changes.
