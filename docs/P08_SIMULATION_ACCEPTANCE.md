# P08 — File workflow simulation

Owner-approved scope: upload, rename and delete workflows against a simulated
server while the real printer is active. This is not full P08/live acceptance.

Files → Preview file management opens an isolated in-memory server. It uses a
built-in 10-byte G-code sample and disposable example filenames. No personal
content is imported and no printer service, network address or filesystem is
connected to the simulation. Existing production FileTransfer remains read-only.

Implemented:
- Review and explicit confirmation for uploads, renames and deletes.
- Safe relative G-code paths and collision rejection, single-use confirmations,
  revision changes invalidating prepared operations, copied upload payloads.
- Cancellation invalidates the pending operation before commit; simulated
  precommit interruption leaves files unchanged. Lost acknowledgement deliberately
  commits once but reports uncertainty, consumes the confirmation, and requires
  refresh before another review. No automatic retry exists.
- Readback updates the visible file list. Changes are discarded when the simulator
  closes. Memory is limited to 1 MiB per upload, 16 MiB total and 100 stored files,
  with at most one additional pending upload. Competing-client writes obey quotas.

The simulator provides atomic revision checking solely to exercise desired UI
behavior. Moonraker does not promise this contract and may overwrite destinations
on move/upload. These tests do not establish a no-overwrite guarantee on Moonraker.
Live transport, overwrite policy, active-file protections, real interrupted transfer
behavior and owner-approved printer acceptance remain pending.

Protocol reference: https://moonraker.readthedocs.io/en/latest/external_api/file_manager/
Existing test-only HTTP mutation prototypes remain separate and do not confer a
production capability or prove atomic collision handling.

Validation:
- Protected build/lint/package/source proof passed; 61 JVM tests, zero failures,
  errors or skips. Final broker evidence cycle: evidence-cycle-745fd6665ef2.
- Razr device suite: OK (7 tests), 26.522s. Covers upload review/confirmation,
  collision rejection, edit invalidation, stale delete rejection, lost-ack refresh,
  cancellation during operation, interrupted upload, successful rename/delete.
- Initial test assertions ambiguously matched list and editable filename nodes;
  corrected to select noneditable list entries. A clock-frozen cancellation test
  stalled and was explicitly stopped; the normal-clock Cancel-button test passed.
  Original failures/stopped-test outputs remain preserved separately.
- APK installed on Razr matches validated build:
  `9ccb3c7046e447f816442310a1ce6ff561de3b60ac16d3ab009a90aeeccd2c6a`.

- Final defensive cancellation-selector refinement: targeted Razr rerun OK (1 test),
  2.755s; protected validation passed again with the same production APK hash.
- Files-tab entry and upload-confirmation success were checked on the Razr;
  workflow.png and upload-result.png were visually inspected.

Claude, Gemini and DeepSeek completed independent and targeted reviews. Accepted
findings about cancellation/interruption coverage and competing-client quotas were
corrected and validated. A later selector concern was not applicable to UPLOAD,
which has no editable source field; the matcher was nevertheless narrowed and
retested. The cancellation UI test uses real timing and may be sensitive to device
load; it passed on the authorized Razr.

Simulation-scope closure: READY, closure-fff7f673066d. Evidence: artifacts/p08;
reviewer change mobile-klipper-p08-simulation-20260906. No actual printer files
were changed. Full live P08 acceptance remains pending.
