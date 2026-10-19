#!/usr/bin/env python3
"""Submit a source-only, sanitized Synthetic review; no account/device data."""
import json
import runpy
import subprocess
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
call = runpy.run_path('/home/jjennison/.local/bin/reviewer', run_name='client')['broker_request']
change = 'nia-govee-matching-' + revision[:8]
print(json.dumps(call({'operation': 'register_change', 'change': {
    'change_id': change, 'repository': str(repo), 'classification': 'SUBSTANTIVE',
    'summary': 'Full-address automatic Govee name matching without owner MAC identification',
    'affected_components': ['environment-core', 'app', 'desktop'],
    'provenance': {'source_revision': revision}}})))
paths = [
    'environment-core/src/main/kotlin/com/nozzleitall/environment/GoveeNames.kt',
    'environment-core/src/test/kotlin/com/nozzleitall/environment/GoveeNamesTest.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalPanel.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/GoveeNameImport.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/EnvironmentalPanel.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/GoveeNameImport.kt']
package = {
    'phase': 'INITIAL_REVIEW', 'source_revision': revision,
    'task': 'Independent read-only targeted review. No tools, writes, model substitutions, added requirements or fabricated runtime proof. Return JSON findings with severity, area, finding, evidence, consequence, required_correction, acceptance_test, confidence.',
    'owner_requirements': 'Show correct Govee Home names automatically, without asking owner to identify unavailable MAC addresses. Private read-only API key entry; BLE readings remain local/foreground. Preserve normal Razr data via existing Play Alpha update. Desktop parity. No extra protocols or qualification gates.',
    'change': 'Previous matcher rejected all eight-byte cloud IDs. Now strict six/eight-byte cloud hex identity parsing, exactly six-byte candidate parsing; strip exactly two prefix bytes only for eight-byte cloud IDs, compare ALL 48 address bits AND model, singleOrNull directory match at each UI caller. No short advertised suffix matching. Invalid, mixed-separator, opaque and ambiguous inputs do not auto-name. Existing saved labels/manual drafts retain precedence. Desktop primary heading now displays friendly label. Optional manual override no longer truncates cloud IDs or instructs owner to guess.',
    'primary_reference': 'https://github.com/homebridge-plugins/homebridge-govee/blob/latest/lib/utils/device-context.js uses parsed.address when present, else device.device.substring(6) for BLE address. This is primary implementation evidence of Govee prefixed IDs, not a universal vendor guarantee. Actual account mapping still needs runtime verification without exposing key.',
    'evidence': 'Exact source remote GTHost full tests/build running; no new PASS claimed. Added regression fixtures for eight-byte colon/hyphen/compact IDs, six-byte IDs, full-address mismatch sharing last two bytes, malformed/mixed separators, unsupported protocol/model, candidate eight-byte rejection and conflicting-directory unique-match failure. Tests contain synthetic identifiers only. Prior C6 full lifecycle/device tests remain historical; normal Play update pending.',
    'sources': {p: (repo/p).read_text() for p in paths}}
print(json.dumps(call({'operation': 'control_submit', 'change_id': change, 'package': package,
    'reviewers': ['council-synthetic-systems', 'council-synthetic-frontend', 'council-synthetic-adversarial']})))
