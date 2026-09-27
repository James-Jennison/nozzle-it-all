# Upstream intake (Advanced Workspace and engine)

Nozzle It All builds on two upstreams, tracked separately in `/mnt/faststorage/Snapmaker-Orca/OrcaSlicer`:

| Name | Remote | Role |
|---|---|---|
| snapmaker | `origin` = github.com/Snapmaker/OrcaSlicer | U1 support baseline. Used only where needed. Its Flutter, cloud and account work is quarantined for the optional Stock U1 adapter. |
| orca | `upstream` = github.com/SoftFever/OrcaSlicer | General slicer improvements, ported selectively and semantically |

**Protected integration branch:** `nozzle/advanced-workspace`. Tooling never commits to it, never force-pushes it, and
never merges into it without a person's review.

## Workflow

```bash
python3 scripts/upstream_intake.py fetch        # read-only for local branches
python3 scripts/upstream_intake.py classify     # docs/upstream/candidates/<date>.md and .json
python3 scripts/upstream_intake.py prepare      # intake/<date> branch in a throwaway worktree: candidates only
# review, build, and test the candidate branch:
#   heavy-build -- cmake --build <build dir> --target nozzle-advanced-workspace
#   scripts/validate.sh, engine golden tests (Desktop / Android / Web)
# then a person merges intake/<date> into nozzle/advanced-workspace and records the reviewed point:
python3 scripts/upstream_intake.py promote snapmaker <sha> --reviewer "<name>"
```

## Classification

The first matching rule wins. Rules match on touched paths and on subject words:

| Class | Suggested action | Meaning |
|---|---|---|
| stock-only | quarantine | Flutter bundle, SSWCP, MQTT, Snapmaker login and account, the 13619 server, SnapLog. It may inform the optional Stock U1 adapter and must never reach the PAXX baseline. Never discarded automatically. |
| paxx-relevant | review | U1 LAN path: Moonraker, native send, filament sync, camera. Reviewed for the PAXX adapter and the Advanced Workspace. |
| slicing | candidate | libslic3r. Must keep the G-code golden tests green on all three platforms. |
| profiles | candidate | Printer and filament profiles. Android and Web bundle flattened copies. |
| workspace-ui | candidate | Orca GUI. Advanced Workspace only, never the primary interface. |
| packaging | review | Checked against Nozzle's identity and the `NOZZLE_*` build options |
| tests | candidate | |
| other | review | |

Commits that mix PAXX-relevant changes with Stock-only ones (for example filament sync v2 touching SSWCP) are ported in
pieces, never whole. `docs/family/STOCK_U1_DEPENDENCY_AUDIT.md` lists the ones seen so far.

## Provenance

Every promoted change gets an entry in [PROVENANCE.md](PROVENANCE.md) recording:

- upstream and commit;
- what was imported and what was adapted;
- the subsystem and platforms it affects;
- test evidence and known divergence;
- whether it touches PAXX, Stock U1, shared UI, slicing, packaging or project interchange.

The `prepare` record (`<date>-prepare.json`) lists exactly what applied and where it stopped.
