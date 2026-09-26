# The nozzleitall.com website

Source: `site-src/` (page fragments, `templates/base.html`, `assets/`, and `release.json`). Output: `site/`, built by `python3 scripts/build_site.py`. CI runs `--check` and fails if `site/` is stale. There is no JavaScript on the site and no third-party asset.

## Rules the build enforces
* **`site-src/release.json` is the only source of what the site may claim.** A platform in state `released` needs a `url`; a platform that is not released cannot have one. Only released platforms can render a download or launch button. Android is `active` (not on Google Play yet), Linux desktop, Web and Windows desktop are `planned` in that order, iOS and macOS are `deferred`. To publish a release, change its state and url in that file.
* `site/mmf-auth/` (the MyMiniFactory OAuth relay) is never written by the build. Do not edit or move it.
* The brand violet `#8B5CF6` is a fill only. Text and buttons use `--accent` (`#A78BFA` dark / `#6D28D9` light).
* Internal links must resolve and no `{{placeholder}}` may be left over.
Store screenshots and site imagery must be real captures of the app, never mockups.

## Hosting and deploying
`nozzleitall.com` is served from the UpCloud VM `web-vm-admin` (Webuzo), behind Cloudflare (DNS, proxy, TLS). `app.nozzleitall.com` is a separate future application and is not part of this site.
* Deploy = copy the contents of `site/` to the web root. Do not delete or overwrite `mmf-auth/` with anything but the committed files. (The owner deploys; no credentials live in this repo.)
* Cloudflare: cache the static pages normally, but do NOT cache `/mmf-auth/*` aggressively, and register no service worker on this origin (a service worker is only for the future app origin).
* Recommended response headers (set in Cloudflare Transform Rules or the web server): `Strict-Transport-Security: max-age=31536000`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: interest-cohort=()`, and `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; font-src 'self'; script-src 'self'; frame-ancestors 'none'` (the `/mmf-auth` relay uses one external script file, `relay.js`, from the same origin).
* Pages are clean URLs (`/platforms/`), so the server must serve `index.html` for directories and `404.html` for missing files.

## Still to write
Engine Service self-hosting docs (when it exists), Security page (currently a section of Support), changelog (GitHub Releases for now).
Open items for the owner: enable GitHub private vulnerability reporting; register a separate MyMiniFactory redirect for the web app origin before it launches; have the privacy policy reviewed before a store release.
