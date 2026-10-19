# Optional environmental monitoring — architecture approval proposal

Date: 2026-10-18 (America/Los_Angeles). Status: OWNER APPROVAL REQUIRED; review tracked separately.
Scope: design and review only. No production implementation, sensor onboarding,
build, installation, service activation or equipment control is authorized.
Repository assessment baseline: `ed50eae76537b32e9b898812c48c1411b0102d37`.

## 1. Owner contract and non-goals

Manufacturer-independent, entirely optional monitoring for dryers, storage,
dryboxes, cabinets, printers, enclosures, rooms and custom equipment. A user can
use all existing NIA slicing, preparation, printer and project features without
sensors, Bluetooth, a gateway, Home Assistant, MQTT, cloud or background services.
No mandatory vendor account, unbinding, replacement gateway or sensor count cap.
No sensor onboarding, radio setting changes, heating control or safety interlock.
No production change before the owner approves the reviewed architecture.

Feature disabled means zero environmental scans, sockets, subscriptions, helper
processes, jobs, permission prompts and automatic history-DB opening/migrations.
Reading the disabled flag and displaying pure capability metadata is permitted.
Cached user history is shown only by an explicit read-history action, not during
startup. Enable alone is not authorization to scan or start collection. Discovery,
foreground collection, background collection, remote sync and cloud each have
separate consent. No environmental work runs in Fleet/PrinterSession constructors.

## 2. Current architecture assessment

Reinspected source, not an implementation claim:

| Existing boundary | Design consequence |
| --- | --- |
| `printer-api/.../DeviceAdapter.kt`: lazy optional registry, printer sessions, ServiceLoader providers | Reuse the separation pattern, not printer credentials/actions or eager provider construction |
| `desktop/.../Fleet.kt`: owns printer polling/settings/sessions | Environmental lifecycle must be a sibling, not a mandatory printer-session dependency |
| `app/.../project/AppDatabase.kt`: Room schema9, project/object/plate storage | Do not add environmental history to project DB or canonical3MF |
| Android manifest: no Bluetooth permissions; app minSdk28/targetSdk36 | Optional hardware and just-in-time permissions need a future reviewed manifest change |
| `desktop/.../AppPaths.kt`: NIA-owned XDG/portable paths | Separate lazy-open environmental directory, respecting NOZZLE_HOME and foreign-folder protections |
| `domain/.../FilamentSlots.kt`: printer-specific tool/tray semantics | Generic storage positions require UUIDs; do not reuse tool indexes or assume four slots |
| `domain/.../Spoolman.kt`: optional spool/material reference | Link optional external spool IDs; do not treat external catalog as local placement/history authority |

Proposed JVM-neutral `environment-api` (contracts and data), `environment-core`
(consent/lifecycle/validation/storage/rules), optional provider modules, Android
and Linux platform collectors, and platform UI. Names are proposals, not created
Gradle modules. UI depends on core/API; providers depend on API, never on Fleet or
project storage. Pure packaged descriptors enumerate providers; do not instantiate
arbitrary ServiceLoader plugin code to discover metadata. New plugin installation
is a separate explicit action, with version/trust/license review. In-process code
is trusted code; a helper process alone is not a sandbox. No third-party arbitrary
code loading in the initial milestone.

Use `com.nozzleitall.environment` for the proposed API/core/provider namespaces;
the existing legacy `net.jamesjennison.klippercompanion` namespace is not renamed.
API is Android-compatible Kotlin/JVM value types and ports with no desktop/app/
project-storage dependency; core uses platform storage/vault/radio/network ports,
not platform Room/BlueZ classes in the API. A compiled-in versioned descriptor
manifest is the initial registry; provider factories are separate and created only
inside a granted operation. Fleet's eager `ServiceLoader ... p.create()` is
explicitly NOT the environmental discovery mechanism. Milestone B verifies the
dependency graph, zero-provider startup and constructor/static-init spies.

## 3. Lifecycle and consent authority

Host-owned state machine:
`DISABLED -> ENABLED_IDLE -> DISCOVERING or COLLECTING -> STOPPING -> ENABLED_IDLE`.
`ERROR/UNAVAILABLE` retains configuration but never bypasses consent. Discovery is
bounded/cancellable and returns to idle; starting collection is a distinct action.
Configuration Save without collecting never starts I/O. Closing the last visible
foreground environmental session stops collection unless separately approved
background operation exists. Startup defaults idle, including after crash/reboot.

Every task carries a feature generation, binding revision, collector identity and
authorization epoch. Disable/revoke immediately closes the in-memory gate, then
durably records revocation and cancels owned scopes/leases/scans/subscriptions as
specified in section3.2, including storage-failure handling. Callback,
history, rule, outbox and UI publication paths all validate current epoch. Stop is
idempotent with a deadline; noncooperative provider is quarantined, cannot publish
and is reported as stopping/unconfirmed rather than falsely stopped. Native
transport resources have one owning scope; failure in one provider cannot cancel
Fleet, the renderer or another provider. Backoff has jitter, bounded concurrency
and no retry after consent revocation or permanent auth failure.

Offline remote collectors cannot learn revocation immediately. Future sync uses
finite authorization leases with explicit expiry, no indefinite offline renewal;
ingress rejects revoked epochs immediately and reports remote stop unconfirmed
until acknowledged/lease expired. Global disable stops this NIA instance's
environmental network traffic; do not keep a hidden revocation socket running.
Remote collector consent/revocation is shown separately from the local toggle.

### 3.1 Single gate and explicit entry-point rules

`EnvironmentalGate` is the sole host authorization authority. The composition
root supplies inert ports/factories; transports, provider creation, ingress,
persistence, analytics, publication and scheduling validate gate-issued purpose/
scope/epoch grants at their boundary. Providers do not grant themselves authority.
No arbitrary third-party code is trusted to obey this gate in the initial scope;
this is a testable trusted-code architecture, not a malicious-plugin sandbox.

