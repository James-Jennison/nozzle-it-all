#!/usr/bin/env python3
"""Remove temporary download egress from this evaluation guest after health passes."""
import json
import subprocess
from guest import execute

result=execute(['curl','--fail','--silent','http://127.0.0.1:3333/health'])
assert result.get('exitcode')==0,result
assert json.loads(result['out-data'])['status']=='ready'
code='''import pathlib,subprocess
cfg=subprocess.check_output(['qm','config','300'],text=True)
assert '\\nname: klipper-ai-01\\n' in '\\n'+cfg
p=pathlib.Path('/etc/pve/firewall/300.fw')
old=p.read_text()
assert 'policy_in: DROP' in old and 'policy_out: DROP' in old and 'IN ACCEPT' not in old
new='\\n'.join(line for line in old.splitlines() if not line.startswith('OUT ACCEPT '))+'\\n'
if new!=old:p.write_text(new)
subprocess.run(['pve-firewall','compile'],stdout=subprocess.DEVNULL,check=True)
print('VM 300 provisioning egress removed; all inbound/outbound IP traffic denied.')
'''
subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
                '-o','ConnectTimeout=10','pve-sv1-admin','python3 -'],input=code,text=True,check=True)
