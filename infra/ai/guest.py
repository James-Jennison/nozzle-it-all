#!/usr/bin/env python3
"""Run explicit commands in the AI guest via the existing Proxmox SSH control.

QEMU guest agent avoids adding a public listener or provisioning login secrets.
Only VM 300 with the expected name may receive a command. Never pass secrets.
"""
import json
import subprocess
import sys


def execute(argv, stdin=None):
    remote = '''import json,subprocess,sys,base64
cfg=subprocess.check_output(['qm','config','300'],text=True)
assert '\\nname: klipper-ai-01\\n' in '\\n'+cfg
payload=json.loads(sys.stdin.readline())
cmd=payload['argv']
data=base64.b64decode(payload['stdin']) if payload['stdin'] is not None else None
assert data is None or len(data)<=1048576
result=subprocess.run(['qm','guest','exec','300','--timeout','45','--pass-stdin',str(int(data is not None)),'--',*cmd],input=data,capture_output=True)
result.stdout=result.stdout.decode();result.stderr=result.stderr.decode()
sys.stdout.write(result.stdout)
sys.stderr.write(result.stderr)
sys.exit(result.returncode)
'''
    # Execute remote code as one properly quoted SSH argument, send argv as stdin.
    import shlex
    import base64
    payload={'argv':argv,'stdin':base64.b64encode(stdin).decode() if stdin is not None else None}
    result=subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
                           '-o','ConnectTimeout=10','pve-sv1-admin',
                           'python3 -c '+shlex.quote(remote)],
                          input=json.dumps(payload)+'\n',text=True,capture_output=True,timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    value=json.loads(result.stdout)
    if not value.get('exited'):
        raise RuntimeError('Guest command still running: '+json.dumps(value))
    return value


if __name__=='__main__':
    result=execute(sys.argv[1:])
    sys.stdout.write(result.get('out-data',''))
    sys.stderr.write(result.get('err-data',''))
    sys.exit(result.get('exitcode',1))
