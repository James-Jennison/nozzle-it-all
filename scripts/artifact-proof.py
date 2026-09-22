#!/usr/bin/env python3
"""Bind debug APK to source inputs without copying local configuration or credentials."""
from pathlib import Path
import hashlib,json,subprocess,sys,zipfile
root=Path(__file__).resolve().parent.parent
asset=root/'app/src/main/assets/source-proof.json'
def digest(data):return hashlib.sha256(data).hexdigest()
def submodule_pins():
 # WO-13: third_party/orcaslicer and third_party/onetbb are vendored, pinned submodules, not
 # prebuilt binaries - this is the actual mechanism answering the provenance gap that
 # disqualified the Snapmaker u1-slicer-for-android binary (an unattested prebuilt .so with no
 # link back to a known source commit). `git submodule status` reports each submodule's checked-
 # out commit without touching the network, so this stays as cheap and offline as the rest of
 # inputs(). A submodule left uninitialized (no native build attempted yet) is skipped rather
 # than failing prepare/verify for developers who never touch WO-13.
 out=subprocess.run(['git','submodule','status'],cwd=root,capture_output=True,text=True,check=True).stdout
 pins={}
 for line in out.splitlines():
  line=line.strip()
  if not line:continue
  sha,path=line.lstrip('-+U ').split()[0],line.split()[1]
  if not line.startswith('-'):pins[path]=sha
 return pins
def inputs():
 paths=[root/x for x in ['build.gradle.kts','settings.gradle.kts','gradle.properties','app/build.gradle.kts','gradle/wrapper/gradle-wrapper.properties','scripts/artifact-proof.py']]
 paths += [p for p in (root/'app/src/main').rglob('*') if p.is_file() and p != asset]
 manifest={str(p.relative_to(root)):digest(p.read_bytes()) for p in sorted(paths)}
 manifest['submodules']=submodule_pins()
 return manifest
expected=inputs()
if sys.argv[1]=='prepare':
 asset.parent.mkdir(parents=True,exist_ok=True);asset.write_text(json.dumps(expected,sort_keys=True,indent=2)+'\n')
elif sys.argv[1]=='verify':
 apk=root/'app/build/outputs/apk/debug/app-debug.apk'
 with zipfile.ZipFile(apk) as z: actual=json.loads(z.read('assets/source-proof.json'))
 if actual != expected:raise SystemExit('FAIL: APK source proof does not match current source')
 print(json.dumps({'status':'PASS','apk_sha256':digest(apk.read_bytes()),'source_manifest_sha256':digest(json.dumps(expected,sort_keys=True).encode())}))
else:raise SystemExit('Expected prepare or verify')