| Entry point | Disabled / enabled-idle policy |
| --- | --- |
| Bootstrap | Read only the enabled flag and minimal consent control record from host-owned settings; no provider, history, vault, DNS or radio access |
| Provider list/describe | Compiled-in static descriptors only, no implementation class loading or device data reads |
| Explicit view/export old history | Separate user-gesture read-only grant; no migration, network, provider creation or collection; incompatible schema reports unavailable |
| Explicit save/configure | User-gesture control-store write, no discovery/connect until a separate start grant; Save-only remains idle |
| Explicit delete/revoke | Cleanup-only grant for environmental control/data/vault resources; no discovery or network restart; no printer/project deletion |
| Discover/collect/history network fetch | Denied while disabled; enabled-idle still requires a fresh scoped explicit start grant |
| Callback/import/outbox/rules/publication | Current operation/epoch only; no stale or automatic startup activity; disabled means no collection/sync/rules/jobs |

The zero-side-effect contract means zero environmental collection/network/radio/
helpers/jobs/prompts and no automatic history open, not a claim of zero filesystem
reads when a user explicitly exports or deletes old data. Neither the bootstrap
read exception nor explicit history read permits startup history access. Future
off-state tests instrument ports plus OS file/socket/process/permission/DB-open
boundaries at cold start, warm start and idle; explicit history/delete tests use
separate expected allowlists. Enabling without Start still creates no provider.

Gate contract: only the host gate issues opaque non-serializable local grants.
Grant fields are issuer/session identity, operation ID, purpose, allowed effects,
binding/config revision, collector identity, feature generation+consent epoch,
authorized endpoint/channel/store scope and monotonic expiry. No credentials are
embedded. Boundary modules cannot construct grants through the API. The single
`validate(grant, requestedEffect, currentContext)` operation denies absent, expired,
revoked, wrong-issuer/session/purpose/scope/revision grants with a typed reason and
zero side effects. Only a validated scoped port/permit can perform the effect;
commit/publication revalidates at the coordinator's linearization point. This
prevents accidental bypass in trusted code, not reflection/malicious-plugin abuse.
Milestone B tests every boundary with each invalid-grant class and statically
checks that only the gate issues grants; OS traces complement, not replace, this.

`OffStateReadAllowlist v1` is a named policy artifact: the environmental-owned
control record at the resolved NIA-private config `environment/control.json`,
including its enabled flag, is the sole environmental bootstrap data-file read.
Static compiled-in descriptors are program constants, not provider constructors
or another data/settings/cache file. Ordinary unrelated app startup I/O is outside
this environmental-only trace attribution and remains unaffected. Any other
environmental data-file/vault/history read at cold/warm/idle start violates this
allowlist. Explicit history/export and cleanup gestures use separately named
`UserHistoryReadAllowlist v1` and `UserCleanupAllowlist v1` scoped to NIA's owned
environmental artifacts only, with path confinement and no provider/network access.

### 3.2 Durable revocation and crash points

An environmental control record (enabled flag, monotonic generation, consent
epochs, stopped intent and cleanup status) lives in NIA's private config area,
outside both project and environmental history DBs. This minimal record is the
only environmental startup read. Writes use atomic replace, file fsync and parent
directory fsync (platform-equivalent durable commit on Android), off the UI thread.

The control record has a schema version, incarnation/generation, stopped/delete
intent, consent/cleanup metadata and checksum over a canonical bounded payload.
Checksum detects corruption, not hostile same-UID tampering. Missing record means
disabled. Truncated/unreadable/checksum-failing or unknown-version record means
DISABLED_RECOVERY: all grants denied, no collection/history/provider startup, retain
the original artifact and show Control data unavailable—review/reset required.
Do not guess enabled/epoch defaults or overwrite corrupt/newer records. Supported
older schemas may be interpreted read-only into inert state; durable migration or
backup restore/reset requires an explicit control/configuration action and tested
atomic conversion. Restored backups never restore grants/old incarnations as live.
Future C fixtures cover torn write, truncation, checksum/version failure and known
schema conversion; valid configuration restores idle only, never active collection.

Ordering: close the in-memory gate immediately -> durably commit the revoked epoch
and stopped intent -> cancel/release owned work -> record acknowledged stop.
An operation cannot publish while persistence is pending. Cancellation is still
attempted if durable commit fails; show Stop requested / persistence failed, not
durably disabled. Never leave collection active merely to preserve write ordering.
No hidden retries/jobs after disable: failed persistence is surfaced for explicit
retry. Every process boot starts with a new session nonce and all activity idle;
restored enabled settings never restore live grants or start collection. Thus a
crash before the durable write cannot resurrect old session callbacks or traffic.
After durable write, recovery uses that epoch and reports prior unconfirmed stop;
old observation/outbox/rule grants are rejected. Existing stored history remains
historical, not a new live publication. Revoked outbox is quarantined pending an
explicit export/delete or new scoped historical-import grant, not silently resent.

Future remote leases bind authority/collector/binding/epoch/session and a maximum
duration. Use monotonic expiry within a boot; reboot, uncertain time, wall-clock
rollback or missing trusted expiry proof fails closed and requires reauthorization.
Local revocation rejects ingress immediately; a disconnected collector may run only
until its existing finite lease expires. UI distinguishes locally blocked, remote
acknowledged and remote stop unconfirmed/lease-bound. No claim of immediate remote
erasure, instantaneous partition revocation or guaranteed same-UID isolation.

One host coordinator serializes grant changes with admission/DB commit and outbox
handoff. A commit must linearize before revocation or roll back; no check-then-commit
window can accept an older epoch after revocation linearizes. UI Stop requested
immediately suppresses live display while coordinator/durable stop finishes; finite
in-flight work is not falsely reported as already stopped. Test crash points before
gate close, after gate close/before control commit, after control commit/before
cancel, and after cancel/before acknowledgement. Reboot never automatically resumes.

