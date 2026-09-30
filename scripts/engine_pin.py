#!/usr/bin/env python3
"""Pin and verify the source archives of the Android engine's dependency prefix (Phase 9g).

  engine_pin.py update [--deps-src PATH]   rewrite engine/fork/android/DEPENDENCIES.json from the archives
  engine_pin.py verify [--deps-src PATH]   fail unless the archives match engine/fork/android/DEPENDENCIES.json

The engine source itself is pinned by commit (engine/fork/ENGINE_PIN.json) and fetched by that commit, so there is no
local patch to check. Default archives: orcaslicer-android-engine's deps/src ($ANDROID_DEPS_SRC overrides).
"""
import argparse, hashlib, json, os, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIN = os.path.join(REPO, "engine", "fork", "android", "DEPENDENCIES.json")
ARCHIVES = (".tar.gz", ".tar.xz", ".tar.bz2", ".zip")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("mode", choices=["update", "verify"])
    ap.add_argument("--deps-src", default=os.environ.get("ANDROID_DEPS_SRC", "/mnt/faststorage/orcaslicer-android-engine/deps/src"))
    a = ap.parse_args()
    if not os.path.isdir(a.deps_src):
        print(f"ENGINE PIN: {a.deps_src} is missing, so the dependency archives cannot be checked", file=sys.stderr); return 1
    current = {n: sha256_file(os.path.join(a.deps_src, n)) for n in sorted(os.listdir(a.deps_src)) if n.endswith(ARCHIVES)}
    pin = json.load(open(PIN))
    if a.mode == "update":
        pin["dependencies"] = [{"file": n, "sha256": d} for n, d in current.items()]
        with open(PIN, "w") as f: json.dump(pin, f, indent=2); f.write("\n")
        print("pinned", len(current), "dependency archives"); return 0
    pinned = {d["file"]: d["sha256"] for d in pin["dependencies"]}
    problems = [f"missing dependency archive {n}" for n in pinned if n not in current]
    problems += [f"dependency archive {n} changed" for n in pinned if n in current and current[n] != pinned[n]]
    problems += [f"unpinned dependency archive {n}" for n in current.keys() - pinned.keys()]
    for p in problems: print("ENGINE PIN MISMATCH:", p, file=sys.stderr)
    if not problems: print("dependency archives match the pin:", len(pinned))
    return 1 if problems else 0


sys.exit(main())
