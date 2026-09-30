#!/usr/bin/env bash
# Deploys the Test Grid inbox (infra/testgrid-inbox) to nozzleitall.com/testgrid/ and prepares the private store
# /home/jamesjen/testgrid-inbox (bundles/, tmp/, blocked.txt), outside the web root. Dry run by default; --go deploys.
# Block an abused tester ID: scripts/deploy_testgrid_inbox.sh --block t-...
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=website-vm-admin; WEB=/home/jamesjen/nozzleitall.com/testgrid; INBOX=/home/jamesjen/testgrid-inbox
if [ "${1:-}" = "--block" ]; then
  id="${2:?the tester ID to block, t-...}"
  case "$id" in t-*) ;; *) echo "A tester ID starts with t-" >&2; exit 1;; esac
  case "$id" in *[!A-Za-z0-9_-]*) echo "Not a tester ID" >&2; exit 1;; esac
  ssh "$HOST" "umask 077; printf '%s\n' '$id' >> $INBOX/blocked.txt; chown jamesjen:jamesjen $INBOX/blocked.txt"
  echo "Blocked $id"; exit 0
fi
FLAGS=(-a -v --chmod=D750,F644 --chown=jamesjen:nobody -e ssh)
if [ "${1:-}" != "--go" ]; then rsync -n "${FLAGS[@]}" infra/testgrid-inbox/ "$HOST:$WEB/"; echo "(dry run; pass --go to deploy)"; exit 0; fi
ssh "$HOST" "install -d -m 750 -o jamesjen -g nobody $WEB && install -d -m 750 -o jamesjen -g jamesjen $INBOX $INBOX/bundles $INBOX/tmp && { [ -f $INBOX/blocked.txt ] || install -m 600 -o jamesjen -g jamesjen /dev/null $INBOX/blocked.txt; }"
rsync "${FLAGS[@]}" infra/testgrid-inbox/ "$HOST:$WEB/"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST https://nozzleitall.com/testgrid/submit.php); echo "POST without a tester ID: $code (expect 400)"
code=$(curl -s -o /dev/null -w '%{http_code}' https://nozzleitall.com/testgrid/submit.php); echo "GET: $code (expect 405)"
code=$(curl -s -o /dev/null -w '%{http_code}' https://nozzleitall.com/testgrid/.user.ini); echo ".user.ini: $code (expect 403)"
