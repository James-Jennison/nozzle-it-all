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
    if len(sys.argv) != 3:
        raise SystemExit('submit requires a sanitized, source-bound evidence JSON file')
    current_evidence = json.loads(Path(sys.argv[2]).read_text())
    if current_evidence.get('source_revision') != revision:
        raise SystemExit('Evidence source_revision must equal current HEAD')
    paths = [*repo.glob('environment-*/src/**/*.kt'), *repo.glob('environment-*/build.gradle.kts'),
             *repo.glob('desktop/src/main/kotlin/com/nozzleitall/desktop/environment/*.kt'),
             *repo.glob('app/src/main/java/net/jamesjennison/klippercompanion/environment/*.kt'),
             repo/'app/src/main/java/net/jamesjennison/klippercompanion/NozzleApp.kt',
             repo/'app/src/androidTest/java/net/jamesjennison/klippercompanion/EnvironmentalDeviceTest.kt',
             repo/'desktop/src/test/kotlin/com/nozzleitall/desktop/EnvironmentalStoreTest.kt',
             repo/'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalPanel.kt']
    sources = {str(p.relative_to(repo)): p.read_text() for p in paths}
    for path in ['desktop/src/main/kotlin/com/nozzleitall/desktop/Main.kt', 'app/src/main/java/net/jamesjennison/klippercompanion/MainActivity.kt']:
        sources[path] = '\n'.join(subprocess.check_output(['git', 'diff', 'ed50eae7', '--', path], cwd=repo, text=True).splitlines())
    package = {
        'phase': 'INITIAL_REVIEW',
        'task': 'Independent read-only implementation review of approved B-D environmental monitoring. Return normalized JSON findings, not patch execution. No tool execution or workspace access. Owner requirements are authoritative; no majority vote or fabricated runtime proof.',
        'source_revision': revision,
        'owner_requirements': json.loads((repo/'docs/handover/NIA_ENVIRONMENT_ACCEPTANCE_SCOPE.json').read_text()),
        'requirements_precedence': 'The owner-directed acceptance scope is authoritative. Historical proposals and reviewer recommendations do not authorize hardware procurement, additional certification programs or unrequested feature work. Untested capabilities are limitations, not added completion gates.',
        'approved_scope': (repo/'docs/handover/NIA_ENVIRONMENT_IMPLEMENTATION.md').read_text(),
        'sources': sources,
        'build_diff': subprocess.check_output(['git', 'diff', 'ed50eae7', '--', 'settings.gradle.kts', 'desktop/build.gradle.kts', 'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml'], cwd=repo, text=True),
        'response_format': 'Concise JSON summary and findings array; severity BLOCKER/HIGH/MEDIUM/LOW/INFORMATIONAL, area, finding, evidence, consequence, required_correction, acceptance_test, confidence. Report all material defects but avoid repeating requirements as bugs or speculative runtime claims. No chain of thought. Initial reviews independent; no other reviewer conclusions supplied.'}
    # Require explicit source-bound evidence; never replay an old checkpoint as current proof.
    package['evidence'] = current_evidence
    package['implementation_details'] = 'Current source and supplied source-bound evidence are authoritative; historical checkpoint prose is not runtime proof.'
    package['review_questions'] = 'Review current source for lifecycle, privacy, storage, platform and resource defects against the owner-directed scope. Untested capabilities remain limitations, not added hardware, procurement or certification completion gates. Do not add unrequested providers or feature work. No model substitution, requirement changes or E-G expansion.'
    print(json.dumps(call({'operation': 'control_submit', 'change_id': change, 'package': package,
                          'reviewers': ['council-synthetic-systems', 'council-synthetic-frontend', 'council-synthetic-adversarial']})))
elif sys.argv[1] == 'status':
    print(json.dumps(call({'operation': 'status', 'cycle_id': sys.argv[2]})))
elif sys.argv[1] == 'response':
    print(json.dumps(call({'operation': 'response', 'cycle_id': sys.argv[2], 'reviewer': 'council-synthetic-' + sys.argv[3]})))