## 4. Adapter and transport contracts (descriptive, not code)

| Operation | Contract |
| --- | --- |
| describe | Pure static metadata, protocol/schema versions, supported models/firmware, channels, permissions, trust, local/cloud, active/passive scan, history, expected cadence, limits and qualification evidence |
| assess(config) | Pure validation and capability explanation; no DNS, sockets, radio, secrets lookup or provider construction |
| discover(grant, deadline) | Optional bounded cancellable discovery; explicit scope/active-scan consent; minimize nearby identifiers and discard unselected candidates |
| collect(binding, grant, sink) | Authorized immutable observations plus separate transport/decoder health; cancellation, bounded ingress and no direct storage/rules/UI writes |
| history(cursor, grant, pageLimit) | Optional bounded backfill, stable cursor/cancellation, source timestamps and provenance; no implied fresh sample |
| stop | Idempotent, only owned resources, observable acknowledgement/deadline; no global radio shutdown or removing pairings |

Radio transport is separate from model/protocol decoding. One authorized shared
radio transport may feed several decoders within the feature; per-binding grants
still gate publishing. Parser inputs and output are bounded, versioned, unit-typed
and validated by the host, not trusted because an adapter is installed. Decoders
cannot start scans or open arbitrary sockets. Manufacturer names/MACs do not
prove identity or compatibility. Support states are distinct: recognized, decoded,
qualified for exact tested firmware/platform, unsupported, unknown, unavailable.
Qualification records exact hardware/firmware, decoder version, transport/scan
mode, observed channels/cadence/calibration, operating limits and evidence.
Unknown firmware is not silently promoted to qualified; it can be explicitly
used as experimental with a warning, but never advertised as qualified.

Qualification is per model/firmware/platform/transport/channel/capability, not an
unqualified whole-device badge. Required fields: decoder/version+fixtures, identity
evidence/uncertainty, units/range/sentinels, calibration revision/status, per-channel
cadence revision(expected interval, tolerance, confidence, evidence source and
observation window), source time/sequence/replay limits, thermal operating envelope
with manufacturer document/revision, placement/hotspot/battery/mounting assumptions,
and dated pass/fail/unresolved checklist. Unknown cadence means freshness unknown;
observed drift marks that cadence revision suspect and requires requalification,
never an automatic wider freshness window. Old decisions retain old revisions.
Unknown battery calibration does not invalidate an independently qualified temp
channel, but the battery capability stays unverified. Unresolved RH/identity/
temperature/calibration issues block qualification of the affected capability.

UI wording: Recognized—format/model match only; Decoded (diagnostic)—values parsed,
not qualified; Qualified for [channels/platform/firmware]—view qualification record;
Unsupported—known unsupported protocol/revision; Unknown—insufficient evidence;
Unavailable—supported route cannot currently run (permission/radio/vault/auth).
Experimental is an explicit warning overlay, never a synonym for Qualified.

## 5. Domain, time and history model

- Sensor: logical UUID, user label, optional manufacturer/model and evidence-backed
  identity. ProviderBinding: separate UUID, route/provider/collector/channel map,
  config revision, SecretRefs, qualification and consent. A sensor may have several
  bindings; linking uncertain identities requires user confirmation and is auditable.
- Equipment: UUID/type/name; arbitrary child Position UUIDs/labels and optional
  printer reference. Positions are not tool lanes. Multiple sensors per position;
  equipment may exist without a printer or spool integration.
- Placement: time-bounded sensor-to-equipment/position context, half-open intervals.
  SpoolResidence: separate time-bounded spool-to-position relation. Reassignment
  closes the old interval and adds a new one transactionally, never edits past
  observations to pretend a sensor was always in its current location. Conflicting
  overlaps are rejected or explicitly represented as unresolved, not last-write-wins.
- Channel: namespaced ID, physical quantity, canonical unit and precision; RH%,
  battery%, batteryV and temperature are separate types. Preserve raw value and
  immutable calibration revision. Missing/invalid data is unknown, not zero.
- Observation: value/unit/quality, logical sensor+binding+channel, optional source
  sample ID/sequence, measuredAt nullable, collector observedAt and monotonic/boot
  clock metadata, ingress receivedAt, source clock uncertainty, collector/session/
  event sequence, decoder and calibration provenance. Source timestamp absence is
  explicit; receipt time never replaces an old measuredAt or fabricates certainty.
- Health: connected, last reception, last accepted observation, last provable new
  sample and freshness are independent. Duplicate advertisement callbacks prove
  reception, not a new physical measurement. Without source time/sequence, display
  recent reception with sample age unknown; alerts/exposure cannot assume continuity.
- Freshness: per-channel expected cadence with documented tolerance and confidence;
  monotonic elapsed time within a boot, conservative clock-jump/reboot handling.
  Backfill is never fresh because it just arrived; future/skewed timestamps become
  uncertain/quarantined, not indefinitely fresh. UI always shows source/age/quality.

Optional separate local history store, schema-versioned with forward-compatibility
rejection and tested reversible migrations, never destructive fallback. Store
readings/assignments/calibration revisions/outbox in transactions. Index by sensor,
channel and event time; stream queries/pages, no load-all charts. No hard sensor
count ceiling, but explicit bounded event queues, page sizes, history retention,
disk budget and rate limits. Overflow/drop counters and gaps are persisted and
visible, not silent completeness. Disk-full stops persistence/analytics cleanly,
does not interrupt slicing/printing; last state is clearly volatile/unavailable.

