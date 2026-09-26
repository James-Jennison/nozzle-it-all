#!/usr/bin/env python3
"""Bundle OrcaSlicer's own Bambu Lab and Prusa profiles for every 0.4 mm model, and generate the Kotlin model catalog.

Each model gets a flattened machine/process/filament pack under app/src/main/assets/slicer_profiles/<dir>/ (same
flattening as flatten_orca_profile.py - the JNI bridge has no `inherits` resolution). The catalog
(domain/.../SlicingModelCatalog.kt, GENERATED - do not edit) declares the SlicingPrinterModel enum and one entry per
model, so the picker, the pack lookup and the tests all read one list.

  bundle_vendor_profiles.py --profiles <orca>/resources/profiles

Existing packs (Snapmaker U1, Elegoo Centauri Carbon COSMOS, generic Klipper, Bambu A1, Prusa MK4, Prusa XL 5T) are
kept as they are and only listed in the catalog. Everything else here is unverified on hardware; the catalog says so.
"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from flatten_orca_profile import index, flatten  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "app/src/main/assets/slicer_profiles")
CATALOG = os.path.join(ROOT, "domain/src/main/kotlin/net/jamesjennison/klippercompanion/SlicingModelCatalog.kt")

# id, label, vendor, asset dir, verified-on-hardware. The first six are the hand-made/earlier packs and keep their names
# (the enum is persisted by name, so these must never be renamed).
EXISTING = [
    ("SNAPMAKER_U1", "Snapmaker U1", "SNAPMAKER", "snapmaker_u1", True),
    ("ELEGOO_CENTAURI_CARBON", "Elegoo Centauri Carbon (OpenCentauri COSMOS)", "ELEGOO", "elegoo_centauri_carbon_cosmos", True),
    ("BAMBU_GENERIC", "Bambu Lab A1", "BAMBU", "bambu_generic", False),
    ("PRUSA_GENERIC", "Prusa MK4", "PRUSA", "prusa_generic", False),
    ("GENERIC_KLIPPER", "Generic Klipper", "GENERIC", "generic_klipper", True),
    ("PRUSA_XL_5T", "Prusa XL 5T (five tools)", "PRUSA", "prusa_xl_5t", False),
]

# Prusa: explicit picks (Orca's names are irregular). (id, label, machine, process, filament)
PRUSA = [
    ("PRUSA_MK4S", "Prusa MK4S", "Prusa MK4S 0.4 nozzle", "0.20mm SPEED @MK4S 0.4", "Generic PLA @Prusa MK4S"),
    ("PRUSA_MK3_5", "Prusa MK3.5", "Prusa MK3.5 0.4 nozzle", "0.20mm Standard @MK3.5", "Generic PLA @Prusa MK3.5"),
    ("PRUSA_MK3S", "Prusa MK3S / MK3S+", "Prusa MK3S 0.4 nozzle", "0.20mm Standard @MK3S", "Generic PLA @Prusa"),
    ("PRUSA_MINI", "Prusa MINI / MINI+", "Prusa MINI 0.4 nozzle", "0.20mm Standard @MINI", "Generic PLA @Prusa"),
    ("PRUSA_MINI_IS", "Prusa MINI IS", "Prusa MINIIS 0.4 nozzle", "0.20mm Standard @MINIIS", "Generic PLA @Prusa MINIIS"),
    ("PRUSA_CORE_ONE", "Prusa CORE One", "Prusa CORE One 0.4 nozzle", "0.20mm SPEED @CORE One 0.4", "Generic PLA @Prusa CORE One"),
    ("PRUSA_CORE_ONE_L", "Prusa CORE One L", "Prusa CORE One L 0.4 nozzle", "0.20mm SPEED @CORE One L 0.4", "Generic PLA @Prusa CORE One"),
    ("PRUSA_XL", "Prusa XL (single tool)", "Prusa XL 0.4 nozzle", "0.20mm Speed @Prusa XL 0.4", "Generic PLA @Prusa XL"),
]

# Bambu: the six models we care most about are labelled explicitly; the rest come from the library's model list.
BAMBU_LABELS = {  # machine model name -> (id, label)
    "Bambu Lab A1 mini": ("BAMBU_A1_MINI", "Bambu Lab A1 mini"),
    "Bambu Lab A2L": ("BAMBU_A2L", "Bambu Lab A2L"),
    "Bambu Lab H2C": ("BAMBU_H2C", "Bambu Lab H2C"),
    "Bambu Lab H2D": ("BAMBU_H2D", "Bambu Lab H2D"),
    "Bambu Lab H2D Pro": ("BAMBU_H2D_PRO", "Bambu Lab H2D Pro"),
    "Bambu Lab H2S": ("BAMBU_H2S", "Bambu Lab H2S"),
    "Bambu Lab P1P": ("BAMBU_P1P", "Bambu Lab P1P"),
    "Bambu Lab P1S": ("BAMBU_P1S", "Bambu Lab P1S"),
    "Bambu Lab P2S": ("BAMBU_P2S", "Bambu Lab P2S"),
    "Bambu Lab X1": ("BAMBU_X1", "Bambu Lab X1"),
    "Bambu Lab X1 Carbon": ("BAMBU_X1_CARBON", "Bambu Lab X1 Carbon"),
    "Bambu Lab X1E": ("BAMBU_X1E", "Bambu Lab X1E"),
    "Bambu Lab X2D": ("BAMBU_X2D", "Bambu Lab X2D"),
}


def slug(model_id): return model_id.lower()


def write_pack(by_name, out_dir, machine, process, filament):
    os.makedirs(out_dir, exist_ok=True)
    for kind, name in (("machine", machine), ("process", process), ("filament", filament)):
        flat = flatten(kind, name, by_name)
        flat["name"] = name
        with open(os.path.join(out_dir, f"{kind}.json"), "w") as f:
            json.dump(flat, f, indent=4); f.write("\n")


def flat_compat(by_name, kind, name):
    try: return flatten(kind, name, by_name).get("compatible_printers") or []
    except SystemExit: return []


def pick_bambu(by_name, machine):
    procs = sorted(n for (k, n) in by_name if k == "process" and n and n.startswith("0.20mm Standard"))
    proc = next((n for n in procs if machine in flat_compat(by_name, "process", n)), None)
    fils = sorted(n for (k, n) in by_name if k == "filament" and n and "PLA" in n and "@BBL" in n)
    def rank(n): return (0 if n.startswith("Bambu PLA Basic") else 1 if n.startswith("Generic PLA") else 2, len(n), n)
    fil = next((n for n in sorted(fils, key=rank) if machine in flat_compat(by_name, "filament", n)), None)
    return proc, fil


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--profiles", required=True); a = ap.parse_args()
    entries = list(EXISTING); report = []
    bbl = index(a.profiles, "BBL")
    for full in sorted(n for (k, n) in bbl if k == "machine" and n and n.startswith("Bambu Lab") and n.endswith("0.4 nozzle")):
        model = full[: -len(" 0.4 nozzle")]
        if model == "Bambu Lab A1": continue  # already bundled as BAMBU_GENERIC
        if model not in BAMBU_LABELS: raise SystemExit(f"unlisted Bambu model {model}: add it to BAMBU_LABELS")
        mid, label = BAMBU_LABELS[model]
        proc, fil = pick_bambu(bbl, full)
        if not proc or not fil: raise SystemExit(f"no compatible process/filament for {full}: {proc} {fil}")
        write_pack(bbl, os.path.join(OUT, slug(mid)), full, proc, fil)
        entries.append((mid, label, "BAMBU", slug(mid), False)); report.append((label, full, proc, fil))
    prusa = index(a.profiles, "Prusa")
    for mid, label, machine, proc, fil in PRUSA:
        write_pack(prusa, os.path.join(OUT, slug(mid)), machine, proc, fil)
        entries.append((mid, label, "PRUSA", slug(mid), False)); report.append((label, machine, proc, fil))
    for r in report: print(" | ".join(r))
    order = {"SNAPMAKER": 0, "ELEGOO": 1, "BAMBU": 2, "PRUSA": 3, "GENERIC": 4}
    ids = [e[0] for e in entries]
    assert len(set(ids)) == len(ids)
    lines = [
        "package net.jamesjennison.klippercompanion", "",
        "// GENERATED by scripts/bundle_vendor_profiles.py - do not edit by hand; rerun the script.",
        "// One entry per bundled OrcaSlicer profile pack (app/src/main/assets/slicer_profiles/<assetDir>). The enum is persisted by",
        "// name(), so existing names are never renamed or removed; new models are only ever appended.",
        "enum class SlicingPrinterModel { " + ", ".join(ids) + " }", "",
        "enum class SlicingVendor(val label: String) { SNAPMAKER(\"Snapmaker\"), ELEGOO(\"Elegoo\"), BAMBU(\"Bambu Lab\"), PRUSA(\"Prusa\"), GENERIC(\"Generic\") }", "",
        "/** verifiedOnHardware: this pack has produced a real print on a real printer of this model; everything else is profile-only. */",
        "data class SlicingModelInfo(val model: SlicingPrinterModel, val label: String, val vendor: SlicingVendor, val assetDir: String, val verifiedOnHardware: Boolean)", "",
        "object SlicingModelCatalog {", "    val all: List<SlicingModelInfo> = listOf(",
    ]
    for mid, label, vendor, d, ver in sorted(entries, key=lambda e: (order[e[2]], e[1])):
        lines.append(f'        SlicingModelInfo(SlicingPrinterModel.{mid}, "{label}", SlicingVendor.{vendor}, "{d}", {str(ver).lower()}),')
    lines += ["    )", "    private val byModel = all.associateBy { it.model }",
              "    fun info(model: SlicingPrinterModel): SlicingModelInfo = byModel.getValue(model)", "}", ""]
    open(CATALOG, "w").write("\n".join(lines))
    print(f"\n{len(entries)} models in catalog; wrote {CATALOG}")


if __name__ == "__main__":
    main()
