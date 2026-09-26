# Website deploy checklist (nozzleitall.com)

Deploying is done by the owner. Nothing in this repository holds server credentials, and the site build never writes to `site/mmf-auth/`.

## Before you deploy
- [ ] **Read every page in the preview** (`.claude/launch.json` → `website-preview`, port 8766). Confirm the copy says only what is true today: Android is *active* (not on Google Play); Linux desktop, Web and Windows desktop are *planned*; iOS and macOS are *deferred*.
- [ ] **Have the privacy policy reviewed** (it is a draft written from the code). Re-read it if anything about data handling changes.
- [ ] `python3 scripts/build_site.py --check` passes (CI runs the same).
- [ ] `support@nozzleitall.com` receives mail (send a test).
- [ ] GitHub links resolve and the repository is public (`github.com/James-Jennison/nozzle-it-all`); source-offer and licence links work.
- [ ] Decide the Lovart licence/trademark checks are done (see `brand/README.md`) before promoting the brand publicly.

## Deploy
1. Back up the current web root on `web-vm-admin`, especially `mmf-auth/`.
2. Copy the *contents* of `site/` into the web root. **Do not delete the server's existing `mmf-auth/` before confirming the new copy is byte-identical** (`git diff --stat -- site/mmf-auth` must be empty).
3. Confirm directory URLs serve `index.html` and missing paths serve `404.html`.
4. In Cloudflare: purge the cache for the changed URLs, keep `/mmf-auth/*` un-cached or short-cached, and add the response headers listed in `docs/SITE.md`. Do not register a service worker on this origin.

## After
- [ ] Load `/`, `/platforms/`, `/platforms/web/`, `/printers/`, `/docs/`, `/privacy/`, `/open-source/`, `/support/`, and a bad URL (expect the 404 page), over HTTPS.
- [ ] **Test MyMiniFactory sign-in end to end from the Android app** (the `/mmf-auth` relay must still hand the token back to the app).
- [ ] Check the pages on a real phone at 360 px width and with the system font size at maximum.
- [ ] Confirm the response headers (securityheaders.com or `curl -I`).

## Rollback
Restore the backed-up web root, purge the Cloudflare cache, and re-test `/mmf-auth`.
