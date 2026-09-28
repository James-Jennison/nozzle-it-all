# Example evidence and report (simulated: grades nothing)

Generated on 2026-09-28 from commit `afabfe4` with the command-line tool against **simulated** printers. They show
the format and the workflow; they are not evidence for any printer, and every grade they produce is UNVERIFIED.

| File | How it was made |
|---|---|
| `simulated-paxx-u1.nozzle-evidence.zip` | `simulate --suite paxx-u1 --preset PAXX_U1 --level 4`: every test recorded PASS by the simulation |
| `simulated-cosmos-lost-reply.nozzle-evidence.zip` | `simulate --suite cosmos-centauri-carbon --preset COSMOS_CC --level 4 --fault lost_ack:home`: the homing reply is "lost", so `controls-idle` is OUTCOME_UNKNOWN (UNVERIFIED), reviewed, never resent; CANVAS tests SKIPPED (no AFC detected) |
| `compatibility-report.md`, `.json` | `report` over those two bundles plus a deliberately tampered copy of the first (`tampered-copy.nozzle-evidence.zip`, not kept), which is listed under "Bundles not used" |

```bash
./gradlew -q :test-grid:cli --args="verify docs/testgrid/examples/simulated-paxx-u1.nozzle-evidence.zip"
./gradlew -q :test-grid:cli --args="preview docs/testgrid/examples/simulated-cosmos-lost-reply.nozzle-evidence.zip"
```

The simulated printer reports a realistic private address, hostname and API key, and its lost-reply errors carry
them; the bundles contain none of them (checked with `unzip -p … | grep`, and by `EvidenceTest`). Photos are a
placeholder PNG whose embedded location text is removed on attach. Timestamps come from the simulation's virtual clock.
