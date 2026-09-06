#!/usr/bin/env python3
"""Read-only deployment validation plus focused guest API tests. No provisioning."""
import hashlib
import json
from pathlib import Path
import subprocess
from guest import execute

ROOT=Path(__file__).resolve().parent
EXPECTED_MODEL='0a6ebd8e30dbf6a450c50f9c0a5406f04ba7eb1c99fd5996e888c78bb383b9aa'
def guest(args):
    result=execute(args)
    assert result.get('exitcode')==0, result
    if result.get('err-data'):
        print(result['err-data'])
    return result.get('out-data','')

for script in ['provision.sh','install-guest.sh']:
    subprocess.run(['bash','-n',str(ROOT/script)],check=True)
hashes={n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() for n in
        ['service.py','test_service.py','benchmark.py','klipper-ai.service','requirements.txt','requirements.lock','install-guest.sh','cloud-init.yaml']}
code='''import hashlib,json,pathlib,subprocess,urllib.request,socket
root=pathlib.Path('/opt/klipper-ai')
expected=json.loads(%r)
for name,value in expected.items():
 assert hashlib.sha256((root/name).read_bytes()).hexdigest()==value, name+' differs from reviewed source'
assert hashlib.sha256((root/'model.onnx').read_bytes()).hexdigest()==%r
run=lambda *a:subprocess.check_output(a,text=True).strip()
assert run('git','-C',str(root/'vendor'),'rev-parse','HEAD')=='49c0bc7001a3fd8d56297fc3032ba287bfe1d50b'
assert not run('git','-C',str(root/'vendor'),'status','--porcelain')
assert run('systemctl','is-active','klipper-ai')=='active'
assert run('systemctl','show','klipper-ai','-p','CPUQuotaPerSecUSec','--value')=='2s'
assert run('systemctl','show','klipper-ai','-p','MemoryMax','--value')==str(3*1024**3)
assert hashlib.sha256(pathlib.Path('/etc/systemd/system/klipper-ai.service').read_bytes()).hexdigest()==expected['klipper-ai.service']
for service in ['ssh.service','ssh.socket']:
 assert subprocess.run(['systemctl','is-active',service],stdout=subprocess.DEVNULL).returncode!=0
listeners=run('ss','-H','-lnt')
assert '127.0.0.1:3333' in listeners
assert all(line.split()[3].startswith('127.') for line in listeners.splitlines()), 'Unexpected non-loopback TCP listener'
with urllib.request.urlopen('http://127.0.0.1:3333/health',timeout=5) as r:
 health=json.load(r)
assert health['status']=='ready' and health['automatic_printer_actions'] is False
egress={}
for address,port in [('1.1.1.1',443),('1.1.1.1',80),('10.40.0.21',22)]:
 with socket.socket() as sock:
  sock.settimeout(2)
  try:
   sock.connect((address,port))
  except OSError:
   egress[f'{address}:{port}']='blocked/unreachable'
  else:
   raise AssertionError(f'Unexpected egress connection to {address}:{port}')
print(json.dumps({'status':'PASS','model_sha256':%r,'health':health,'listeners':listeners,'active_tcp_probes':egress}))
'''%(json.dumps(hashes),EXPECTED_MODEL,EXPECTED_MODEL)
print(guest(['python3','-c',code]))
print(guest(['cloud-init','schema','-c','/opt/klipper-ai/cloud-init.yaml']))
print(guest(['bash','-c','cd /opt/klipper-ai && venv/bin/pip check && venv/bin/python -m unittest -v test_service']))

remote='''import pathlib,hashlib,json,subprocess
root=pathlib.Path('/root/klipper-ai-provisioning')
baseline=json.loads((root/'protected-baseline.json').read_text())
for vmid,old in baseline.items():
 conf=pathlib.Path(f'/etc/pve/qemu-server/{vmid}.conf')
 fw=pathlib.Path(f'/etc/pve/firewall/{vmid}.fw')
 assert hashlib.sha256(conf.read_bytes()).hexdigest()==old['config_sha256'], 'Protected VM config changed: '+vmid
 assert (hashlib.sha256(fw.read_bytes()).hexdigest() if fw.exists() else None)==old['firewall_sha256']
 assert subprocess.check_output(['qm','status',vmid],text=True).strip()==old['status']
cfg=dict(l.split(': ',1) for l in subprocess.check_output(['qm','config','300'],text=True).splitlines() if ': ' in l)
for k,v in {'name':'klipper-ai-01','cores':'4','memory':'8192','cpulimit':'4','balloon':'0','protection':'1','onboot':'0','cicustom':'user=local:snippets/klipper-ai-300.yaml'}.items():assert cfg.get(k)==v,(k,cfg.get(k))
assert 'size=100G' in cfg['scsi0']
fw=pathlib.Path('/etc/pve/firewall/300.fw').read_text()
assert 'policy_in: DROP' in fw and 'policy_out: DROP' in fw and 'IN ACCEPT' not in fw
assert 'OUT ACCEPT' not in fw, 'Temporary provisioning egress remains open'
assert 'OUT DROP -dest 10.0.0.0/8' in fw
status=subprocess.check_output(['pve-firewall','status'],text=True)
assert 'enabled/running' in status
print(json.dumps({'status':'PASS','protected_vms':list(baseline),'vm300':'4 vCPU / 8 GiB / 100 GiB','firewall':status.strip()}))
'''
result=subprocess.run(['ssh','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10',
                       'pve-sv1-admin','python3 -'],input=remote,text=True,capture_output=True,timeout=60)
assert result.returncode==0,result.stderr
print(result.stdout)
