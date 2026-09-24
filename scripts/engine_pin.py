#!/usr/bin/env python3
"""Pin and verify the native slicing engine's sources (Phase 9g).

  engine_pin.py update [--engine-root PATH]   rewrite engine/ENGINE_PIN.json from a checkout
  engine_pin.py verify [--engine-root PATH]   fail unless the checkout matches engine/ENGINE_PIN.json

Pinned: the upstream OrcaSlicer commit, the exact patch applied on top (kept in engine/), and the SHA-256 of every
dependency source archive in deps/src. Default engine root: $ORCASLICER_ENGINE_ROOT or /mnt/faststorage/orcaslicer-android-engine.
"""
import argparse, hashlib, json, os, subprocess, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIN = os.path.join(REPO, "engine", "ENGINE_PIN.json")
PATCH = os.path.join(REPO, "engine", "android-headless-engine.patch")
ARCHIVES = (".tar.gz", ".tar.xz", ".tar.bz2", ".zip")


def sha256_bytes(data): return hashlib.sha256(data).hexdigest()
def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()


def git(root, *args): return subprocess.run(["git", "-C", os.path.join(root, "orcaslicer"), *args], check=True, capture_output=True).stdout


def snapshot(root):
    src = os.path.join(root, "deps", "src")
    # None (not []) when the archives are not on this machine, so "absent" is never mistaken for "no dependencies".
    deps = [{"file": n, "sha256": sha256_file(os.path.join(src, n))} for n in sorted(os.listdir(src)) if n.endswith(ARCHIVES)] if os.path.isdir(src) else None
    return {
        "upstream": {"repo": "https://github.com/SoftFever/OrcaSlicer.git", "commit": git(root, "rev-parse", "HEAD").decode().strip()},
        "patch": {"file": "engine/android-headless-engine.patch", "sha256": sha256_bytes(git(root, "diff"))},
        "dependencies": deps,
    }


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("mode", choices=["update", "verify"])
    ap.add_argument("--engine-root", default=os.environ.get("ORCASLICER_ENGINE_ROOT", "/mnt/faststorage/orcaslicer-android-engine"))
    ap.add_argument("--allow-missing-deps", action="store_true",
                    help="verify only: when deps/src is absent (a runner that has prebuilt deps but not their source archives), check the upstream commit and patch and warn that the archives were not checked")
    a = ap.parse_args()
    live = snapshot(a.engine_root)
    if live["dependencies"] is None and (a.mode == "update" or not a.allow_missing_deps):
        print(f"ENGINE PIN: {a.engine_root}/deps/src is missing, so the dependency archives cannot be checked (pass --allow-missing-deps to verify only the commit and patch)", file=sys.stderr); return 1
    if a.mode == "update":
        import shutil
        with open(PATCH, "wb") as f: f.write(git(a.engine_root, "diff"))
        with open(PIN, "w") as f: json.dump(live, f, indent=2); f.write("\n")
        print("pinned", live["upstream"]["commit"], "patch", live["patch"]["sha256"][:12], len(live["dependencies"]), "dependency archives"); return 0
    pin = json.load(open(PIN))
    problems = []
    if live["upstream"]["commit"] != pin["upstream"]["commit"]: problems.append(f"upstream commit {live['upstream']['commit']} != pinned {pin['upstream']['commit']}")
    if live["patch"]["sha256"] != pin["patch"]["sha256"]: problems.append("engine checkout's local changes differ from the pinned patch")
    if sha256_file(PATCH) != pin["patch"]["sha256"]: problems.append("engine/android-headless-engine.patch does not match ENGINE_PIN.json")
    if live["dependencies"] is None:
        print("WARNING: deps/src not present; dependency archives were NOT verified (commit and patch were)", file=sys.stderr)
    else:
        pinned = {d["file"]: d["sha256"] for d in pin["dependencies"]}; current = {d["file"]: d["sha256"] for d in live["dependencies"]}
        for name, digest in pinned.items():
            if name not in current: problems.append(f"missing dependency archive {name}")
            elif current[name] != digest: problems.append(f"dependency archive {name} changed")
        for name in current.keys() - pinned.keys(): problems.append(f"unpinned dependency archive {name}")
    for p in problems: print("ENGINE PIN MISMATCH:", p, file=sys.stderr)
    if not problems: print("engine matches pin", pin["upstream"]["commit"][:12])
    return 1 if problems else 0


sys.exit(main())