Offline collection optionally commits observation+outbox atomically. Retry uses
stable event ID `(collectorId, sessionId, sequence)` and unique constraints. Crash
after send-before-ack cannot double count. Cross-collector exact dedup is allowed
only for adequate shared source identity+sample identity; byte/value equality is
not proof. Otherwise retain distinct raw receptions and choose one canonical
binding per sensor/channel/time range for analytics with explicit failover/gaps;
do not sum exposure across overlapping routes. Import, retry, chart and alert use
the same canonicalization rules. Historical source switching is versioned, not
silent rewriting. Shared exclusive GATT/polling may use optional finite fencing
leases; local foreground operation never requires a coordinator.

### 5.1 Canonicalization v1

Persist a versioned effective-time policy per logical sensor/channel with an
explicit ordered list of authorized binding UUIDs. Qualification eligibility is
checked before priority; priority ties use lexical binding UUID, not arrival order.
Configuration revisions apply to half-open future intervals, never rewrite earlier
policy. Raw observations retain their binding; duplicate source-sample keys must
include source identity and sequence scope/boot era, not a bare counter. A wrapping
8-bit BTHome packet ID alone is only a bounded adjacent-repeat hint, not a globally
unique historical dedup key. Unknown source reboot/era means exact cross-collector
dedup is unavailable, not guessed from equal payloads.

For an analysis interval, partition at sample boundaries and policy/assignment/
calibration/qualification changes. Pick the first eligible binding in policy
order with valid time-bounded coverage; if it has no coverage, choose the next,
emitting failover/source-change and uncertainty/gap markers. Never sum overlapping
routes or use latest arrival as an implicit priority. Only samples whose age and
quality policy permit analysis have coverage; unknown physical sample age can
appear as diagnostic reception history but contributes no asserted exposure
duration or fresh-sample alert. Uncertain placement-boundary times get unresolved
context, not a fabricated position. Do not bridge gaps with unbounded interpolation.

Store canonical-policy version, input watermark/snapshot and algorithm/calibration/
assignment revisions with each derived series. Equal inputs+versions produce equal
series for charts/alerts/exposure/import/retry; changing policy or receiving late
data creates a new derived revision, preserving previously issued results and
their uncertainty. Late backfill can correct historical analytics but cannot emit
present-time alerts. Spool residence overlaps/edits use the same temporal rules.

Event admission first deduplicates the stable collector/session/sequence key.
Same key+same content is an exact retry; same key+different content is quarantined
as an identity conflict, never last-write-wins. Physical-source dedup additionally
requires qualified source identity+sample key+proven counter era. For eligible
samples, canonical sort is `(effective measured time, time-uncertainty class,
proven source sequence within era, binding priority, collector UUID, session UUID,
event sequence)`; absent incomparable source sequence sorts after a proven one,
not by lexically comparing opaque source IDs. Source sample IDs identify samples,
they do not define time order. Same-time conflicting values on the selected route
produce unresolved-conflict coverage, not arbitrary freshness or averaged exposure.
Diagnostics without reliable measured time sort in separate reception/boot-segment
views by observedAt/boot-relative sequence and ingress event ID; receivedAt never
makes them eligible for physical exposure. Clock jumps are annotated, not smoothed.

Late-arrival horizon is a finite versioned policy parameter set before analytics
acceptance. A trustworthy event-time watermark excludes future/skewed/quarantined
events. In-window late data can create a corrected historical derived revision;
outside-window data remains raw until an explicit historical recomputation creates
a new revision. Neither path sends present-time alerts. No arrival permutation may
change results for the same input snapshot/policy. Tests include sequence resets,
counter wrap, source era unknown, conflicting retries and identical times/routes.

Stored derived revisions are always rendered/compared under their recorded policy
version and input snapshot, never interpreted under the currently active policy.
Changing the active policy is an explicit owner-visible configuration/version
event; it creates new revisions, not edits to archived ones. A consumer lacking
that policy/derived schema reports Unsupported historical policy (unavailable for
interpretation/comparison), preserves the artifact/export, and does not silently
re-derive it. Future G tests two versions over identical raw inputs plus unknown
policy versions. Retry equality compares canonical immutable event content, not
new ingress receivedAt/hop diagnostics; retries retain first admission metadata
and optional reception counters, so a transport retry is not a content conflict.

## 6. Platform and provider integration

Android initial scope: foreground only, optional BLE hardware, no permission prompt
until explicit scan/connect. Version-specific SCAN/CONNECT and legacy location
needs reviewed against min28/target36; receiving requires no ADVERTISE permission.
neverForLocation only if truthful; test beacon filtering on the actual models.
No reuse of PrintMonitorService specialUse and no continuous WorkManager scanner.
Background later requires separate opt-in and documented Android-supported
lifecycle/foreground-service/notification requirements; force-stop/permission
revocation/battery restrictions are explicit unsupported-availability states.

Initial Android collection is tied to a visible Activity lifecycle (STARTED);
no environmental foreground service or background notification requirement in B–D.
On stop/background or permission loss, close the gate and cancel collection;
display the resulting coverage gap on return and require an explicit restart.
Rotation may transfer inert configuration but never duplicate live collectors.
Future background work is Milestone F and needs a new platform-specific decision.

| Android API on initial min28/target36 app | Scan/receive advertisements | Explicit GATT/connect |
| --- | --- | --- |
| 28–30 | Legacy BLUETOOTH/BLUETOOTH_ADMIN declarations(maxSdk30), foreground runtime FINE_LOCATION and OS location availability where required; explain before scan | Legacy BLUETOOTH; location only if scanning is used |
| 31–36 | Runtime BLUETOOTH_SCAN; request CONNECT separately only when the chosen operation/device metadata API actually requires it | Runtime BLUETOOTH_CONNECT, SCAN only if discovery needed |
| All supported versions | No ADVERTISE, no background-location request in initial scope; optional BLE feature required=false | No pairing/GATT without explicit chosen-operation consent |

