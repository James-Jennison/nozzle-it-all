# First release runbook (owner steps marked OWNER)

The pipeline is ready: pushing a tag `vX.Y.Z` runs the `release` job, which builds the minified, signed APK **and the Play bundle (AAB)**, writes the SBOM and checksums, attests provenance, and creates a **draft** GitHub Release (nothing is public until you press Publish). It has never run: the first tag is the first real test.

## 1. Signing key (OWNER; I do not create, hold or see it)
1. Generate one upload key on a machine you trust:
   `keytool -genkeypair -v -keystore nozzle-upload.jks -alias nozzle -keyalg RSA -keysize 4096 -validity 10950`
2. Back it up in two places you control (for example a password manager attachment and an encrypted drive). Write down the alias and both passwords.
3. Add four repository secrets: `NOZZLE_KEYSTORE_B64` (`base64 -w0 nozzle-upload.jks`), `NOZZLE_KEYSTORE_PASSWORD`, `NOZZLE_KEY_ALIAS`, `NOZZLE_KEY_PASSWORD`.
4. Enrol in **Play App Signing** when you create the app (recommended): Google holds the real signing key, this key is only the *upload* key, and a lost upload key can be reset through Play support. If you distribute the APK directly, that same key signs the GitHub APK, so users of the APK and of Play get differently signed builds (they cannot update across each other); that is normal.

## 2. Play Console (OWNER)
- Developer account and identity verification. **CHECK** the current testing rule for new personal accounts (a closed test with a minimum number of testers for a minimum number of days was required before production at last check).
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
