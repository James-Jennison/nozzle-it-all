#!/usr/bin/env bash
# Sets the password for the Test Grid review page (nozzleitall.com/testgrid/review/). Run it in your own terminal: the
# password is read here without echo and sent over SSH on stdin; only its bcrypt hash is stored on the server
# (/home/jamesjen/testgrid-inbox/review-password). It never appears in a command line, a log or this repository.
set -euo pipefail
read -r -s -p "New review password: " p1; echo
read -r -s -p "Again: " p2; echo
[ "$p1" = "$p2" ] || { echo "They don't match." >&2; exit 1; }
[ ${#p1} -ge 12 ] || { echo "Use at least 12 characters." >&2; exit 1; }
printf '%s' "$p1" | ssh website-vm-admin "umask 077; /usr/local/apps/php84/bin/php -r 'echo password_hash(stream_get_contents(STDIN), PASSWORD_BCRYPT);' > /home/jamesjen/testgrid-inbox/review-password.new && chown jamesjen:jamesjen /home/jamesjen/testgrid-inbox/review-password.new && mv /home/jamesjen/testgrid-inbox/review-password.new /home/jamesjen/testgrid-inbox/review-password"
echo "Review password set. Sign in at https://nozzleitall.com/testgrid/review/"