Proposed `neverForLocation` assertion is appropriate only because BLE data is used
for user-assigned equipment labels, never inferred physical user location/proximity
tracking. Qualification must test the resulting beacon filters; if a protocol is
filtered, report unavailable on that configuration rather than silently remove
the assertion/request broader permissions. Any new location-derived use needs a
separate design/permission review. No denial blocks non-environmental app features.

For this target36 app on API31+, an old FINE_LOCATION grant does not replace
BLUETOOTH_SCAN/CONNECT or authorize a broader fallback. Legacy declarations capped
at maxSdk30 are not the API31+ scan contract. No extra location grant is requested
merely because scanning yields no reports. Qualification includes an independent
known-transmitter/reference observation and permitted-filter configuration matrix:
if that evidence proves the required report is filtered, mark that exact route
Unavailable under this permission policy. Runtime silence alone is No reports
received / cause unknown, not proof of filtering or a reason to change the
assertion. Tests verify no silent removal of neverForLocation and no permission
broadening with stale legacy grants; platform/OEM quirks remain explicit unknowns.

Permission/radio state machine: Idle -> explicit Discover/Start -> Explain required
capability/active-scan impact -> OS permission request(if needed) -> Granted+radio
ready -> bounded discovery/collection. Denied returns unavailable, no automatic
re-prompt; permanent denial offers an explicit OS-settings action. Cancel during
the permission dialog invalidates the operation generation: a late permission
success starts nothing. Radio-off, permission loss or Activity STOP cancels current
grant and owned session, shows the reason/gap, and never auto-restarts on restore.
Only a fresh user action restarts. Candidate identifiers/payloads are bounded
in-memory draft data, expire at discovery deadline/cancel, and never enter history,
logs or durable config unless explicitly selected. Cancel discards the draft and
provisional credentials. Active-scan-required protocols are labeled before Start;
missing OS support is Unavailable, not a passive-scan compatibility claim.

Linux initial scope: foreground optional BlueZ D-Bus client, no root or mandatory
daemon. Acquire/release only NIA's discovery session on its own D-Bus connection,
never globally stop Bluetooth/discovery or remove devices. Shared daemon filtering
does not make other clients' devices authorized NIA inputs; host post-filters.
Active scanning is disclosed, including radio impact; no GATT/pairing unless the
chosen protocol specifically needs it and the user separately approves.

Each NIA collector owns one BlueZ connection/session reference. After its own
StartDiscovery succeeds, accept only reports matching an explicit discovery grant
(bounded candidate preview) or saved authorized binding/protocol/identity predicates
(collection), with qualification/epoch/payload checks before publication. Discard
other clients' nearby device data. Another client's discovery is normally shared
and is NOT an automatic Unavailable state. Only actual NotReady/NotAuthorized or
failed acquisition is unavailable, without forcing power/discovery/reset changes.
On stop, release NIA's successful session once and close its own connection; do
not release someone else's session or clear their filters. Test coexistence with
another active BlueZ client as a success case, plus real acquisition failures.

Providers: BLE Govee plus independent BTHome qualification; LAN only where a
specific documented/read-only protocol is proven; HA selected entities and MQTT
selected topics, both optional. HA token scope may exceed the NIA entity allowlist;
disclose that and enforce app-side selection. MQTT retained/QoS/reconnect messages
are not new measurements by receipt. Read-only subscription credentials and broker
ACLs are preferred; client publishes no equipment-control commands. TLS/auth by
default, no secret-bearing plaintext connection, certificate failure fails closed.
If a local protocol requires unencrypted unauthenticated reads, disclose/approve
that specific local endpoint, never globally weaken TLS. WiFi capability is not
evidence of a local API. No Govee gateway/cloud adapter is presently qualified.

HA uses selected `subscribe_entities` where supported, explicit allowlisted entity
IDs and no automatic whole-house subscription fallback; unknown/unavailable states
and HA event times are not source physical sample proof. MQTT packet identifiers,
QoS/DUP and retain flags never substitute for a durable physical source sample ID.
No environmental client may rely on Android's existing global
`usesCleartextTraffic=true` (needed by current printer workflows): host transport
scheme/credential/certificate checks enforce the environmental policy independently.
Changing the global printer setting is out of scope and not needed for this design.

## 7. Security, privacy and deletion

Secrets remain SecretRefs backed by platform vaults, never project files, normal
configuration, logs, exported history or review packages. Vault unavailable means
credential-dependent provider unavailable, no plaintext fallback. App-private
storage/permissions, no automatic cloud backup/sync. History can reveal occupancy
and equipment usage; explicit retention/export controls and minimized diagnostics.
Raw nearby-device payloads are transient by default, bounded diagnostic capture
separately consented. Unencrypted BLE can be spoofed; never use it for safety actions.

Future remote collectors require authenticated explicit pairing, scoped permissions,
key rotation/revocation/expiry and encrypted transport; no unauthenticated listener
or automatic LAN trust. Endpoints are user selected and scheme/host/port validated;
reject embedded credentials, unsafe redirect/SSRF destinations, cloud metadata
endpoints and DNS-rebinding policy bypass. Private LAN hosts are allowed only by
explicit endpoint scope, not arbitrary device payload URLs. Payload size/depth/
rate/time bounds, malformed input and fuzz fixtures precede provider qualification.

EndpointPolicy v1 is host-owned: permit https/wss/mqtts only by default; no URL
userinfo, fragments, device-supplied URLs or redirects. Validate canonical hostname,
port, certificate/SNI and all resolved addresses. Connect to a validated pinned
address while validating TLS against the approved hostname; re-resolve and recheck
on reconnect, never validate one address and let the client connect to another.
Explicit selected LAN endpoint grants may permit RFC1918/IPv6 ULA; no wildcard
subnet authorization. Deny unspecified, broadcast, multicast, link-local/metadata
and loopback destinations in the initial network provider scope, including mapped
IPv4/IPv6 forms; future local helper access needs a separately reviewed local IPC
contract, not an SSRF exception. Known cloud metadata endpoints remain denied even
if expressed by hostname. Fixed protocol discovery multicast is a separate bounded
discovery transport grant, never an arbitrary HTTP/MQTT fetch exemption. A public
Internet/cloud endpoint requires the separate cloud opt-in. A specific local http
or mqtt exception can authorize unencrypted, unauthenticated read-only data only
after warning/approval; secret-bearing plaintext is never allowed. The UI displays
that exception and its endpoint; no fallback on TLS failure or redirect auth leak.

