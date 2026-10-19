# Environmental monitoring — Synthetic architecture review

Date: 2026-10-18, America/Los_Angeles. **Design review only.**
No implementation milestone is authorized. The current application, installed
viewport build, sensor/gateway settings and platform services are unchanged.

## Proposal for owner approval

[Architecture proposal](ENVIRONMENT_MONITORING_ARCHITECTURE.md), SHA256
`dfbdd62225e24e89e850fa7326f8a877993e4ec0edba3b08a09760742fa5700d`.
Current-source assessment baseline: `ed50eae76537b32e9b898812c48c1411b0102d37`.
Proposal approval and implementation authorization are separate owner actions;
the approval-request artifact currently records false and an empty milestone list.

## Review provenance

Independent initial review `review-cycle-071c0ccfa241` completed all three seats.
Targeted remediation review `review-cycle-349560cea827` and final closure review
`review-cycle-c4580d55ee78` also completed all three seats, using revised proposals
and separate Codex dispositions, not edited reviewer findings. Actual model pins:

| Seat | Requested / returned model |
| --- | --- |
| Systems / architecture | `hf:zai-org/GLM-5.3` / `zai-org/GLM-5.3` |
| Platform / frontend / UX | `hf:moonshotai/Kimi-K3` / `moonshotai/Kimi-K3` |
| Adversarial / security / failure analysis | `hf:deepseek-ai/DeepSeek-V4.1-Flash` / `deepseek-ai/DeepSeek-V4.1-Flash` |

The same sanitized initial package went independently to each reviewer. No paid
fallback, model substitution, provider configuration/credential change or browser
reviewer transport was used. The adversarial remediation first attempt ended in
a Synthetic request failure, without substantive output; the broker's permitted
same-job retry completed successfully and preserves the failure and pinned model.
All nine substantive reviewer responses are persisted COMPLETE with matching
requested/returned IDs and normal stop. No surviving actionable design defect
was reported in any of the three final reviews.

## Findings and design remediation

The initial26 findings (1 BLOCKER,6 HIGH,12 MEDIUM,5 LOW,2 INFORMATIONAL) are
immutable broker evidence; their separate Codex
dispositions and exact original wording are in `remediation.json`. These are
architectural corrections, not claims of implemented runtime fixes:

| Theme | Corrected design |
| --- | --- |
| Consent and off-state | One host EnvironmentalGate, inert compiled-in descriptors, entry-point allowlists and no automatic history/provider/network/radio activity; explicit read-history/export/delete are separate narrowly scoped gestures |
| Revocation and crash recovery | Minimal durable control record, immediate stop request, serialized admission/commit/revoke, atomic persistence and crash points, new boot nonce/idle startup, stale outbox quarantine and finite remote leases |
| Time, duplicates and analytics | Stable event-key conflicts, proven source counter era, deterministic versioned canonicalization, effective-time binding priorities, gap/failover markers, snapshot/late-data revisions, sample-age-unknown exclusion and absent-by-default AnalyticsPolicy |
| Platform behavior | Exact Android28–30/31–36 permission matrix, visible-Activity-only collection/no FGS initially, cancellation of late permission grants, no automatic restart, BlueZ owned sessions and post-filtering |
| Endpoint security | Strict host-owned EndpointPolicy independent of existing global Android cleartext flag; pinned validated DNS addresses+TLS hostname, no redirects/proxy fallback, scoped local unauthenticated exceptions, no secret-bearing plaintext |
| Deletion and privacy | Explicit local per-store transaction graph, vault/diagnostic/cache cleanup failures, new incarnation/replay rejection, separate archival restore, remote unconfirmed state and user-owned export limits |
| Qualification and thermal safety | Per-capability model/firmware/channel evidence, versioned cadence/calibration, unresolved RH/identity/battery evidence visible; unknown or insufficient operating envelope cannot become qualified dryer deployment by warning waiver |
| UI and evidence | Accessible canonical age/qualification/stop/delete/clock labels, DIRECT/HANDOFF/UPSTREAM/FUTURE taxonomy, per-milestone reproducible evidence list |
| Ownership and source traceability | Missing SDK/slot/material excerpts supplied, namespace/dependency direction fixed, owner-only choices and milestone gates listed explicitly |

