#!/usr/bin/env python3
"""Indexes the bundled printer profiles (app/src/main/assets/slicer_profiles/) for Desktop and Web.

A printer *profile* is what slicing needs: machine, process and filament settings. It is independent of any printer
*connection* (address, credentials, live state), so every profile can be sliced and exported with no printer attached.

Writes app/src/main/assets/slicer_profiles/index.json:
  {"version": 1, "source": {...provenance...}, "profiles": [{id, vendor, model, name, bed:[w,d], height, tools,
   outputs:[...], family}]}

  python3 scripts/build_profile_index.py          write
  python3 scripts/build_profile_index.py --check  exit 1 if the committed index is stale
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "app/src/main/assets/slicer_profiles"
OUT = DIR / "index.json"


def first(v, default=None):
    if isinstance(v, list):
        return v[0] if v else default
    return v if v is not None else default


def family_for(vendor: str, pid: str, flavor: str = "") -> str:
    """Which live-connection family a profile's printer usually belongs to. A hint for the UI only: slicing never needs it."""
    v = vendor.lower()
    if pid == "snapmaker_u1":
        return "paxx-u1"
    if "bambu" in v:
        return "bambu-lab"
    if "prusa" in v:
        return "prusa"
    if pid == "generic_klipper":
        return "klipper"
    return "export-only"


def main() -> int:
    pin = json.loads((ROOT / "engine/ENGINE_PIN.json").read_text())
    profiles = []
    for d in sorted(p for p in DIR.iterdir() if p.is_dir()):
        mpath = d / "machine.json"
        if not mpath.exists() or not (d / "process.json").exists() or not (d / "filament.json").exists():
            continue
        m = json.loads(mpath.read_text())
        area = m.get("printable_area") or ["0x0", "270x0", "270x270", "0x270"]
        pts = [tuple(float(x) for x in s.split("x")) for s in area if "x" in s and all(p.strip() for p in s.split("x"))] or [(0.0, 0.0), (270.0, 270.0)]
        bed = [round(max(p[0] for p in pts) - min(p[0] for p in pts), 2), round(max(p[1] for p in pts) - min(p[1] for p in pts), 2)]
        vendor = m.get("vendor") or m.get("printer_vendor") or (m.get("name", "").split(" ")[0])
        model = m.get("printer_model") or m.get("name", d.name)
        nozzles = m.get("nozzle_diameter") or ["0.4"]
        # Some profiles store per-tool values as one comma-separated string.
        if isinstance(nozzles, str): nozzles = nozzles.split(",")
        if isinstance(nozzles, list) and len(nozzles) == 1 and "," in str(nozzles[0]): nozzles = str(nozzles[0]).split(",")
        flavor = first(m.get("gcode_flavor"), "marlin")
        outputs = ["gcode"] + (["gcode.3mf"] if "bambu" in vendor.lower() else []) + (["bgcode"] if str(first(m.get("binary_gcode"), "0")) in ("1", "true") else [])
        profiles.append({
            "id": d.name, "vendor": vendor, "model": model, "name": m.get("name", d.name),
            "bed": bed, "height": float(first(m.get("printable_height"), 250)), "tools": len(nozzles) if isinstance(nozzles, list) else 1,
            "nozzle": float(first(nozzles, 0.4)), "gcodeFlavor": flavor, "outputs": outputs, "family": family_for(vendor, d.name),
        })
    index = {
        "version": 1,
        "source": {"upstream": pin["upstream"]["repo"], "commit": pin["upstream"]["commit"], "licence": "AGPL-3.0-or-later (OrcaSlicer resources/profiles)",
                   "notes": "Flattened from OrcaSlicer's profile library; see PROVENANCE.md and docs/family/PRINTER_SUPPORT_MATRIX.md for per-vendor licence review."},
        "profiles": profiles,
    }
    text = json.dumps(index, indent=1, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            print("stale: app/src/main/assets/slicer_profiles/index.json (run python3 scripts/build_profile_index.py)", file=sys.stderr)
            return 1
        print(f"profile index up to date ({len(profiles)} profiles)")
        return 0
    OUT.write_text(text)
    print(f"indexed {len(profiles)} printer profiles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