Endpoint config is a canonical immutable object: scheme, normalized hostname,
explicit port, protocol-specific base path/entity/topic allowlist, trust class,
certificate policy and optional local-plaintext exception consent ID. Changes
increment binding revision, stop the old grant, and require new endpoint approval;
imports supply an inert draft only. No implicit environment/system HTTP proxy or
PAC is used; a future proxy is separately configured/approved and cannot bypass
destination/TLS checks. Never log userinfo, headers, tokens, credential-bearing
query values or raw server bodies; use sanitized error codes. Query credentials
are rejected, not redacted only after connection. Credentials use provider-owned
vault access scoped to the approved endpoint and operation.

On detected vault-lock/unavailability, invalidate the affected operation grant,
cancel/close its owned transport, release raw credential references, and show
Credential vault unavailable—unlock and Retry. No automatic vault retry/prompt,
credential refresh or plaintext fallback; only explicit retry after availability
reassessment can obtain a new grant. Raw credentials are retained only for the
active operation's finite authorized lifetime, never beyond stop/expiry or in
durable caches. A read-only request already sent cannot be retracted; do not claim
instant remote cancellation or guaranteed JVM-memory erasure. Future E injects
vault failure before start and mid-operation, including providers without lock
notifications (availability enforced when detected/credential access attempted).

Disable retains history/config with clear wording but stops collection. Remove
binding revokes grants, closes resources and removes its credentials; orphaned
vault entries are reconciled without logging. Delete all environmental data has
explicit confirmation and removes NIA-owned config/history/outbox/calibration/
diagnostics/credentials, never printer/project data. Synced deletion needs durable
tombstones/epochs before purge and acknowledgement/lease policy to prevent offline
resurrection; disconnected deletion is shown as unconfirmed, not globally complete.
No silent loss of historical provenance or claim of SSD forensic erasure. Exports
are explicit user-owned copies; NIA cannot revoke copies already exported.

### 7.1 Delete-all transaction graph

1. Stop requested closes live publication; serialize admission, commit a durable
   delete intent/new incarnation+revocation epoch in the minimal control record.
   Any pending collector/history/outbox activity under the old incarnation fails.
2. In one environmental-store transaction remove observations, assignments,
   calibrations, canonical/analytics outputs, queued outbox and binding configs;
   close the store. This is not a project DB transaction and cannot cascade there.
3. Clear environmental UI/image/query caches and temporary export/diagnostic files
   only in NIA-owned environmental paths. User-exported copies elsewhere remain
   user-owned and are named explicitly as outside deletion's scope.
4. Remove environmental vault entries/helpers' owned data; each cleanup phase has
   a durable completion bit. A vault/filesystem failure means Cleanup incomplete,
   never successful deletion; an explicit retry resumes idempotently, not collection.
5. On complete local cleanup compact to inert disabled flag+new incarnation nonce
   in host settings, containing no sensor names/readings/credentials. The minimal
   denial nonce is disclosed; do not promise literally zero control metadata or
   forensic erasure. Restart stays idle and resumes no hidden cleanup jobs.

Named recovery step `DeleteResume`: the store transaction is admitted only after
the durable delete intent commits successfully; failed intent commit permits no
store purge and leaves the gate closed with an explicit incomplete state. On a
crash after intent commit/before store transaction, or after store commit/before
completion-bit commit, boot reads only the control record and offers an explicit
Resume cleanup action. That gesture re-runs incomplete steps idempotently under
the recorded intent; already-cleared tables or removed files/vault entries count
as complete, never as a reason to restore data. History/providers remain blocked
throughout. This is not automatic background startup cleanup. Future C injects
each named crash point and tests explicit resume, no resurrection/no collection.

Crash recovery opens only the minimal control record until explicit cleanup retry;
history/providers cannot reopen while delete intent is pending. Pre-delete backups,
imports and delayed messages carry an old incarnation and are rejected, not silently
restored. Deliberate user restore is a separately confirmed archival import into a
new namespace, preserving provenance but never restoring grants, credentials or
current freshness/alerts. Removing a binding while keeping history archives its
provenance identity; it cannot cascade-delete past observation context by accident.

Future remote deletion must record epoch/tombstone before local purge. Local off
and Delete-all do not open a network path to send deletion requests. Only a separately
authorized remote-management session can send/receive deletion acknowledgements.
UI says Local data removed; remote deletion unconfirmed until that is established.
Keep revocation/tombstone metadata until all authorized collectors acknowledge or
their maximum lease plus replay horizon expires; those durations are owner-approved
before F. Old incarnation/authentication is never reusable after compacting those
tombstones, so old data stays rejected. Partial/offline deletion never claims global
completion. Export dialogs say Copies you save are not removed by Delete-all.

## 8. Optional UI and intelligence

Settings > Environmental Monitoring > Enable > Add > Method > explicit Discover/
Connect > Assign equipment/position > Configure > Save without collecting OR Save
and start. Manual endpoint entry is always available; unsupported platform/device
does not block existing app use. Each step is cancellable, with no implicit sensor
pairing/account linking. Accessible names, keyboard navigation, focus/error summary,
non-color freshness/status indicators and platform permission explanations required.
Cancel rolls back provisional credentials/collectors; committed binding saves are
atomic. Provider unavailable has Retry/Choose another method/Keep saved actions,
never an automatic cloud fallback. Feature disable and remote consent status are
visible independently. Charts expose gaps, age and calibration, not interpolated
certainty; default no cloud, background, alerts or analytics until opted in.

