#!/usr/bin/env bash
# Pulls bundles testers sent from Test Mode (nozzleitall.com inbox) to GTHost and verifies each with the Test Grid CLI.
# Filing and accepting stay the maintainer's steps (docs/testgrid/MAINTAINER_GUIDE.md). Read-only on the web server.
set -euo pipefail
cd "$(dirname "$0")/.."
WEB=website-vm-admin; INBOX=/home/jamesjen/testgrid-inbox/bundles
GT=jjennison@100.87.130.114; DEST=testgrid-evidence/incoming
LOCAL=$(mktemp -d); trap 'rm -rf "$LOCAL"' EXIT
rsync -a -e ssh "$WEB:$INBOX/" "$LOCAL/"
ls "$LOCAL"/*.zip >/dev/null 2>&1 || { echo "No bundles in the inbox."; exit 0; }
ssh "$GT" "mkdir -p ~/$DEST"
rsync -a --ignore-existing "$LOCAL/" "$GT:$DEST/"
ssh "$GT" "cd ~/testgrid-build/src && export ANDROID_HOME=/opt/android-sdk && for z in ~/$DEST/*.zip; do ./gradlew --no-daemon -q :test-grid:cli --args=\"verify \$z\" 2>&1 | tail -1; done"
for m in "$LOCAL"/*.json; do python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(m["receivedAt"], m["tester"], m["suite"], m["printer"], m["sha256"][:12])' "$m"; done
