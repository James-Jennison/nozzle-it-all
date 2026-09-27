#!/usr/bin/env bash
# Generates schemas/fixtures/flush-volumes.json from the slicers' own flushing-volume code, compiled into tiny harnesses:
#   pairs   Snapmaker Orca (engine pin): src/libslic3r/FlushVolCalc.cpp + RGB2HSV (src/slic3r/Utils/ColorSpaceConvert.cpp)
#   orca    OrcaSlicer (engine/ENGINE_PIN.json's upstream commit): FlushVolCalc.cpp + FlushVolPredictor.cpp with its
#           resources/flush data (the measured-flush predictor, then the colour formula)
#   elegoo  ElegooSlicer (the commit its profiles were bundled from): the same plus its per-printer overrides
#           (FlushVolumeRules, resources/profiles/Elegoo/flush/flush_volumes.json) snapped through StandardColorMatcher.cpp
# Only file loading and logging are stubbed: FlushVolumeRules' JSON/boost loader is replaced by the same rules compiled
# in, and its lookup is restated (a priority pick over exact snapped matches). The desktop, Android and Web App ports
# (FlushVolumes, flush.ts) must reproduce every value.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pin() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(eval("d"+sys.argv[2]))' "$1" "$2"; }
SM="${SNAPMAKER_ORCA:-$(pin "$ROOT/engine/snapmaker/ENGINE_PIN.json" '["base"]["local_checkout"]')}"
SM_COMMIT="$(pin "$ROOT/engine/snapmaker/ENGINE_PIN.json" '["base"]["commit"]')"
ORCA="${ORCASLICER:-/mnt/faststorage/orcaslicer-android-engine/orcaslicer}"
ORCA_COMMIT="$(pin "$ROOT/engine/ENGINE_PIN.json" '["upstream"]["commit"]')"
ELEGOO="${ELEGOOSLICER:-/mnt/faststorage/ElegooSlicer}"
ELEGOO_COMMIT="${ELEGOOSLICER_COMMIT:-2d507e39a96ab9562b35d06b23ef5df8fe613297}" # scripts/bundle_elegoo_canvas.sh's checkout
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
show() { git -C "$1" show "$2:$3"; }

# --- Snapmaker Orca ---------------------------------------------------------------------------------------------------
mkdir -p "$W/sm"
show "$SM" "$SM_COMMIT" src/libslic3r/FlushVolCalc.cpp | grep -v '^#include' > "$W/sm/calc.inc"
show "$SM" "$SM_COMMIT" src/slic3r/Utils/ColorSpaceConvert.cpp | sed -n '/^void RGB2HSV(/,/^}/p' > "$W/sm/hsv.inc"
cat > "$W/sm/h.cpp" <<'CPP'
#include <cmath>
#include <algorithm>
#include <cstdio>
#define M_PI 3.14159265358979323846
#include "hsv.inc"
namespace Slic3r { class FlushVolCalculator { public: FlushVolCalculator(int min, int max, float multiplier = 1.0f);
  int calc_flush_vol(unsigned char, unsigned char, unsigned char, unsigned char, unsigned char, unsigned char, unsigned char, unsigned char);
  private: int m_min_flush_vol; int m_max_flush_vol; float m_multiplier; }; }
#include "calc.inc"
int main() {
  // Every colour on a 6-level cube (and transparent), each pair, at three minimum volumes; a spread-out 1-in-23 of them.
  const int lv[6] = {0, 51, 102, 153, 204, 255}; const int mins[3] = {0, 107, 250};
  bool first = true;
  std::printf("[");
  for (int a = 0; a < 217; ++a) for (int b = 0; b < 217; ++b) for (int m : mins) {
    if ((a * 7 + b * 13 + m) % 23 != 0) continue;
    unsigned char fa = a == 216 ? 0 : 255, ta = b == 216 ? 0 : 255;
    int fr = lv[a % 216 / 36], fg = lv[a % 36 / 6], fb = lv[a % 6], tr = lv[b % 216 / 36], tg = lv[b % 36 / 6], tb = lv[b % 6];
    Slic3r::FlushVolCalculator c(m, Slic3r::g_max_flush_volume);
    std::printf("%s[\"#%02X%02X%02X%02X\",\"#%02X%02X%02X%02X\",%d,%d]", first ? "" : ",\n  ", fr, fg, fb, fa, tr, tg, tb, ta, m,
                c.calc_flush_vol(fa, fr, fg, fb, ta, tr, tg, tb));
    first = false;
  }
  std::printf("]");
}
CPP
g++ -std=c++17 -O2 -I"$W/sm" -o "$W/sm/h" "$W/sm/h.cpp"

