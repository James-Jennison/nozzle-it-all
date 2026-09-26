#!/usr/bin/env python3
"""Flatten an OrcaSlicer system profile (resolve its "inherits" chain, child over parent) into one JSON file.

The app's JNI bridge loads profiles with plain ConfigBase::load() calls and has no inherits resolution, so every
bundled pack is flattened offline (see app/src/main/assets/slicer_profiles/PROVENANCE.md).

  flatten_orca_profile.py --profiles <orca>/resources/profiles --vendor Prusa \
      --machine "Prusa XL 5T 0.4 nozzle" --process "0.20mm Speed @Prusa XL 5T 0.4" \
      --filament "Generic PLA @Prusa XL 5T" --out app/src/main/assets/slicer_profiles/prusa_xl_5t
"""
import argparse, json, os, sys


def index(profiles_root, vendor):
    by_name = {}
    roots = [os.path.join(profiles_root, vendor)] + [os.path.join(profiles_root, d) for d in sorted(os.listdir(profiles_root)) if d != vendor]
    for root in roots:
        if not os.path.isdir(root): continue
        for sub in ("machine", "process", "filament"):
            d = os.path.join(root, sub)
            if not os.path.isdir(d): continue
            for f in os.listdir(d):
                if f.endswith(".json"):
                    try: data = json.load(open(os.path.join(d, f)))
                    except Exception: continue
                    by_name.setdefault((sub, data.get("name")), data)
    return by_name


def flatten(kind, name, by_name, seen=()):
    if name in seen: raise SystemExit(f"inherits cycle at {name}")
    data = by_name.get((kind, name))
    if data is None: raise SystemExit(f"{kind} profile not found: {name}")
    parent = data.get("inherits")
    merged = flatten(kind, parent, by_name, seen + (name,)) if parent else {}
    merged.update({k: v for k, v in data.items() if k != "inherits"})
    return merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", required=True); ap.add_argument("--vendor", required=True)
    ap.add_argument("--machine", required=True); ap.add_argument("--process", required=True); ap.add_argument("--filament", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    by_name = index(a.profiles, a.vendor)
    os.makedirs(a.out, exist_ok=True)
    for kind, name in (("machine", a.machine), ("process", a.process), ("filament", a.filament)):
        flat = flatten(kind, name, by_name)
        flat["name"] = name
        with open(os.path.join(a.out, f"{kind}.json"), "w") as f: json.dump(flat, f, indent=4); f.write("\n")
        print(f"{kind}: {name} -> {len(flat)} keys")


if __name__ == "__main__":
    sys.exit(main())
