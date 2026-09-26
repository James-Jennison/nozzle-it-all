#!/bin/bash
# Deploy site/ to the nozzleitall.com origin (Webuzo VM, ssh alias website-vm-admin -> web.jamesjennison.net).
#   scripts/deploy_site.sh          dry run (shows what would change)
#   scripts/deploy_site.sh --go     back up the web root on the server, then sync
# Never touches mmf-auth/ (the MyMiniFactory OAuth relay). Never deletes remote files. Needs the owner's ssh alias/key.
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=website-vm-admin; ROOT=/home/jamesjen/nozzleitall.com
python3 scripts/build_site.py --check
FLAGS=(-a -v --exclude='mmf-auth/' --chmod=D750,F644 --chown=jamesjen:nobody -e ssh)
if [ "${1:-}" != "--go" ]; then rsync -n "${FLAGS[@]}" site/ "$HOST:$ROOT/"; echo "(dry run; pass --go to deploy)"; exit 0; fi
TS=$(date -u +%Y%m%dT%H%MZ)
ssh "$HOST" "mkdir -p /root/backups && tar czf /root/backups/nozzleitall-webroot-$TS.tgz -C /home/jamesjen nozzleitall.com && echo backup: /root/backups/nozzleitall-webroot-$TS.tgz"
rsync "${FLAGS[@]}" site/ "$HOST:$ROOT/"
for p in / /platforms/ /platforms/web/ /printers/ /docs/ /privacy/ /open-source/ /support/ /mmf-auth/; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://nozzleitall.com$p"); echo "$code $p"; [ "$code" = 200 ] || { echo "UNEXPECTED $code for $p" >&2; exit 1; }
done
for f in index.html relay.js; do curl -s "https://nozzleitall.com/mmf-auth/$f" | cmp - "site/mmf-auth/$f" && echo "mmf-auth/$f identical"; done
