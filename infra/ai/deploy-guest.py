#!/usr/bin/env python3
"""Stage exact source into the AI guest and start its bounded installer.

Idempotent only for an identical staged source set; refuses divergent files.
No credentials or guest login keys are needed or transferred.
"""
import base64
import hashlib
import json
from pathlib import Path
from guest import execute

root=Path(__file__).resolve().parent
names=['service.py','test_service.py','benchmark.py','klipper-ai.service',
       'requirements.txt','requirements.lock','install-guest.sh','cloud-init.yaml']
files={n:base64.b64encode((root/n).read_bytes()).decode() for n in names}
manifest={n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in names}
code='''import json,sys,base64,hashlib,pathlib
data=json.load(sys.stdin);root=pathlib.Path('/opt/klipper-ai')
files=data['files'];manifest=data['manifest']
assert set(files)==set(manifest)
for name,encoded in files.items():
 assert '/' not in name and name not in ('.','..')
 assert hashlib.sha256(base64.b64decode(encoded)).hexdigest()==manifest[name]
existed=root.exists()
if existed:
 for name,sha in manifest.items():
  assert (root/name).is_file() and hashlib.sha256((root/name).read_bytes()).hexdigest()==sha, 'Existing source differs: '+name
 print('IDENTICAL')
else:
 root.mkdir(mode=0o755)
 for name,encoded in files.items():
  dest=root/name;dest.write_bytes(base64.b64decode(encoded));dest.chmod(0o644)
 for name,sha in manifest.items():assert hashlib.sha256((root/name).read_bytes()).hexdigest()==sha
 print('STAGED')
(root/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\\n')
'''
result=execute(['python3','-c',code],stdin=json.dumps({'files':files,'manifest':manifest}).encode())
assert result.get('exitcode')==0,result
if result.get('out-data','').strip()=='STAGED':
    result=execute(['systemd-run','--unit=klipper-ai-install','--property=CPUQuota=200%',
                    '--property=MemoryMax=4G','--property=RuntimeMaxSec=1200',
                    '/bin/bash','/opt/klipper-ai/install-guest.sh'])
    assert result.get('exitcode')==0,result
    print('Installer launched; inspect its result before sealing the network.')
else:
    print('Identical files already staged; installation was not replayed.')