# --- OrcaSlicer and ElegooSlicer: the predictor-based calculation -----------------------------------------------------
# Test colours: a coarse cube, the predictor's measured colours and near-misses of them, the rule palette.
python3 - "$W" "$ORCA" "$ORCA_COMMIT" "$ELEGOO" "$ELEGOO_COMMIT" <<'PY'
import json, re, subprocess, sys
w, orca, oc, eleg, ec = sys.argv[1:]
show = lambda repo, c, p: subprocess.run(["git", "-C", repo, "show", f"{c}:{p}"], check=True, capture_output=True, text=True).stdout
data = show(orca, oc, "resources/flush/flush_data_standard.txt").splitlines()[1].split()
palette = re.findall(r'"(#[0-9A-Fa-f]{6})"', show(eleg, ec, "src/libslic3r/StandardColorMatcher.cpp"))
cube = ["#%02X%02X%02X" % (r, g, b) for r in (0, 128, 255) for g in (0, 128, 255) for b in (0, 128, 255)]
def near(h, d):
    v = [min(255, max(0, int(h[i:i + 2], 16) + d)) for i in (1, 3, 5)]; return "#%02X%02X%02X" % tuple(v)
colours = list(dict.fromkeys(data + [near(c, 3) for c in data] + [near(c, 9) for c in data] + palette + cube))
rules = json.loads(show(eleg, ec, "resources/profiles/Elegoo/flush/flush_volumes.json"))["rules"]
with open(f"{w}/colours.inc", "w") as f: f.write(",".join(f'"{c}"' for c in colours))
with open(f"{w}/rules.inc", "w") as f:
    for r in rules:
        f.write("{{%s},%d,{%s}},\n" % (",".join(json.dumps(p) for p in r.get("printer_name", [])), r.get("priority", 0),
                ",".join('{"%s","%s",%d}' % (e[0], e[1], int(e[2])) for e in r.get("flush_volume_entries", []))))
PY
for v in orca elegoo; do
  D="$W/$v"; mkdir -p "$D/src" "$D/stub/slic3r/Utils" "$D/res/flush"
  if [ $v = orca ]; then R="$ORCA"; C="$ORCA_COMMIT"; else R="$ELEGOO"; C="$ELEGOO_COMMIT"; fi
  for f in FlushVolCalc.cpp FlushVolCalc.hpp FlushVolPredictor.cpp FlushVolPredictor.hpp; do show "$R" "$C" "src/libslic3r/$f" > "$D/src/$f"; done
  for f in flush_data_standard.txt flush_data_dual_standard.txt flush_data_dual_highflow.txt; do show "$R" "$C" "resources/flush/$f" > "$D/res/flush/$f"; done
  show "$R" "$C" src/slic3r/Utils/ColorSpaceConvert.cpp | sed -n '/^void RGB2HSV(/,/^}/p' > "$D/stub/hsv.inc"
  printf 'void RGB2HSV(float r, float g, float b, float* h, float* s, float* v);\n' > "$D/stub/slic3r/Utils/ColorSpaceConvert.hpp"
  printf '#pragma once\n#include <cmath>\n#include <string>\n#include <limits>\n#include <cassert>\n#include <algorithm>\n#ifndef M_PI\n#define M_PI 3.14159265358979323846\n#endif\nnamespace Slic3r { std::string resources_dir(); }\n' > "$D/stub/Utils.hpp"
  printf '#pragma once\n#include "Utils.hpp"\n' > "$D/stub/libslic3r.h"; : > "$D/stub/PrintConfig.hpp"
  if [ $v = elegoo ]; then
    for f in StandardColorMatcher.cpp StandardColorMatcher.hpp; do show "$R" "$C" "src/libslic3r/$f" > "$D/src/$f"; done
    cat > "$D/stub/FlushVolumeRules.hpp" <<'CPP'
