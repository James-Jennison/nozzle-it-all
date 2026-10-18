# Handover to Codex (written 2026-10-18)

## Current Codex acceptance update (2026-10-18)

Installed Linux application source is `6a9155547c60e3aba8623bc342a4a811b161d2c6`; the later seal changes documentation/report analysis only, not runtime code. Worst-case viewport benchmarking and bottleneck remediation are complete: shared projection/lighting/scratch, conservative span/offscreen rejection, bounded shadow compositing, high-valence normals, weak-cache lifetime/keying and short critical sections. Multi-select/Select All, auto-paint draft comparison/correction/keep/reset, full pointer orbit underneath, false triangle-shading/visibility fixes, HTTPS/diagnostic guards and stale-source rejection remain included.

All source builds/tests/packages ran on `gthost-build01`. Exact6a full desktop/verifyPaxxBaseline:198total,161passed,37conditional skips(including6opt-in benchmark methods),zero failures/errors; native-enabled focused paint/review28/28PASS. Exact-source CI39163041293 SUCCESS. Package/revision/jar/engine hashes and isolated installed runtime/save-project invariants PASS.450k-triangle4K CPU median270.038->154.447ms;64slivers856.232->23.700ms; paired creased150kface normal preparation215593.609->1020.912ms(single no-explicit-warmup comparison). Not display/compositorFPS. The owner's normal window was not restarted or manipulated; an already-running owner process may still contain older code.

Owner's current reviewer choice is the **Synthetic council**, using existing `council`/`reviewer`, not Agent Canvas/Together. Owner explicitly authorized re-enabling the11 existing Synthetic broker registry flags; transport configs were already enabled and model/privacy/credential settings were not changed. Current change `nia-viewport-performance-6a915554`, expanded-manifest evidence `evidence-cycle-95b42c94b05b`, closure `closure-e29c16a8f8fa`: **READY_WITH_RESIDUAL_RISK**, no blocking reasons. Three residual risks: MEDIUM dense4K software CPU headroom, LOW benchmark/JIT/shared-host sampling limits, LOW resize/inactive-cache/stable-window diagnostic behavior. Three exact-model Synthetic closure reviews and targeted clarification COMPLETE. Immutable findings and separate Codex dispositions preserve dissent; no unanimity/60Hz guarantee inferred.

Performance evidence folder: `/home/jjennison/.local/state/nozzle-it-all/acceptance/20261018-viewport-performance`; SHA256 manifest `55b15ff4a4ca4d63bead44e676c60c6c8d023584fc5a1a3b0652cdd1bb670429`. Installed450k stress and95934face Capybara before/after orbit projects preserve ordered geometry/topology/paint/placement exactly; underneath/zoom screenshots inspected. See `docs/handover/NIA_VIEWPORT_PERFORMANCE.md` for measurement method/results/limits, `NIA_REVIEW_FOLLOWUPS.md` for optional work, and work log for provenance. Earlier detailed paint/Apply/Undo/Redo acceptance remains in `acceptance/20261018-synthetic-final-6bf84dfe`. No external semantic paint-quality claim, Windows/GPU-compositor acceptance, printer command, device install, PR merge or release. Owner decides merges.

You are taking over Nozzle It All (NIA) Desktop work from Claude. This file is the starting point. The full step-by-step history is in
`docs/handover/NIA_WORK_LOG.md` (every step, with what was and was not verified) and the earlier gap check against Orca 2.5.0 is in
`docs/handover/NIA_GAP_REPORT.md`. Read the last 40 lines of the work log first.

## The owner's rules (they are strict; they come from the owner's own global instructions)
1. **All building happens on the GTHost server**, never on the workstation: `ssh jjennison@100.87.130.114` (Tailscale, key auth, 64 cores).
   Sync the source there, build there, copy outputs back. If it is unreachable, say so and ask; do not build locally.
2. **Finish what is approved.** When the owner says "do it", do the whole chain (build, test, install, report). Do not re-ask about closely related
   sub-steps. Do not end a turn announcing future work: do the work, or end with finished results or a real question.
3. **Install the app yourself after each change** (see "Install" below) and say what you verified in the running app and what you did not.
4. **Never delete or clean up** files, build trees, images or branches that the owner has not named. Check the target before installing or
   uninstalling on a device: `adb -s <serial> shell getprop ro.product.model`. For Nozzle It All device tests use the Motorola Razr
   (ZP22235MHM), never the LCARS Tablet (HA2G1M6X).
