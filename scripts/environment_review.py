#!/usr/bin/env python3
"""Submit a sanitized, read-only Synthetic environmental implementation review.
No credential files or raw device diagnostics are read.
"""
import hashlib
import json
import runpy
import subprocess
import sys
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
call = runpy.run_path('/home/jjennison/.local/bin/reviewer', run_name='client')['broker_request']
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
change = 'nia-environment-bcd-' + revision[:8]
if sys.argv[1] == 'register':
    print(json.dumps(call({'operation': 'register_change', 'change': {
        'change_id': change, 'repository': str(repo), 'classification': 'SUBSTANTIVE',
        **({'supersedes': sys.argv[2]} if len(sys.argv) > 2 else {}),
        'summary': 'Approved B-D opt-in environmental framework, separate local history, foreground Android/Linux BLE; security, concurrency, privacy, platform lifecycle, performance and UI review',
        'affected_components': ['environment-api', 'environment-core', 'desktop', 'app'],
        'provenance': {'source_revision': revision, 'architecture_sha256': 'dfbdd62225e24e89e850fa7326f8a877993e4ec0edba3b08a09760742fa5700d'}}})))
elif sys.argv[1] == 'submit':
    paths = [*repo.glob('environment-*/src/**/*.kt'), *repo.glob('environment-*/build.gradle.kts'),
             *repo.glob('desktop/src/main/kotlin/com/nozzleitall/desktop/environment/*.kt'),
             *repo.glob('app/src/main/java/net/jamesjennison/klippercompanion/environment/*.kt'),
             repo/'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalPanel.kt']
    sources = {str(p.relative_to(repo)): p.read_text() for p in paths}
    for path in ['desktop/src/main/kotlin/com/nozzleitall/desktop/Main.kt', 'app/src/main/java/net/jamesjennison/klippercompanion/MainActivity.kt']:
        sources[path] = '\n'.join(subprocess.check_output(['git', 'diff', 'ed50eae7', '--', path], cwd=repo, text=True).splitlines())
    package = {
        'phase': 'CURRENT_SOURCE_REVIEW',
        'task': 'Independent read-only implementation review of approved B-D environmental monitoring. Return normalized JSON findings, not patch execution. No tool execution or workspace access. Owner requirements are authoritative; no majority vote or fabricated runtime proof.',
        'source_revision': revision,
        'owner_requirements': (repo/'docs/ENVIRONMENT_MONITORING_ARCHITECTURE.md').read_text(),
        'approved_scope': (repo/'docs/handover/NIA_ENVIRONMENT_IMPLEMENTATION.md').read_text(),
        'sources': sources,
        'build_diff': subprocess.check_output(['git', 'diff', 'ed50eae7', '--', 'settings.gradle.kts', 'desktop/build.gradle.kts', 'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml'], cwd=repo, text=True),
        'evidence': 'All builds GTHost only. b19ff9a9 compiled both platforms and passed14coretests. 773de56c full core/domain/desktop/Android-local suites and Debian package passed. 8bee5c02 full desktop/Android-local tests and both debug/AndroidTest APKs/package passed, fresh pinned native engine source matched byte-for-byte against export. Early incorrect CGAL5 prefix failed configure; corrected to verified6.2.1 prefix, no engine changes. Resource failures retained: early total processCPU9-10%onecore100Hz, later fcdbf7d0 steady5.52% stillFAIL. 605b2985 steady4.6666%, incrementalRSS6.35MB/drop0 passed resource case but suiteFAILED2deleteVACUUM tests due cached active statements. Current source closes cache before VACUUM and adds tests; fresh exactsource validation pending, not claimedPASS. Owner confirms no BTHome hardware available, so physical BTHome qualification cannot pass. No current installed/radio/battery/Goveequalification evidence yet. No serials/MACs/credentials/raw nearby payloads in this package.',
        'implementation_details': 'OS scan callbacks are bounded atomic enqueue only, no gate/DB locks or direct disk writes; failure coalesces onto IO. Visibility epoch invalidates scan preparation and admission; Android composition disposal closes owned resources asynchronously and creates no cleanup worker for untouched off/idle. JDBC statement cache max32, cache closed before VACUUM, max100ms/32report batches. Stable encodingv1 preserves raw/calibration/collector/session/boot/clock provenance; receivedAt stored separately, never immutable retry identity. Dropped reports are persisted with batch/gap records and exported; unknown missing coverage remains explicit. Initial API31+ operation requests SCAN only, not CONNECT; no GATT/name/bond/alias APIs. Android collection uses selected-address OS scan filters plus host postfilter, bounded unfiltered discovery only. Linux uses own connection/discovery reference, first powered adapter deterministically or explicitly configured path and reports selected adapter. Draft equipment/positions reuse IDs and draft+placement saves are atomic. No qualified firmware/thermal/calibration claims or assigned deployment.',
        'review_questions': 'Find actual implementation defects, missing B-D requirements, unsafe lifecycle/permission/BlueZ ownership/deletion/history concurrency and resource failures. Unknown firmware/calibration/thermal evidence must not become qualified dryer deployment. Distinguish missing physical acceptance evidence from source bugs. E-G background/sync/HA/MQTT/analytics/alerts are intentionally absent. Android initial scope STARTED Activity only, no background service; local environmental data never projectDB/3MF. Compiled descriptors inert. CPU/memory/physical qualification pending, not PASS.',
        'response_format': 'Concise JSON summary and findings array; severity BLOCKER/HIGH/MEDIUM/LOW/INFORMATIONAL, area, finding, evidence, consequence, required_correction, acceptance_test, confidence. Report all material defects but avoid repeating requirements as bugs or speculative runtime claims. No chain of thought. Initial reviews independent; no other reviewer conclusions supplied.'}
    print(json.dumps(call({'operation': 'control_submit', 'change_id': change, 'package': package,
                          'reviewers': ['council-synthetic-systems', 'council-synthetic-frontend', 'council-synthetic-adversarial']})))
elif sys.argv[1] == 'status':
    print(json.dumps(call({'operation': 'status', 'cycle_id': sys.argv[2]})))
elif sys.argv[1] == 'response':
    print(json.dumps(call({'operation': 'response', 'cycle_id': sys.argv[2], 'reviewer': 'council-synthetic-' + sys.argv[3]})))
