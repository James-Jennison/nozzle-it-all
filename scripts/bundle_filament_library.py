#!/usr/bin/env python3
"""Bundle a printer's filament profiles (every filament its slicer marks compatible with it), flattened, so each of
Nozzle's material slots can print with its own filament profile the way Orca-family slicers pick a filament preset per
slot. Source for the Snapmaker U1: Snapmaker Orca's own profiles at the engine pin (engine/profiles/SOURCES.json),
which match its engine.

  bundle_filament_library.py --profiles <Snapmaker Orca>/resources/profiles --vendor Snapmaker \
      --printer "Snapmaker U1 (0.4 nozzle)" --out engine/profiles/filaments/snapmaker_u1

Writes one flattened <id>.json per filament plus index.json: id, name, vendor, type, family (Snapmaker's filament
match name: the name without its "<d> nozzle" suffix, as GetFilamentMatchName strips it).
"""
import argparse, json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from flatten_orca_profile import index, flatten  # noqa: E402


def match_name(name):
    return re.sub(r"\s+\d+(\.\d+)?\s+nozzle$", "", name).strip()


def slug(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", required=True); ap.add_argument("--vendor", required=True)
    ap.add_argument("--printer", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    by_name = index(a.profiles, a.vendor)
    os.makedirs(a.out, exist_ok=True)
    for f in os.listdir(a.out):
        if f.endswith(".json"): os.remove(os.path.join(a.out, f))
    entries = []
    for (kind, name), data in sorted(by_name.items(), key=lambda kv: str(kv[0][1])):
        if kind != "filament" or not name or str(data.get("instantiation")).lower() != "true": continue
        try: flat = flatten("filament", name, by_name)
        except SystemExit: continue  # a profile elsewhere in the tree with a broken parent; not ours to fix
        if a.printer not in (flat.get("compatible_printers") or []): continue
        flat["name"] = name
        fid = slug(name)
        with open(os.path.join(a.out, fid + ".json"), "w") as out: json.dump(flat, out, indent=1, sort_keys=True); out.write("\n")
        first = lambda k: (flat.get(k) or [""])[0] if isinstance(flat.get(k), list) else flat.get(k, "")
        entries.append({"id": fid, "name": name, "vendor": first("filament_vendor"), "type": first("filament_type"), "family": match_name(name)})
    with open(os.path.join(a.out, "index.json"), "w") as out:
        json.dump({"printer": a.printer, "source": "Snapmaker Orca resources/profiles (AGPL-3.0), see engine/profiles/SOURCES.json",
                   "filaments": entries}, out, indent=1); out.write("\n")
    print(f"{len(entries)} filament profiles for {a.printer} -> {a.out}")


if __name__ == "__main__":
    sys.exit(main())