5. **Reply to the owner's message before starting work**, and **keep a verbose log** (`docs/handover/NIA_WORK_LOG.md`, newest entries at the
   bottom, one line per step, honest about what was not verified). The owner reads it. Do not claim something is installed or verified unless
   you checked (dpkg status plus a class in the installed jar; a screenshot of the running app).
6. Be honest about mistakes. Earlier the owner caught a false claim ("reports every slot empty") and was rightly angry.
7. Pronouns: use they/them unless told otherwise.

## Repos and branches
- NIA: `/mnt/faststorage/Nozzle It All`, branch `feat/engine-orca-2.5`, remote `github`, **PR #62** (open, mergeable; its CI run was in progress).
  Latest commit at handover: `9a4aec24`. Working tree is clean except untracked notes (`NIA_ENGINE_MIGRATION.md`, `PORT_PLAN.md`,
  `PR_DRAFT_ai_auto_paint.md`, a sample 3MF and PNG); leave them alone.
- Engine: `/mnt/faststorage/nozzle-engine-2.5`, branch `engine/orca-2.5-base`, **PR #13** into `main` (open, mergeable). Merge commit `2340ff3b`
  (strategy "ours": tree is Orca 2.5.0 plus the Nozzle README and only the engine CI workflow). **Merge with a merge commit, not a squash.**
  Merge order the owner wants: engine #13 first, then NIA #62. The owner decides when to merge; do not merge on your own.
- **Open problem: engine PR #13's CI job "Desktop engine" fails in about 11 seconds** (run 39097059959). Its deps prefix
  `~/nozzle-engine-ci/desktop-deps` was missing on gthost; I created a symlink to
  `~/combined-orca/src/deps/build/OrcaSlicer_dep/usr/local` and asked for a re-run, but **I never read the re-run's result**. Check it
  (`gh run list --repo James-Jennison/nozzle-engine`), and if it still fails read `gh run view <id> --log-failed`.

## Build, test, package, install (the loop that has been used all along)
```bash
cd "/mnt/faststorage/Nozzle It All"
# sync WITHOUT --delete (a --delete once wiped gthost's local.properties; its sdk.dir must be /opt/android-sdk)
rsync -a --exclude .git --exclude build --exclude .gradle --exclude .kotlin --exclude dist --exclude .cxx --exclude local.properties ./ jjennison@100.87.130.114:nia-pkg3/
ssh jjennison@100.87.130.114 'cd nia-pkg3 && ./gradlew --no-daemon --offline :desktop:test -q 2>&1 | tail -25'
ssh jjennison@100.87.130.114 'cd nia-pkg3 && scripts/package_linux.sh --engine $HOME/nozzle-engine-2.5/build-headless/android_jni/nozzle-engine > ~/pkgN.log 2>&1; tail -1 ~/pkgN.log'
mkdir -p /tmp/claude-1000/pkgN && scp jjennison@100.87.130.114:nia-pkg3/dist/linux/nozzle-it-all_0.1.0_amd64.deb /tmp/claude-1000/pkgN/
```
- Adapter changes also need `:adapter-paxx:test` etc. All desktop tests passed at handover (JUnit 4: `org.junit.Assert.*`, `org.junit.Test`).
- **Look at it before installing:** `dpkg -x` the deb into a scratch folder, start `Xvfb :99 -screen 0 1600x1000x24`, run
  `NOZZLE_HOME=<empty dir> <x>/opt/nozzle-it-all/bin/nozzle-it-all` (the env var gives the app its own config/data, so the owner's real settings are
  untouched), drive it with `xdotool` and capture with ImageMagick `import -window root`. A fresh data folder starts empty; "Find my printer"
  then "Use" adds the owner's real U1 read-only for a live view. Never press Pause/Cancel/Start on the owner's printers.
- **Install:** `timeout 250 pkexec dpkg -i <deb>`. It needs the owner to type their password; if nobody is at the machine it times out. Then
  give the owner `sudo dpkg -i <deb>` to run in their own terminal. Verify with `dpkg -s nozzle-it-all | grep Status` (want `install ok installed`) and
  check a new class is in the installed jar: `for j in /opt/nozzle-it-all/lib/app/desktop-*.jar; do unzip -l "$j" | grep -q <Class> && echo yes; done`.
- Do not use `pkill -f` with a pattern that appears in your own command line (it killed the shell once).

