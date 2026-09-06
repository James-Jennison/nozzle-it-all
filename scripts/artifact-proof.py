#!/usr/bin/env python3
"""Bind debug APK to source inputs without copying local configuration or credentials."""
from pathlib import Path
import hashlib,json,sys,zipfile
root=Path(__file__).resolve().parent.parent
asset=root/'app/src/main/assets/source-proof.json'
def digest(data):return hashlib.sha256(data).hexdigest()
def inputs():
 paths=[root/x for x in ['build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','gradle/wrapper/gradle-wrapper.properties','scripts/artifact-proof.py']]
 paths += [p for p in (root/'app/src/main').rglob('*') if p.is_file() and p != asset]
 return {str(p.relative_to(root)):digest(p.read_bytes()) for p in sorted(paths)}
expected=inputs()
if sys.argv[1]=='prepare':
 asset.parent.mkdir(parents=True,exist_ok=True);asset.write_text(json.dumps(expected,sort_keys=True,indent=2)+'\n')
elif sys.argv[1]=='verify':
 apk=root/'app/build/outputs/apk/debug/app-debug.apk'
 with zipfile.ZipFile(apk) as z: actual=json.loads(z.read('assets/source-proof.json'))
 if actual != expected:raise SystemExit('FAIL: APK source proof does not match current source')
 print(json.dumps({'status':'PASS','apk_sha256':digest(apk.read_bytes()),'source_manifest_sha256':digest(json.dumps(expected,sort_keys=True).encode())}))
else:raise SystemExit('Expected prepare or verify')