Optional intelligence: ambient RH/temperature is not measured filament moisture;
no inferred filament moisture percentage. Exposure uses time-aligned channels and
temporal spool residence, explicitly reports coverage/gaps/calibration uncertainty.
Drying sessions record observed conditions, not proof a spool is dry; sidewall
temperature is not spool-core temperature. Multi-sensor uniformity requires
overlapping qualified samples and time uncertainty, not comparing unrelated ages.
Material guidance is attributed/versioned and bounded by sensor operating limits;
correlation with print outcomes is not causation. Alerts are best-effort, explicitly
opted-in, with debounce/hysteresis/cooldown and stale/uncertain-input suppression;
history import does not generate present-time alerts. Never safety-critical or
heater/printer actuation. Recalculation preserves its algorithm/provenance version.

Canonical accessible state text: Received recently; sample age unknown (no fresh
sample badge); Fresh sample / Stale sample with age and source; Time uncertain
after clock change; Identity unconfirmed; Stop requested / Stopped locally /
Remote stop unconfirmed; Cleanup incomplete / Local data removed; remote deletion
unconfirmed. Qualification labels are in section4. Each has text/icon and screen
reader descriptions, not color alone, consistently in wizard/detail/chart/settings.
Show local display timezone and UTC export timestamps, original source zone/clock
quality where known, and distinguish reception from measurement timestamp axes.

AnalyticsPolicy v1 is required before G and absent by default (Insufficient
evidence, not an inferred estimate). It specifies required channels/qualification,
minimum coverage fraction in[0,1], minimum positive overlap duration/sample count,
maximum source-time uncertainty, age/cadence tolerance and placement confidence.
All necessary samples must lie within qualified sensor operating limits and
calibration ranges. Exclude unknown age, uncertain placement, gaps, conflicting
samples, out-of-range values and incompatible calibration segments; expose excluded
duration and coverage denominator. If any threshold is unconfigured or unmet,
show Insufficient evidence with reasons rather than an exposure/uniformity score.
Diagnostic reception charts remain available but labeled non-analytic. Numeric
thresholds are owner-approved before G; no default enables optional analytics or
alerts. Confidence limits and algorithm/input revisions accompany any later result.

## 9. Existing-hardware evidence and qualification limits

The side investigation handoff reports nine BLE sensors across H5100/H5110/H5074/
H5179, versus seven in the original reference. It reports repeated diagnostic
decoding while phone Bluetooth was off, Govee gateway-linked readings continued,
and U1 went offline as a negative control. This is diagnostic coexistence, not a
released NIA adapter or per-model/firmware qualification. Battery100% was decoded
but not independently calibrated. Probable sensor/name mapping is not proven;
one S2 RH discrepancy remains unresolved. No new live scans were run for this
architecture review.

Two handed-over screenshots were directly inspected here: sensor detail shows
Bluetooth-disabled warning, gateway connection,38.1C/27.0% and minute-granularity
update timestamp; gateway settings show H5151 firmware1.00.28/hardware3.01.01.
The handoff reports seven gateway-bound sensors, not U1/Centauri, and no response
to bounded UDP4001/4002 discovery; selected TCP ports were closed. That does not
prove absence of every LAN API or local-vs-cloud path. No gateway API is invented.
Direct BLE is a possible coexistence route without Govee unbinding/account use.

Current upstream parser marks H5074 and H5179 active-scan-required; HA lists these
families but that is not NIA qualification. Exact model/firmware, payload fixture,
negative temperature, calibration, sentinel, cadence, coexistence and radio behavior
must be tested separately. Manufacturer60C limits for some models were reported in
the handoff; those PDFs were not independently reverified in this review. H5100
limit remains unknown. Do not qualify any60C-limited or unknown-limit sensor for
an entire70C dryer operating range; placement/hotspots/battery/mounting and exact
revision require manufacturer evidence before deployment. No heating tests proposed.

Milestone D qualification checklist explicitly includes the S2 RH mismatch, confirmed
logical identity mapping, each channel's calibration/battery evidence, cadence/time
quality and manufacturer thermal envelope. Each entry is pass/fail/unresolved with
impact; unresolved items block the affected Qualified capability, not disappear.
Unknown or insufficient operating limits block Save and start for a declared dryer
position whose full expected envelope (including credible hotspots) is not within
the documented sensor/battery/mounting envelope. Save-only may retain an unqualified
draft association. An owner cannot turn an unsupported temperature into a qualified
manufacturer limit by clicking a warning. Unknown position envelope also blocks
qualified deployment; observations already received outside limits are retained
and flagged, not discarded or used to actuate anything. Room/other placement needs
its own demonstrated compatible envelope; no full70C qualification by inference.

## 10. Milestones and objective acceptance (future authorization required)

| Milestone | Required acceptance before advancing |
| --- | --- |
| A: architecture review and owner approval | Findings resolved in design; owner explicitly approves bounded implementation scope; no production activation |
| B: pure contracts/off-state | Zero automatic environmental I/O and zero collection/network/radio/prompts/helpers/jobs when off or enable-idle; only explicit user-gesture operations allowlisted by3.1; Save-only performs control writes, not collection; parser/unit/version/capability/dependency tests; existing NIA regressions unchanged |
| C: CRUD/history/temporal model | Restart, assignment interval boundaries/overlaps, calibration versions, retention/export/delete, crash/outbox atomicity, retry dedup, disk-full/queue overflow/gap tests |
| D: foreground Android/Linux BLE | Exact qualified Govee and independent BTHome fixtures/hardware; active-scan/permission/BT-off/revoke/cancel/stale-epoch/reboot/identity-change/coexistence tests; no Govee unbinding; no thermal-overrange tests |
| E: optional HA/MQTT/documented LAN | TLS/auth/ACL/entity/topic allowlist, unknown/stale/retained/backfill, reconnect/retry/clock skew, bounded payload/SSRF and failure isolation tests |
| F: optional collectors/sync/background | Separate owner approval, platform lifecycle proof, authenticated pairing, scoped finite leases/fencing, partition/revoke/delete/replay/tombstone and OS-kill tests |
| G: optional intelligence | Aligned clocks and confidence/coverage, no duplicate exposure/alerts, late import suppression, uncertainty/material-source/thermal-limit tests; no moisture% or safety-actuation claims |