#pragma once
#include <optional>
#include <string>
#include <vector>
#include "../src/StandardColorMatcher.hpp"
namespace Slic3r {
// The rules of resources/profiles/Elegoo/flush/flush_volumes.json, compiled in; lookup as FlushVolumeRules::lookup
// (entries and inputs snapped through the matcher, exact snapped match, highest priority, later rule on a tie).
class FlushVolumeRules {
public:
  struct E { std::string from, to; int v; };
  struct R { std::vector<std::string> printers; int priority; std::vector<E> entries; };
  static FlushVolumeRules& instance() { static FlushVolumeRules r; return r; }
  std::optional<int> lookup(const std::string& printer, const std::string& from, const std::string& to) {
    static const std::vector<R> rules = {
#include "../../rules.inc"
    };
    if (printer.empty()) return std::nullopt;
    auto mf = m.match(from), mt = m.match(to);
    if (!mf || !mt) return std::nullopt;
    int best = -1; int bp = 0; int bv = 0;
    for (int i = 0; i < (int)rules.size(); ++i) {
      const R& r = rules[i];
      if (std::find(r.printers.begin(), r.printers.end(), printer) == r.printers.end()) continue;
      for (const E& e : r.entries) {
        auto ef = m.match(e.from), et = m.match(e.to);
        if (!ef || !et || ef->hex != mf->hex || et->hex != mt->hex) continue;
        if (best < 0 || r.priority > bp || r.priority == bp) { best = i; bp = r.priority; bv = e.v; }
        break;
      }
    }
    if (best < 0) return std::nullopt;
    return bv;
  }
private:
  StandardColorMatcher m;
};
}
CPP
    EXTRA="$D/src/StandardColorMatcher.cpp"; CALL='c.calc_flush_vol(255, fr, fg, fb, 255, tr, tg, tb, printer)'
    PRINTERS='"Elegoo Centauri Carbon 2 0.4 nozzle", "Elegoo Centauri Carbon 0.4 nozzle"'; MINS='107'; DATASETS='0'
  else
    EXTRA=""; CALL='c.calc_flush_vol(255, fr, fg, fb, 255, tr, tg, tb)'; PRINTERS='""'; MINS='0, 107'; DATASETS='0, 1, 2'
  fi
  cat > "$D/h.cpp" <<CPP
#include <cstdio>
#include <string>
#include "src/FlushVolCalc.hpp"
#include "hsv.inc"
namespace Slic3r { std::string resources_dir() { return "$D/res"; } }
static int hex(const char* s, int i) { int v; std::sscanf(s + 1 + 2 * i, "%2x", &v); return v; }
int main() {
  const char* colours[] = {
#include "../colours.inc"
  };
  const int n = sizeof(colours) / sizeof(colours[0]);
  const char* printers[] = { $PRINTERS }; const int mins[] = { $MINS }; const int datasets[] = { $DATASETS };
  bool first = true; long k = 0;
  std::printf("[");
  for (const char* printer_c : printers) for (int ds : datasets) for (int m : mins) for (int a = 0; a < n; ++a) for (int b = 0; b < n; ++b) {
    if (a == b || (++k) % (ds == 0 ? 5 : 17) != 0) continue;
    std::string printer = printer_c;
    int fr = hex(colours[a], 0), fg = hex(colours[a], 1), fb = hex(colours[a], 2), tr = hex(colours[b], 0), tg = hex(colours[b], 1), tb = hex(colours[b], 2);
    Slic3r::FlushVolCalculator c(m, Slic3r::g_max_flush_volume, ds);
    std::printf("%s[\"%s\",\"%s\",%d,%d,\"%s\",%d]", first ? "" : ",\n  ", colours[a], colours[b], m, ds, printer.c_str(), $CALL);
    first = false;
  }
  std::printf("]");
}
CPP
  g++ -std=c++17 -O2 -include "$D/stub/Utils.hpp" -I"$D/stub" -I"$D/src" -I"$D" -o "$D/h" "$D/h.cpp" "$D/src/FlushVolCalc.cpp" "$D/src/FlushVolPredictor.cpp" $EXTRA
done

OUT="$ROOT/schemas/fixtures/flush-volumes.json"
{ echo '{'
  echo " \"\$description\": \"Golden flushing volumes from the slicers' own code (scripts/flush_volumes_golden.sh). pairs: Snapmaker Orca $SM_COMMIT FlushVolCalc.cpp, [from #RRGGBBAA, to #RRGGBBAA, minimum, mm3]. orca: OrcaSlicer $ORCA_COMMIT FlushVolCalc.cpp + FlushVolPredictor.cpp, elegoo: ElegooSlicer $ELEGOO_COMMIT with its per-printer rules, both [from, to, minimum, dataset, printer, mm3]. FlushVolumes (Kotlin) and flush.ts must match every one.\","
  echo -n ' "pairs": '; "$W/sm/h"; echo ','
  echo -n ' "orca": '; "$W/orca/h"; echo ','
  echo -n ' "elegoo": '; "$W/elegoo/h"; echo
  echo '}'; } > "$OUT"
python3 - "$OUT" <<'PY'
import json, sys, collections
d = json.load(open(sys.argv[1]))
print(len(d["pairs"]), "Snapmaker pairs;", len(d["orca"]), "Orca pairs;", len(d["elegoo"]), "Elegoo pairs")
print("orca volumes by dataset:", {k: (min(v), max(v)) for k, v in collections.defaultdict(list, {}).items()} or
      {ds: (min(p[5] for p in d["orca"] if p[3] == ds), max(p[5] for p in d["orca"] if p[3] == ds)) for ds in (0, 1, 2)})
print("elegoo overrides hit (900/810):", sum(1 for p in d["elegoo"] if p[5] in (900, 810)))
PY
