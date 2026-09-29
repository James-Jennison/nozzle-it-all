#!/usr/bin/env bash
# Deploys the Test Grid inbox (infra/testgrid-inbox) to nozzleitall.com/testgrid/ and prepares the private store
# /home/jamesjen/testgrid-inbox (bundles/, tmp/, codes.txt), outside the web root. Dry run by default; --go deploys.
# New tester code: scripts/deploy_testgrid_inbox.sh --add-code "<label>" prints a fresh code once.
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=website-vm-admin; WEB=/home/jamesjen/nozzleitall.com/testgrid; INBOX=/home/jamesjen/testgrid-inbox
if [ "${1:-}" = "--add-code" ]; then
  label="${2:?label, for example the tester first name}"
  case "$label" in *[!A-Za-z0-9._\ -]*) echo "Use letters, digits, space, . _ - in the label" >&2; exit 1;; esac
  code=$(python3 -c 'import secrets; print("ng-" + secrets.token_urlsafe(12))')
  ssh "$HOST" "umask 077; printf '%s %s\n' '$code' '$label' >> $INBOX/codes.txt; chown jamesjen:jamesjen $INBOX/codes.txt"
  echo "Tester code for $label: $code"; exit 0
fi
FLAGS=(-a -v --chmod=D750,F644 --chown=jamesjen:nobody -e ssh)
if [ "${1:-}" != "--go" ]; then rsync -n "${FLAGS[@]}" infra/testgrid-inbox/ "$HOST:$WEB/"; echo "(dry run; pass --go to deploy)"; exit 0; fi
ssh "$HOST" "install -d -m 750 -o jamesjen -g nobody $WEB && install -d -m 750 -o jamesjen -g jamesjen $INBOX $INBOX/bundles $INBOX/tmp && { [ -f $INBOX/codes.txt ] || install -m 600 -o jamesjen -g jamesjen /dev/null $INBOX/codes.txt; }"
rsync "${FLAGS[@]}" infra/testgrid-inbox/ "$HOST:$WEB/"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST https://nozzleitall.com/testgrid/submit.php); echo "POST without a code: $code (expect 403)"
code=$(curl -s -o /dev/null -w '%{http_code}' https://nozzleitall.com/testgrid/submit.php); echo "GET: $code (expect 405)"
code=$(curl -s -o /dev/null -w '%{http_code}' https://nozzleitall.com/testgrid/.user.ini); echo ".user.ini: $code (expect 403)"
