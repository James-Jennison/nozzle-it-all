# Website deploy checklist (nozzleitall.com)

Deploying is done with `scripts/deploy_site.sh` (Claude, authorised by the owner 2026-09-26; first deploy done). Nothing in this repository holds server credentials, and the site build never writes to `site/mmf-auth/`.

## Before you deploy
- [ ] **Read every page in the preview** (`.claude/launch.json` → `website-preview`, port 8766). Confirm the copy says only what is true today: Android is *active* (not on Google Play); Linux desktop, Web and Windows desktop are *planned*; iOS and macOS are *deferred*.
- [ ] **Have the privacy policy reviewed** (it is a draft written from the code). Re-read it if anything about data handling changes.
- [ ] `python3 scripts/build_site.py --check` passes (CI runs the same).
- [x] `support@nozzleitall.com` receives mail. Verified 2026-09-26 with `exim -bt` on mail-vm-admin (mail.jamesjennison.net): it is forwarded to James@jamesjennison.net; `postmaster@` and any unknown address at the domain go to jamesjen@jamesjennison.net. The forwarder lives in Webuzo's store, not /etc/valiases. **Sending as** @nozzleitall.com is blocked (SPF `-all`, DMARC `p=reject`, no DKIM): reply from james@jamesjennison.net, or add SPF and DKIM for the domain in Cloudflare.
- [ ] GitHub links resolve and the repository is public (`github.com/James-Jennison/nozzle-it-all`); source-offer and licence links work.

## Deploy
1. Back up the current web root on `web-vm-admin`, especially `mmf-auth/`.
2. Copy the *contents* of `site/` into the web root. **Do not delete the server's existing `mmf-auth/` before confirming the new copy is byte-identical** (`git diff --stat -- site/mmf-auth` must be empty).
3. Confirm directory URLs serve `index.html` and missing paths serve `404.html`.
4. In Cloudflare: purge the cache for the changed URLs, keep `/mmf-auth/*` un-cached or short-cached, and add the response headers listed in `docs/SITE.md`. Do not register a service worker on this origin.

## After
- [ ] Load `/`, `/platforms/`, `/platforms/web/`, `/printers/`, `/docs/`, `/privacy/`, `/open-source/`, `/support/`, and a bad URL (expect the 404 page), over HTTPS.
- [ ] **Test MyMiniFactory sign-in end to end from the Android app** (the `/mmf-auth` relay must still hand the token back to the app).
- [ ] **Check which cookies Cloudflare actually sets** (browser dev tools, Application, Cookies). The privacy policy says none are set by our pages and that Cloudflare may set a strictly necessary security cookie. Adjust the wording if it sets more.
- [ ] Check the pages on a real phone at 360 px width and with the system font size at maximum.
- [ ] Confirm the response headers (securityheaders.com or `curl -I`).

## Rollback
Restore the backed-up web root, purge the Cloudflare cache, and re-test `/mmf-auth`.

## Sending mail as support@nozzleitall.com (2026-09-26)

The mail server (mail-vm-admin, 209.94.63.87) already DKIM-signs every outgoing message with a key named after the From
domain (selector `upcloud2026`, `/var/webuzo-data/mail/dkim/private/<domain>`); a key for nozzleitall.com exists. Only DNS
(Cloudflare) is missing. Add these three records to nozzleitall.com, then send a test and check `dkim=pass spf=pass dmarc=pass`:

| Type | Name | Value |
|---|---|---|
| TXT | `upcloud2026._domainkey` | `v=DKIM1; k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtfOH7/Jj7rha49gYhZAscRHmFSECHuiAY37x5hYmb1jm5PHvkp67Y44fmFrRgtE9XQzC/+VFAWU9ySPNMmM6e/DCpjGrkkr2qPoW3ay5kB4lpVTrvVC3yTbJ4YZELMBGdRcjbnA+snYx2/IptysIpdDIQl/hloX84IuLBa/hT8SYantlvnstWZ4+tMVAWOPPcyWk4DvJgjZtmjgIRdo1sSNptqql0uz8cqj8XrYPlKLP/j/wTcWDFLTB9x2E38mII05ektNm83ja7e8j1hFNJzjVwql6dnNZpG4w/YgEJ2iEWvyupb8a7+BHZgOTTmhccdfg32DbH53yXNWPoI2VBQIDAQAB` |
| TXT | `@` (replaces the current `v=spf1 -all`) | `v=spf1 ip4:209.94.63.87 -all` |
| TXT | `_dmarc` (update) | `v=DMARC1; p=reject; sp=reject; adkim=s; aspf=s; rua=mailto:support@nozzleitall.com` |

The public key above is safe to publish; the private key never leaves the server. DMARC alignment is strict, and DKIM
(d=nozzleitall.com) is what satisfies it, because the envelope sender is on jamesjennison.net.
