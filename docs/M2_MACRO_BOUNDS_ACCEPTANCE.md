# M2 macro parameter bounds correction

Local correction only; device installation and live macro acceptance are pending.
Full M2 remains incomplete.

MacroTools previously compared input via Double but emitted the exact BigDecimal
string. For example,300.00000000000000000000000001 could round onto a maximum300
for validation, then be sent above that maximum. Definitions also lost decimal
precision before checking default values.

MacroParameter now retains BigDecimal minimum, maximum and default. Construction
validates names, ordered bounds, ±1000000 magnitude and bounded decimal rendering.
Command construction compares the exact emitted numeric value against those
bounds and rejects duplicate or excessive definitions. MacroForm displays exact
bounds and renders defaults in plain notation, including small scientific-form
stored defaults. Original option strings and JSON storage remain unchanged.

Definition decimals must have scale -6..30, precision at most32 and normalized
plain length at most32. Previously accepted definitions outside those limits now
fail closed with a validation error. No saved definition is silently rewritten.
These bounds prevent excessive exponent expansion and keep defaults usable in
the existing32-character command-input field. Existing command input still
requires plain numeric syntax; scientific notation is permitted only in bounded
stored definitions.

Validation: evidence-cycle-54283ed28228 passed the protected JVM/lint/package/source
proof entrypoint.91 tests passed, zero failures/errors/skips. Four new regressions
cover precision at both limits, exact definition/default bounds, bounded exponent
normalization and direct-construction/name/duplicate guards.

APK SHA256:19860c10b4b8aec2e3295d52e87b5f8955b5884703bdc909bad3abcc4e20a9ca
Source manifest:091331703d7d3544986c9c04aafeab11ac26986c6bb05eee23984da858e0a98e
Artifact:artifacts/m2-macro-bounds/klipper-companion-macro-bounds-debug.apk
Review cycle:review-cycle-217de397b990: all three reviewers COMPLETE.
Gemini and DeepSeek reported NO_FINDINGS. Claude LOW error-wording suggestion
was assessed NOT APPLICABLE as a defect (broker disposition REJECTED): the message
correctly advises valid replacement input; exact magnitude is unconditionally
enforced in construction and covered by regression. Finding finding-650fa2a95552
and disposition finding-disposition-09a12de97bdf retain that assessment separately.
Local correction closure-b2c67e8f6ae5: READY; this does not accept full M2.
No install or live printer command was performed for this correction. The prior
heater APK remains installed on the Razr2023. Original unrelated README and
FREE_FEATURE_SCOPE work is preserved.

## Owner-approved installation and device checks

The owner approved installation and local UI inspection. Installation on the
Razr2023 succeeded; remote APK SHA256 matched the reviewed19860c10... build.
Installed identity is recorded in artifacts/m2-macro-bounds/installed-identity.json.

Four scoped form/confirmation fixture cases passed across the initial run and
one targeted rerun: exact upper-bound rejection and boundary acceptance, small
scientific default rendered as plain decimal, injection rejection, and dispatch
disabled while printing. Fixture callbacks only collect commands in memory.
The initial screenshot failed because onRoot matched the activity and dialog;
its instrumentation-only selector was corrected to isDialog and the affected
case passed in2.526seconds. The initial failure remains preserved. No production
source or production APK changed during this test correction.

The captured macro dialog was visually inspected. Exact bounds and validation
feedback are readable; the existing single-line input scrolls long numbers.
The normal MainActivity was opened and verified at the offline dashboard after
tests. No live connection or printer command was initiated during this install
acceptance. Installed macro-bounds correction is verified; live macro acceptance,
other M2 controls and full M2 remain pending.