## What the owner asked for and where it stands
The owner wants NIA Desktop to feel first-class and turnkey (like Bambu Studio / Orca / Prusa), "Orca-like but unique", as **one job-centred flow**
(chosen direction): pick a printer or a model, then plate, slice, send, watch, with a job bar always visible.

Done, installed and pushed on `feat/engine-orca-2.5` (newest first; all in `desktop/src/main/kotlin/com/nozzleitall/desktop/`):
- `9a4aec24` first-run: advice "You haven't added a printer yet" with Find my printer (opens Printers already scanning, `AppState.findPrinterRequested`),
  Workspace empty state button, newly added printer auto-chosen in the Workspace, `Prepare a print` on each printer card, queue beside the cards.
- `59256206` preview colours by Line type / Filament / Speed with a legend (`prepare/GcodePreview.kt` reads `;TYPE:` and feed; `PreviewLegend` in `PrepareScreen.kt`).
- `1fb604c5` **Workspace** (`prepare/PrepareScreen.kt`): top bar (project, Save, add model, Projects, Printer / Filament / Print-profile chips that open a right
  drawer, Plate/Preview, Slice then time+grams then Send), 300 px object list on the left, big plate, drawers PRINTER/FILAMENT/PROCESS/SEND/CUT/AI.
  `screens/JobBar.kt`: a slim bar on every screen while anything prints (click opens that printer). Rail is Workspace / Printers / Settings;
  Projects is reached from File and the Workspace.
- `238a3e09` Monitor top band with time left and finish time (`screens/JobEta.kt`), Pause/Resume/Cancel only when allowed; Settings in three tabs.
- `c861105d`, `03d3b265` network scan (`discovery/LanScan.kt`): private /24, ports 7125 then 80, fingerprint de-duplication (one printer on two addresses).
- `cb0c5d66` plate handles (`prepare/PlateHandles.kt`, `PlateViewer.kt`): drag an object to move, ring knob to turn (Ctrl snaps 15 degrees), corner knob to resize.
- Earlier: advice strip, print queue, print history and notifications, one printer page, AI auto-paint with a review step, CC1 filament from AFC lanes,
  Arrange fix, Edit printer, Sync filament. See the work log.

Verified in the running app (test copy, owner's real U1): first-run path, job bar, Prepare a print, Monitor band, Settings tabs, scan results, plate handles,
preview Line type and Speed views. **Not verified:** the queue side panel with items, the CC1 card, narrow windows, preview Filament view, Resume button,
off-plate and open-edge advice in the running app, real print-end notification and History entry, queue Start on hardware.

## Suggested next steps (in the order I recommended)
1. **Multi-select and Select all.** Selection is a single `PrepareState.selected: Int?` everywhere (move/rotate/scale handles, delete, undo in `PlateHistory`,
   painting). Plan: a `selectedIds` set with `selected` as the primary, Edit > Select all / Deselect, shift/ctrl-click in the plate and list, and apply move/rotate/scale to the set.
2. Plate lighting / bed polish and preview time and filament estimates next to the plate.
3. Remaining gap-report items: Preferences, config bundle import/export, Export all STL, the 6 plate-level options, Android per-object settings / overrides /
   option states / undo, other calibration kinds, real-U1 run of the Orca desktop port. See `docs/handover/NIA_GAP_REPORT.md`.
4. Resolve engine PR #13 CI (above), then the owner decides the merge order. The upstream AI auto-paint PR is undecided (draft in `PR_DRAFT_ai_auto_paint.md`).

## Things that will bite you
- Compose Desktop (Skia). Use `desktop/src/main/kotlin/com/nozzleitall/desktop/ui/` components (`NzButton`, `Txt`, `Card`, `Banner`, `Field`) and `settings/` (`DenseInput`, `DenseSelect`, `IconToggle`).
- `Txt` has no `softWrap`. `Destination.railItem` drives which rail item is lit. `Fleet` owns printers, polling, history, queue, notifications.
- Moonraker adapters: fields requested come back `null` rather than absent (the CC1 bug). The CC1 serves Moonraker on port 80, the U1 on 7125; a U1 can answer on two IPs.
- Engine option `nozzle_volume_type` takes "Standard" / "High Flow".
- The AI auto-paint command default is `claude -p ... --no-session-persistence` (otherwise Claude Code fires a "Claude is ready" notification per call).
- Commit messages end with a `Co-Authored-By:` line for whoever wrote it; commit and push each finished step (the owner expects that).
