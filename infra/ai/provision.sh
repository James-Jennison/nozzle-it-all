#!/usr/bin/env bash
# New guest only. Existing runner/template configuration is never modified.
set -euo pipefail
cd "$(dirname "$0")"
target=pve-sv1-admin
opts=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10)
ssh "${opts[@]}" "$target" 'python3 -' <<'PY'
import subprocess,json,pathlib,hashlib,ipaddress
run=lambda *args:subprocess.check_output(args,text=True)
assert 'pve-manager/9.2' in run('pveversion')
assert not pathlib.Path('/etc/pve/qemu-server/300.conf').exists(), 'VM 300 already exists; inspect, do not overwrite'
assert not pathlib.Path('/etc/pve/lxc/300.conf').exists()
assert not pathlib.Path('/etc/pve/firewall/300.fw').exists()
assert not pathlib.Path('/var/lib/vz/snippets/klipper-ai-300.yaml').exists()
assert not pathlib.Path('/root/klipper-ai-provisioning').exists(), 'Existing operation requires recovery inspection'
cfg=lambda i:dict(l.split(': ',1) for l in run('qm','config',str(i)).splitlines() if ': ' in l)
t=cfg(9000)
assert t.get('template')=='1' and t.get('name')=='ubuntu-2404-actions-runner-template'
assert 'base-9000-disk-0' in t.get('scsi0','')
assert t.get('net0','').split('bridge=')[1].split(',')[0]=='privnet'
assert 'snippets' in json.loads(run('pvesh','get','/storage/local','--output-format','json'))['content']
subnets=json.loads(run('pvesh','get','/cluster/sdn/vnets/privnet/subnets','--output-format','json'))
assert any(s.get('cidr')=='10.40.0.0/24' and s.get('gateway')=='10.40.0.1' and s.get('snat')==1 for s in subnets)
for p in [*pathlib.Path('/etc/pve/qemu-server').glob('*.conf'),*pathlib.Path('/etc/pve/lxc').glob('*.conf')]:
 assert '10.40.0.30' not in p.read_text(), 'Address already configured'
assert subprocess.run(['ping','-c','2','-W','1','10.40.0.30'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode!=0, 'Address responds'
baseline={}
for i in [201,202,203,204,205,206,9000]:
 c=pathlib.Path(f'/etc/pve/qemu-server/{i}.conf');f=pathlib.Path(f'/etc/pve/firewall/{i}.fw')
 baseline[str(i)]={'config_sha256':hashlib.sha256(c.read_bytes()).hexdigest(),'firewall_sha256':hashlib.sha256(f.read_bytes()).hexdigest() if f.exists() else None,'status':run('qm','status',str(i)).strip()}
root=pathlib.Path('/root/klipper-ai-provisioning');root.mkdir(mode=0o700)
(root/'protected-baseline.json').write_text(json.dumps(baseline,indent=2))
print('PASS: target, new VM/address, storage and protected baseline checked')
PY
scp "${opts[@]}" cloud-init.yaml "$target:/root/klipper-ai-provisioning/cloud-init.yaml"
ssh "${opts[@]}" "$target" 'bash -s' <<'REMOTE'
set -euo pipefail
test ! -e /etc/pve/qemu-server/300.conf
install -m 0644 /root/klipper-ai-provisioning/cloud-init.yaml /var/lib/vz/snippets/klipper-ai-300.yaml
qm clone 9000 300 --name klipper-ai-01 --full 1 --storage vmdata
qm set 300 --cores 4 --sockets 1 --cpu host --memory 8192 --balloon 0 \
  --cpulimit 4 --cpuunits 100 --onboot 0 \
  --description 'Klipper AI evaluation; CPU-only; no runner; no public listener' \
  --tags 'klipper-ai;evaluation' --ipconfig0 ip=10.40.0.30/24,gw=10.40.0.1 \
  --nameserver '1.1.1.1 9.9.9.9' --searchdomain invalid \
  --cicustom user=local:snippets/klipper-ai-300.yaml
qm resize 300 scsi0 100G
cat > /etc/pve/firewall/300.fw <<'FIREWALL'
[OPTIONS]
enable: 1
policy_in: DROP
policy_out: DROP
macfilter: 1
ipfilter: 1

[IPSET ipfilter-net0]
10.40.0.30

[RULES]
OUT DROP -dest 10.0.0.0/8 -log nolog
OUT DROP -dest 100.64.0.0/10 -log nolog
OUT DROP -dest 169.254.0.0/16 -log nolog
OUT DROP -dest 172.16.0.0/12 -log nolog
OUT DROP -dest 192.168.0.0/16 -log nolog
OUT DROP -dest 224.0.0.0/4 -log nolog
OUT ACCEPT -dest 1.1.1.1 -p udp -dport 53 -log nolog
OUT ACCEPT -dest 1.1.1.1 -p tcp -dport 53 -log nolog
OUT ACCEPT -dest 9.9.9.9 -p udp -dport 53 -log nolog
OUT ACCEPT -dest 9.9.9.9 -p tcp -dport 53 -log nolog
OUT ACCEPT -p udp -dport 123 -log nolog
OUT ACCEPT -p tcp -dport 80 -log nolog
OUT ACCEPT -p tcp -dport 443 -log nolog
FIREWALL
pve-firewall compile >/dev/null
qm set 300 --protection 1
qm start 300
echo 'CREATED: VM 300. Baseline retained; guest bootstrap is asynchronous.'
REMOTE
