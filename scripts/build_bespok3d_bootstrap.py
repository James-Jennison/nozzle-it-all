#!/usr/bin/env python3
"""Builds app/src/main/assets/bespok3d/bootstrap.zip from Bespok3d's own official GitHub releases.

Bespok3dBootstrapPackages.kt (ported from Helix, AGPL-3.0-or-later) verifies each .b3 archive
inside this bundle against Bespok3d's own OpenPGP publisher key at runtime, on the device, before
ever trusting its contents. This script performs the *same* verification at build time, before the
binary is committed to the repo, so a compromised or wrong download is caught here rather than
shipped. It does not build, sign, or otherwise originate any binary itself - it only downloads and
re-verifies release artifacts Bespok3d already built and signed with their own key.

Usage:
    python3 scripts/build_bespok3d_bootstrap.py [--daemon-tag TAG] [--jinni-tag TAG]

Requires `curl` and `gpg` on PATH. Run from the repo root (or anywhere - paths are absolute).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT = REPO_ROOT / "app/src/main/assets/bespok3d/bootstrap.zip"

# Same key Bespok3dBootstrapPackages.kt pins as OFFICIAL_PUBLIC_KEY / OFFICIAL_FINGERPRINT.
OFFICIAL_FINGERPRINT = "679939555819FB5F6423DC68C4388E76BFA9B4E0"
OFFICIAL_PUBLIC_KEY = """-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBGoe+wUBEADJjkI85zRmpx2XmaU2e7eb1OGR0Khw0z5dByvQ0odMovBhInK4
mmWR1d+DL2yLt8QNh421LGuBd1iWXSx6jTKPi8PcxBSxfhfJydJWIji58HFN/sTd
dyk+I20Ln9k0B0A8BpLnSzVUTEKYrqYiRSAJcPVkrA1myp3X4kUt/DyqERHE/HF+
bmwMsW0pgpdvs1umUOV7EdpADWorfWcWFOGKFJSGbd8K3hjFR9IPt6sPeKsUGU5U
01hdFp89a/DAX/Q2LGQP/v+WNUpNQtj6CMPRPc2sjNcyH16m9EsIugkWoimxsoSk
gKAoINq+gQtp/qckQiXoApXnB1ewQfWmz0C+zAoSL/qXd/QEpStZhgvlDX4eOeUl
LdOLleRnwqorNgz4Qr96C1uETJF2ew8iZm5v4nPOidP9eG0OOrYsiHjmiOubD3A9
V6GLGiaVuRNJ1dIew615bOmOhQY/8Sa32QoUeDYVDEL4pZxyk+fuxObvBGfvRFdG
wuuVvEXX0L+Ne7KSHSVUXQGGobjfrektB8OSOFpAM9iGAhtH/lCXq8OjogjzoetE
47JflKHZLmAaspl16WrsRk+GPxGwAf8ckAs7GxgaxbTECkeauG2Iqcmme1k+3kmK
NQBrQq5NMz4A+OMN0g/4BO/S8RkLtxC1cjDCZ72MNgzh4lt+to91Vr8R6wARAQAB
tFFCZXNwb2szZCBSZWdpc3RyeSBTaWduaW5nIEtleSAob2ZmaWNpYWwgbGlzdCBz
aWduaW5nIGtleSkgPHJlZ2lzdHJ5QGJlc3BvazNkLm9yZz6JAm0EEwEIAFcWIQRn
mTlVWBn7X2Qj3GjEOI52v6m04AUCah77BRsUgAAAAAAEAA5tYW51MiwyLjUrMS4x
MiwwLDMCGwMFCwkIBwICIgIGFQoJCAsCBBYCAwECHgcCF4AACgkQxDiOdr+ptOAq
7BAAlCoYtauXk8As3ajW2IJLUOYHxtal+h4UUaXiiNKwgtZBbnIZByfDZ68veDoP
SQ3PfKLKgypuJqGNRKCORiP/zw2Co7AqwHgsG9G5B48SsDIQlRX1nad5Acc5XyHN
GKqDu0mxQd9GVU96zhOknZoF4f2yrrHhrv1OYrbzHsp9ktyddfyO4izurs0zPh6B
6ln1AgbOwc+yMG3NjqpmjEgXn/5B+WCXU/9wwOC8TmOGdZHtdVgzExZEbEgRkqe+
Wzq8Or8at+CLn2BCyYyKJcRQVDNYubjpE0BsYw4t/n01PwDKlgk4Kc4JPmjAXgqh
7ZJDegBIb14+rhwptKBpr/bGHJxJQBqAPmeqIPjNYNSkXlVbToS8RRsy5/7wWm7E
UKQChOBY4CZ9+d6H7IEIkj6Cay0NRDNRGBJ8H1ePsA9P8xCU567F0iEXwKKmWPiL
lB1lLI5KScW7kfx9iHQ8NKGxhmiDbB7J/Zd+et5WZIKONit+xifU4YVOpELbhRYA
6G7i1pFOQhXLZG832pKMqHCPCpBqT5imrJ2NKYqCHyZ2aVi3gK6mpYWnzSh5Xcpv
HGkr0kOBhL1zF6g4Cn/wU26QI4mQ2eEOqBRhUTFBbBZ7fQTbFgA4AV9Gwi8L6tNB
At5hzMkILtyaJ1gDVIBv/Qmet5QtOB22Sq54rRL4W+igroM=
=DgD7
-----END PGP PUBLIC KEY BLOCK-----
"""

DAEMON_REPO = "Bespok3d/daemon"
DAEMON_NAME = "bespok3d-daemon"
JINNI_REPO = "Bespok3d/adapters"
JINNI_NAME = "bespok3d-jinni-snapmaker-u1"


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kwargs)


def latest_release_asset(repo: str, package_name: str, tag: str | None) -> tuple[str, str]:
    """Returns (tag, browser_download_url) for the .b3 asset of the given or latest release."""
    if tag is None:
        releases = json.loads(run(["gh", "api", f"repos/{repo}/releases"]).stdout)
        release = releases[0]
        tag = release["tag_name"]
    else:
        release = json.loads(run(["gh", "api", f"repos/{repo}/releases/tags/{tag}"]).stdout)
    asset = next(a for a in release["assets"] if a["name"].endswith(".b3"))
    return tag, asset["browser_download_url"]


def download(url: str, dest: Path) -> None:
    run(["curl", "-sL", "-o", str(dest), url])


def verify_and_read_version(b3_path: Path, expected_name: str, gnupg_home: Path) -> str:
    """Verifies the archive's manifest.json.sig against the official key and returns its version.

    Mirrors Bespok3dBootstrapPackages.verifyPackage's checks: manifest signature, publisher
    fingerprint, declared name, and every declared file's own sha256 - so a bad download or a
    tampered release is caught here, at build time, not just at runtime on a user's device.
    """
    with zipfile.ZipFile(b3_path) as archive:
        names = set(archive.namelist())
        for required in ("manifest.json", "manifest.json.sig"):
            if required not in names:
                raise SystemExit(f"{b3_path.name}: missing {required}")
        manifest_bytes = archive.read("manifest.json")
        signature_bytes = archive.read("manifest.json.sig")

        with tempfile.TemporaryDirectory() as tmp:
            manifest_file = Path(tmp) / "manifest.json"
            sig_file = Path(tmp) / "manifest.json.sig"
            manifest_file.write_bytes(manifest_bytes)
            sig_file.write_bytes(signature_bytes)
            result = subprocess.run(
                ["gpg", "--homedir", str(gnupg_home), "--batch", "--status-fd", "1",
                 "--verify", str(sig_file), str(manifest_file)],
                capture_output=True,
            )
            # NOTATION_DATA can carry raw non-UTF8 bytes; decode leniently since only the
            # VALIDSIG line (plain ASCII) is actually being checked below.
            stdout = result.stdout.decode("utf-8", errors="replace")
            # The VALIDSIG status line's second field is the signing key's full fingerprint -
            # gpg only emits it once the signature itself has checked out over the exact bytes.
            if f"VALIDSIG {OFFICIAL_FINGERPRINT}" not in stdout:
                raise SystemExit(
                    f"{b3_path.name}: manifest signature did not verify against the "
                    f"official Bespok3d key.\n{stdout}\n{result.stderr.decode(errors='replace')}"
                )

        manifest = json.loads(manifest_bytes)
        if manifest["name"] != expected_name:
            raise SystemExit(f"{b3_path.name}: manifest name {manifest['name']!r} != {expected_name!r}")
        if manifest.get("publisher", "").upper() != OFFICIAL_FINGERPRINT:
            raise SystemExit(f"{b3_path.name}: manifest publisher is not the pinned official key")

        for entry in manifest["files"]:
            actual = hashlib.sha256(archive.read(entry["path"])).hexdigest()
            if actual != entry["sha256"].lower():
                raise SystemExit(f"{b3_path.name}: {entry['path']} failed its declared sha256")

        return manifest["version"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--daemon-tag", default=None, help="e.g. bespok3d-daemon-v0.14.0 (default: latest)")
    parser.add_argument("--jinni-tag", default=None, help="e.g. bespok3d-jinni-snapmaker-u1-v0.1.11 (default: latest)")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp_str:
        tmp = Path(tmp_str)
        gnupg_home = tmp / "gnupg"
        gnupg_home.mkdir(mode=0o700)
        key_file = tmp / "official-key.asc"
        key_file.write_text(OFFICIAL_PUBLIC_KEY)
        run(["gpg", "--homedir", str(gnupg_home), "--batch", "--import", str(key_file)])

        daemon_tag, daemon_url = latest_release_asset(DAEMON_REPO, DAEMON_NAME, args.daemon_tag)
        jinni_tag, jinni_url = latest_release_asset(JINNI_REPO, JINNI_NAME, args.jinni_tag)

        daemon_path = tmp / Path(daemon_url).name
        jinni_path = tmp / Path(jinni_url).name
        print(f"Downloading {daemon_tag}: {daemon_url}")
        download(daemon_url, daemon_path)
        print(f"Downloading {jinni_tag}: {jinni_url}")
        download(jinni_url, jinni_path)

        daemon_version = verify_and_read_version(daemon_path, DAEMON_NAME, gnupg_home)
        jinni_version = verify_and_read_version(jinni_path, JINNI_NAME, gnupg_home)
        print(f"Verified {DAEMON_NAME} {daemon_version}, {JINNI_NAME} {jinni_version} "
              "against the official Bespok3d signing key.")

        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED) as out:
            out.write(daemon_path, daemon_path.name)
            out.write(jinni_path, jinni_path.name)
        print(f"Wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
