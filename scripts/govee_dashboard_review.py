#!/usr/bin/env python3
"""Owner-requested Synthetic review; source and synthetic fixtures only."""
import json
import runpy
import subprocess
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
call = runpy.run_path('/home/jjennison/.local/bin/reviewer', run_name='client')['broker_request']
change = 'nia-govee-dashboard-' + revision[:8]
print(json.dumps(call({'operation': 'register_change', 'change': {
    'change_id': change, 'repository': str(repo), 'classification': 'SUBSTANTIVE',
    'summary': 'Real environmental visual dashboard and persistent correct Govee names',
    'affected_components': ['environment-core', 'app', 'desktop'],
    'provenance': {'source_revision': revision}}})))
paths = [
    'environment-core/src/main/kotlin/com/nozzleitall/environment/EnvironmentalCharts.kt',
    'environment-core/src/main/kotlin/com/nozzleitall/environment/EnvironmentalCoordinator.kt',
    'environment-core/src/main/kotlin/com/nozzleitall/environment/GoveeNames.kt',
    'environment-core/src/test/kotlin/com/nozzleitall/environment/EnvironmentalChartsTest.kt',
    'environment-core/src/test/kotlin/com/nozzleitall/environment/GoveeNamesTest.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalDashboard.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalPanel.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/EnvironmentalDashboard.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/EnvironmentalPanel.kt']
package = {
    'phase': 'INITIAL_REVIEW', 'source_revision': revision,
    'task': 'Independent read-only Synthetic council review. Return concise JSON findings severity/area/finding/evidence/consequence/required_correction/acceptance_test/confidence. No tools/writes/substitutions/new requirements. Distinguish source defects from missing final validation. Owner approved implementation, not new protocols or hardware qualification programs.',
    'owner_requirements': 'Correct automatic Govee Home names without owner MAC identification. Preserve custom names and existing data. Actual visual temperature/humidity data, not text-only rows. Foreground local BLE with explicit Start, no background restart/control/cloud readings. Private key entry. Complete desktop and existing Play Alpha update and normal Razr verification.',
    'design': 'H5100 eight-byte cloud IDs contain six little-endian BLE address bytes followed by two extra bytes; H5110 uses two prefix bytes followed by address. Compare full48bits/model and require unique directory match. Restore saved labels in explicit IO preparation; discovery keeps saved metadata but empty radio filter to discover all. Hoist desktop memory-only name directory to AppState. Chart buffers capped500 globally; clear with latest at Stop/visibility/deletion; no constructor/Enable IO. Explicit history query/JSON parsing on IO, bounded500 rows. Native Compose cards, selectable sensor, two Canvas plots, timestamp-proportional axis, accessible range/time summary; no invented samples/safe bands. Session changes, known drops, sequence/time reversals and gaps over2min break lines. History and live merge by event ID, bound500. Saved custom labels are never automatically overwritten.',
    'primary_reference': 'https://github.com/wez/govee2mqtt/issues/308 independently demonstrates H5100 eight-byte cloud ID little-endian-address layout. https://github.com/homebridge-plugins/homebridge-govee/blob/latest/lib/utils/device-context.js demonstrates other prefixed IDs. Synthetic fixtures only in package; actual owner identifiers omitted.',
    'evidence': 'Current exact-source GTHost full validation running; no new PASS/install/runtime claimed. Added pure series/time/gap/dedup/calibration/bound tests, H5100 full-identity regressions and coordinator fresh-projection/rescan label restoration regression. Desktop installed baseline940 and normal Play275 preserved until final integrated candidate. Prior fixtures do not prove this new UI. Final runtime/account proof remains pending.',
    'sources': {p: (repo / p).read_text() for p in paths}}
print(json.dumps(call({'operation': 'control_submit', 'change_id': change, 'package': package,
    'reviewers': ['council-synthetic-systems', 'council-synthetic-frontend', 'council-synthetic-adversarial']})))
