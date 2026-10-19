#!/usr/bin/env python3
"""Submit source-only sanitized name-import review; no credential/device data read."""
import json
import runpy
import subprocess
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
call = runpy.run_path('/home/jjennison/.local/bin/reviewer', run_name='client')['broker_request']
change = 'nia-govee-names-desktop-' + revision[:8]
print(json.dumps(call({'operation': 'register_change', 'change': {
    'change_id': change, 'repository': str(repo), 'classification': 'SUBSTANTIVE',
    'summary': 'Private read-only Govee name import Android/Desktop; owned async credential cleanup; Linux launcher integration correction',
    'affected_components': ['environment-core', 'app', 'desktop'],
    'provenance': {'source_revision': revision}}})))
paths = [
    'environment-core/src/main/kotlin/com/nozzleitall/environment/GoveeNames.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/environment/DesktopGoveeNames.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/GoveeNameImport.kt',
    'desktop/src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalPanel.kt',
    'desktop/src/test/kotlin/com/nozzleitall/desktop/DesktopGoveeNamesTest.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/AndroidGoveeNames.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/GoveeNameImport.kt',
    'app/src/main/java/net/jamesjennison/klippercompanion/environment/EnvironmentalPanel.kt',
    'app/src/androidTest/java/net/jamesjennison/klippercompanion/GoveeNameImportDeviceTest.kt',
    'environment-core/src/test/kotlin/com/nozzleitall/environment/GoveeNamesTest.kt',
    'scripts/package_linux.sh', 'desktop/packaging/linux/nozzle-it-all.desktop']
package = {
    'phase': 'INITIAL_REVIEW', 'source_revision': revision,
    'task': 'Independent read-only review. No tool execution, mutation, requirement changes or model substitution. Return JSON findings with severity, area, finding, evidence, consequence, required_correction, acceptance_test, confidence.',
    'owner_requirements': 'Explicit read-only Govee Home device-name lookup with private in-app key entry on Android AND desktop. BLE measurements remain local foreground only. No equipment control. Preserve existing normal Android app data via existing Play Alpha update. Correct Linux dock icon. No added protocols, procurement, certification or hardware acceptance gates.',
    'design': 'Only full MAC+model auto-matches; otherwise explicit same-model selection. Existing labels never overwritten automatically. Android optional dedicated encrypted preferences; desktop key session-only, never persisted. ATOMIC app-owned IO cleanup starts before returning its handle and fences newer imports until completion. Main-visible monitor never holds disk IO. Desktop cancellation and publication serialized; focus loss clears private dialog and invalidates results.',
    'evidence': 'Current source GTHost full build/tests running, no success claimed yet. Prior source12c full build SUCCESS; its device fixtures6/7PASS, one label-based UI selector failed and is now test-tag based, requires re-run. Normal app and actual-account import NOT verified yet. Installed Linux currently lacks standard launcher/icons: raw jpackage Deb was installed rather than integrated scripts/package_linux.sh output. Will install/verify integrated package, not raw Deb.',
    'sources': {p: (repo/p).read_text() for p in paths}}
print(json.dumps(call({'operation': 'control_submit', 'change_id': change, 'package': package,
    'reviewers': ['council-synthetic-systems', 'council-synthetic-frontend', 'council-synthetic-adversarial']})))
