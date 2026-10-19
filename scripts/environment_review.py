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
change = "nia-environment-bcd-20261018"
call = runpy.run_path('/home/jjennison/.local/bin/reviewer', run_name='client')['broker_request']
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
if sys.argv[1] == 'register':
    print(json.dumps(call({'operation': 'register_change', 'change': {
        'change_id': change, 'repository': str(repo), 'classification': 'SUBSTANTIVE',
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
        'task': 'Independent read-only implementation review of approved B-D environmental monitoring. Return normalized JSON findings, not patch execution. No tool execution or workspace access. Owner requirements are authoritative; no majority vote or fabricated runtime proof.',
        'source_revision': revision,
        'owner_requirements': (repo/'docs/ENVIRONMENT_MONITORING_ARCHITECTURE.md').read_text(),
        'approved_scope': (repo/'docs/handover/NIA_ENVIRONMENT_IMPLEMENTATION.md').read_text(),
        'sources': sources,
        'build_diff': subprocess.check_output(['git', 'diff', 'ed50eae7', '--', 'settings.gradle.kts', 'desktop/build.gradle.kts', 'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml'], cwd=repo, text=True),
        'evidence': 'GTHost b19ff9a9 compiled desktop and Android Kotlin and passed all 14 environmental JUnit cases. Earlier 18833767 failed shared nullable smartcasts; fixed in b19, raw logs retained. Later source changes require fresh validation, currently in progress. No current source package/install/real radio, performance or hardware qualification claimed. Hardware serials, nearby addresses, account information, credentials and raw personal data excluded.',
        'review_questions': 'Find actual implementation defects, missing B-D requirements, unsafe lifecycle/permission/BlueZ ownership/deletion/history concurrency and resource failures. Unknown firmware/calibration/thermal evidence must not become qualified dryer deployment. Distinguish missing physical acceptance evidence from source bugs. E-G background/sync/HA/MQTT/analytics/alerts are intentionally absent. Android initial scope STARTED Activity only, no background service; local environmental data never projectDB/3MF. Compiled descriptors inert. CPU/memory/physical qualification pending, not PASS.',
        'response_format': 'JSON summary and findings array; severity BLOCKER/HIGH/MEDIUM/LOW/INFORMATIONAL, area, finding, evidence, consequence, required_correction, acceptance_test, confidence. Findings independent before seeing others.'}
    print(json.dumps(call({'operation': 'control_submit', 'change_id': change, 'package': package,
                          'reviewers': ['council-synthetic-systems', 'council-synthetic-frontend', 'council-synthetic-adversarial']})))
elif sys.argv[1] == 'status':
    print(json.dumps(call({'operation': 'status', 'cycle_id': sys.argv[2]})))
elif sys.argv[1] == 'response':
    print(json.dumps(call({'operation': 'response', 'cycle_id': sys.argv[2], 'reviewer': 'council-synthetic-' + sys.argv[3]})))