Two recommendations were narrowed rather than copied blindly: BlueZ discovery is
shared across clients, so another active client is not itself an error; and a
warning/owner waiver cannot alter a manufacturer's supported operating envelope.
Explicit manual local history/export/delete is not the automatic off-state I/O
that the owner forbids. These interpretations are recorded in separate dispositions.

The first remediation review added12 entries:5 MEDIUM design clarifications,
3 LOW notes and4 INFORMATIONAL observations. The final proposal closes these with
an opaque grant/validation contract, corrupt/unknown-version control recovery,
policy-version compatibility, named DeleteResume and read allowlists, vault-failure
behavior, and the precise API31+ legacy-location/filter-testing distinction.
The final reviews contain11 INFORMATIONAL future-gate/verification observations,
not remaining design defects. All49 entries have separate resolved dispositions.
The initial BLOCKER/HIGH items were resolved by actual prose corrections and
targeted re-review, not accepted as unresolved high risks or decided by vote.

## Approval boundary and remaining future gates

The requested decision is architecture approval only. Any implementation starts
only after separate explicit authorization of a bounded milestone. Section10.1
names later owner decisions: numerical resource/retention/hardware budgets, provider
subset/order, experimental limits, HA token/endpoints/topics/entities, background
collection, remote sync/cloud and optional analytics/alerts. Conservative defaults
remain off/idle, diagnostic/unqualified or insufficient evidence, as appropriate.

No NIA environmental adapter is implemented or qualified. The handed-over nine
sensors and Govee coexistence are diagnostic evidence, not a tested NIA feature.
The gateway's usable NIA data API remains unqualified; no account, unbinding or
gateway replacement is made mandatory. Thermal PDF claims remain handoff-only in
this review; H5100 operating range and the reported S2 RH discrepancy remain
future qualification work. Current primary-source links and evidence tiers are
in the proposal. No hardware heating or further live sensor tests were conducted.

No product build, test suite, install, background service or runtime deployment
was appropriate for this documentation-only review. Future implementation gates
are tests to perform later, never current PASS claims. Objective validation here
covers proposal/review integrity and the unchanged production-source boundary.

Evidence folder:
`/home/jjennison/.local/state/nozzle-it-all/architecture/20261018-environment`.
The initial proposal, revised hash, raw normalized responses, separate dispositions,
approval-request artifact and protected unrelated-file digests are retained there.

## Evidence closure — ready for owner approval, not implementation

Sealed design manifest SHA256:
`f83d22a7adc721c8cc7f2ab63b2548505e958b9dfbbc7f555cfe4d18a8fe007d`.
Local design validator PASS and broker `evidence-cycle-35c3067bfc8d` both required
checks PASS with intact locked artifacts. These checks prove proposal/review
integrity and that runtime source is unchanged from the assessment baseline;
they do **not** claim implemented behavior, hardware qualification or owner approval.
All five unrelated untracked files retain their captured hashes.

Convergence `convergence-3d5a12ea92fb`; closure `closure-5e2fa77f1ac3` is
**READY_WITH_RESIDUAL_RISK**, with no blocking reasons. Two LOW scope limitations
remain explicit: future implementation/hardware acceptance is unproven here,
and owner-only architecture/milestone/policy choices remain gated. No critical or
high risk was accepted instead of remediation. The historical intermediate
proposal/version packages and the failed transport attempt are not rewritten.

Owner architecture approval remains **not granted**, and authorized implementation
milestones remain **empty**. This packet asks for approval of the architecture
only. A later implementation task must register its own source-bound Evidence
Profile and run the authorized build/test/review/install/runtime loop; the current
architecture-only profile must never substitute for that proof.
