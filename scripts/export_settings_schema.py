#!/usr/bin/env python3
"""Writes schemas/slicing/settings-schema.json from the shared engine (`nozzle-engine --schema`), with the engine's
source commit, so every Nozzle settings screen is generated from what libslic3r actually accepts. --check fails if the
committed file differs from the engine's output."""
import json, os, subprocess, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'schemas/slicing/settings-schema.json')
engine = os.environ.get('NOZZLE_ENGINE', '/mnt/faststorage/build-work/nozzle-native-fork/dist/nozzle-engine')
raw = json.loads(subprocess.run([engine, '--schema'], check=True, capture_output=True, text=True).stdout)
# The engine is nozzle-engine at its pinned commit (engine/fork/ENGINE_PIN.json).
pin = json.load(open(os.path.join(ROOT, 'engine/fork/ENGINE_PIN.json')))
raw['source'] = {'engine': 'libslic3r (nozzle-engine)', 'repo': pin['base']['repo'], 'commit': pin['base']['commit'], 'licence': 'AGPL-3.0'}
text = json.dumps(raw, indent=1, ensure_ascii=False) + '\n'
if '--check' in sys.argv:
    same = os.path.exists(OUT) and open(OUT, encoding='utf8').read() == text
    print('settings schema', 'up to date' if same else 'OUT OF DATE (run scripts/export_settings_schema.py)'); sys.exit(0 if same else 1)
open(OUT, 'w', encoding='utf8').write(text); print(f"wrote {len(raw['options'])} options to {OUT}")
