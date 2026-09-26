# First release runbook (owner steps marked OWNER)

The pipeline is ready: pushing a tag `vX.Y.Z` runs the `release` job, which builds the minified, signed APK **and the Play bundle (AAB)**, writes the SBOM and checksums, attests provenance, and creates a **draft** GitHub Release (nothing is public until you press Publish). It has never run: the first tag is the first real test.

## 1. Signing key (DONE 2026-09-25)
**Status:** the upload key was generated on 2026-09-25 (RSA 4096, PKCS12, alias `nozzle`, valid to 2056-09-17, `CN=Nozzle It All, O=James Jennison`) and stored at `/run/media/jjennison/DATA/Nozzle It All/nozzle-upload.jks` with its passwords in `nozzle-upload-credentials.txt` next to it (both mode 0600, never committed, never printed). The four GitHub secrets below are set. Certificate SHA-256 fingerprint (public): `D3:9C:C8:7E:BD:D7:79:13:99:EF:9B:E5:55:12:2A:7A:8D:09:E8:D4:4F:2A:00:E8:EA:AD:06:12:DF:6F:BC:85`. Keystore file SHA-256: `39455270a1390eecbb99fd751eefc16aba6362a606c68df8380d56c7f9219a4f`.
**GitHub secrets cannot be read back, so that folder is the only source. Make a second copy elsewhere (password manager plus another drive) and move the passwords into the password manager.**

The steps used (kept for a rebuild or rotation):
1. Generate one upload key on a machine you trust:
   `keytool -genkeypair -v -keystore nozzle-upload.jks -alias nozzle -keyalg RSA -keysize 4096 -validity 10950`
2. Back it up in two places you control (for example a password manager attachment and an encrypted drive). Write down the alias and both passwords.
3. Add four repository secrets: `NOZZLE_KEYSTORE_B64` (`base64 -w0 nozzle-upload.jks`), `NOZZLE_KEYSTORE_PASSWORD`, `NOZZLE_KEY_ALIAS`, `NOZZLE_KEY_PASSWORD`.
4. Enrol in **Play App Signing** when you create the app (recommended): Google holds the real signing key, this key is only the *upload* key, and a lost upload key can be reset through Play support. If you distribute the APK directly, that same key signs the GitHub APK, so users of the APK and of Play get differently signed builds (they cannot update across each other); that is normal.

## 2. Play Console (OWNER)
**Status 2026-09-25:** the app record exists: "Nozzle It All", package `com.nozzleitall.app`, app ID `4975542357521366304`, free, en-US, created by the owner (who ticked the Developer Program Policies and US export law declarations). Play's dashboard says: finish setup, **run a closed test, then apply for production access** (the closed-test requirement does apply to this app). Console: https://play.google.com/console/u/0/developers/7054634954390925321/app/4975542357521366304/app-dashboard
- Developer account: `owner@example.invalid` (owner). Complete identity verification if Play still asks. **CHECK** the current testing rule for new personal accounts (a closed test with a minimum number of testers for a minimum number of days was required before production at last check).
- Create the app, upload the AAB to **internal testing** first, fill in the store listing from `docs/STORE_LISTING.md`, the Data safety form, the content rating and the foreground-service declaration.

## 3. Cut a release
1. Make sure `main` is green in CI and the website preview is right. Decide the version, for example `0.1.0`.
2. `git tag v0.1.0 && git push github v0.1.0` (version name comes from the tag, version code from the commit count).
3. When the job finishes, review the draft release on GitHub (notes, files, `SHA256SUMS`), then Publish. Upload the AAB from the release to Play.

## 4. After
- Update `site-src/release.json` (Android state and Play URL) and rebuild the site, so the website only claims what exists (the build refuses a link before release).
- Install the published APK on the Razr over the previous build (never `adb uninstall`: it deletes saved printers) and run the self-check.

## Not covered
F-Droid needs a reproducible build from source; the native engine build (a prebuilt dependency set, 57 MB) makes that a separate project.
