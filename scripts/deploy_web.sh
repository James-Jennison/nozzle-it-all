#!/usr/bin/env bash
# Deploy the Web App (web/dist) to app.nozzleitall.com: Webuzo subdomain of jamesjen on website-vm-admin,
# docroot /home/jamesjen/app.nozzleitall.com. Dry run by default; --go deploys (after a backup). Never deletes remote files.
# Needs the slicing engine in web/public/engine (engine/wasm/scripts/build_engine_fork.sh INSTALL=1).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST=website-vm-admin; DOCROOT=/home/jamesjen/app.nozzleitall.com
[ -f "$ROOT/web/public/engine/nozzle-engine.wasm" ] || { echo "no engine in web/public/engine" >&2; exit 1; }
(cd "$ROOT/web" && npm run build >/dev/null)
FLAGS=(-a -v --chmod=D750,F644 --chown=jamesjen:nobody -e ssh)
if [ "${1:-}" != "--go" ]; then rsync -n "${FLAGS[@]}" "$ROOT/web/dist/" "$HOST:$DOCROOT/"; echo "(dry run; pass --go to deploy)"; exit 0; fi
TS=$(date -u +%Y%m%dT%H%M%SZ)
ssh "$HOST" "mkdir -p /root/backups && tar czf /root/backups/app-nozzleitall-$TS.tgz -C /home/jamesjen app.nozzleitall.com && echo backup: /root/backups/app-nozzleitall-$TS.tgz"
rsync "${FLAGS[@]}" "$ROOT/web/dist/" "$HOST:$DOCROOT/" | tail -3
for p in / /engine/nozzle-engine.wasm /profiles/index.json /sw.js; do
  printf '%s %s\n' "$(curl -s -o /dev/null -w '%{http_code}' "https://app.nozzleitall.com$p")" "$p"
done
curl -sI https://app.nozzleitall.com/ | grep -iE '^cross-origin-(opener|embedder)-policy'