Every implementation milestone later needs source-bound GTHost builds/tests,
Synthetic review, permitted platform install and real-app verification. Android
target identity must be freshly checked; do not infer the correct physical phone
from a stale serial in a handoff. This review executes none of those deployment
steps. Performance acceptance must measure radio/battery/CPU/disk budgets and UI
responsiveness on declared actual hardware, not claim unlimited sensors at zero
cost. Numeric budgets are set with the owner before implementation acceptance.

### 10.1 Owner-only approval gates

These are future scope/configuration choices, not outstanding technical defects
that reviewers may decide on the owner's behalf:

| Decision | Gate and conservative default |
| --- | --- |
| Approve this architecture, then authorize an implementation milestone | A; no implementation authorized by this review or by review closure alone |
| Numeric scan/radio/battery/CPU/disk/queue/retention budgets and target hardware | Before B–D implementation acceptance; bounded resource design exists, actual values require owner agreement before qualifying collection |
| Provider ordering/subset | Before D/E implementation scope; proposed first foreground Govee+BTHome, later optional HA/MQTT/documented LAN; owner may select a subset |
| Experimental unknown-firmware/calibration sensors | Before D deployment; default diagnostic/unqualified only; unknown/insufficient thermal envelope never gains qualified dryer deployment by waiver |
| HA token privileges and specific endpoints/topics/entities | E per connection; app allowlists do not narrow upstream token privileges; no connection absent user acceptance |
| Background collection and Android lifecycle/notification requirements | F separate approval; initial foreground Activity-only collection uses no FGS |
| Optional intelligence/alerts and coverage/time-uncertainty thresholds | G separate approval; absent policy means no score/alerts, only diagnostic charts |
| Remote collectors/sync/cloud, finite lease duration and deletion/tombstone policy | F separate approval; off by default, no remote listener/coordinator mandatory |
| Global Android cleartext flag | Not required by this proposal; existing printer traffic unaffected; any change needs separate owner-approved scope |

User consent in the eventual wizard configures an already-authorized feature; it
does not replace the owner's project-level authorization to implement a milestone.

### 10.2 Future evidence artifacts, not tests performed in this review

| Gate | Required reproducible evidence |
| --- | --- |
| B | Dependency graph; empty-provider startup; constructor spies; cold/warm/idle OS file/socket/process/permission traces and DB-open counters against3.1 allowlists; existing app regression reports |
| C | Schema/backup/restore fixtures; temporal boundaries/overlaps; controlled crash-point traces and raw DB/outbox assertions; disk-full/overflow gaps; per-store deletion/vault-failure/retry/import-resurrection reports |
| D | Model+firmware+OS+radio+decoder+scan-mode matrix, sanitized payload fixtures and negative/sentinel/calibration/time/cadence/range cases; actual coexistence/permission/radio/Activity cancel captures and unselected-candidate retention checks |
| E | Endpoint policy vectors, DNS pin/rebind/redirect/proxy/TLS/auth/cleartext failures, allowlisted HA entity/MQTT topic fixtures; stale/retained/QoS/replay tests, isolated provider failure traces |
| F | Actual supported Android/Linux background behavior and permission snapshots; authenticated pairing/finite lease/fencing, clock/reboot/partition/revoke/delete/old-incarnation replay traces; no mandatory coordinator/off-state sockets |
| G | Permutation/dedup/conflicting-key/counter-era fixtures; identical canonical series across consumers, explicit coverage/overlap/time uncertainty/placement/thermal exclusions, no late-data present alerts, accessible insufficient-evidence captures |

All proof records source/artifact identity, command/device identity and uncertainty;
no secret-bearing payloads or unrelated nearby-device data. Evidence taxonomy:
DIRECT=inspected current source/docs/screenshots; HANDOFF=reported earlier live
investigation not rerun here; UPSTREAM=external protocol/integration reference,
not NIA qualification; FUTURE=required implementation acceptance, not current PASS.
Section9's nine sensors, BLE decoding, bindings/UDP/TCP findings and thermal PDF
claims are HANDOFF; its two screenshots are DIRECT; active-scan/model support is
UPSTREAM; every qualified NIA provider and operating envelope remains FUTURE.

## 11. Primary references checked for this review

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions): optional hardware, version-specific runtime permissions, neverForLocation filtering.
- [Android BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background): background restrictions and supported lifecycle options, not a blanket continuous-worker permission.
- [BlueZ Adapter1](https://github.com/bluez/bluez/blob/master/doc/org.bluez.Adapter.rst): per-client discovery ownership/shared discovery and filtering.
- [Govee BLE integration](https://www.home-assistant.io/integrations/govee_ble/) and [upstream parser](https://github.com/Bluetooth-Devices/govee-ble/blob/main/src/govee_ble/parser.py): model coverage and active-scan declarations, not NIA qualification.
- [BTHome format](https://bthome.io/format/): independent versioned sensor protocol candidate.
- [Home Assistant WebSocket API](https://developers.home-assistant.io/docs/api/websocket/): authenticated state/event transport; selection does not narrow underlying token privileges.
- [MQTT5 OASIS](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html): transport delivery/retention semantics are distinct from physical measurement freshness.

These references inform a proposed design, not implemented platform compliance.
