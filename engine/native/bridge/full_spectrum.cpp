// Full Spectrum (colour mixing) for nozzle-engine. A port of Snapmaker Orca's colour-mixing GUI logic, commit cbf7bbb0b3
// (AGPL-3.0, as is this file). The mixed-filament list itself is libslic3r's MixedFilamentManager, used unchanged; what is
// ported here is the GUI code around it, with wxColour replaced by FsColour and wxString by std::string, and the bitmap /
// widget code dropped. Maths, thresholds, step sizes, heap sizes and tie-breaking are kept identical.
//
// Ported from src/slic3r/GUI/MixedColorMatchHelpers.cpp:
//   normalize_color_match_weights, expand_color_match_recipe_weights, summarize_color_match_recipe, BlendLUT,
//   sRGB_to_CIELab, delta_e_lab, build_blend_lut, blend_weighted_lab_accurate, color_delta_e00,
//   build_best_color_match_recipe (lines 363-619), compute_color_match_recipe_display_color,
//   decode_color_match_gradient_weights, build_pair_color_match_candidate, build_multi_color_match_candidate,
//   color_match_weights_within_range, build_color_match_sequence, blend_sequence_filament_mixer,
//   batch_match_model_colors, assign_batch_virtual_filament_ids, merge_duplicate_recipe_mappings
// Ported from src/slic3r/GUI/MixedFilamentBatchDialog.cpp:
//   FULL_SPECTRUM_FALLBACK_COLORS, kMinComponentPercent/kMaxComponentPercent, kDeltaEGoodMax/kDeltaEFairMax,
//   load_palette_colors (target de-duplication), load_recommended_palette, the dialog constructor's default palette
//   selection, launch_background_match (the worker body, lines 2379-2675, run synchronously)
// Ported from src/slic3r/GUI/Plater.cpp:
//   the batch-match apply path of the "Color Mixing Match" button (lines ~2650-3000: recommended-palette write-back,
//   in-place recipe update of existing custom rows, add_batch_custom_filaments, assigned-id fixup),
//   Sidebar::init_color_mix_panel's "+" and "-" handlers (~6585-6640), Sidebar::update_color_mix_panel's display context
//   and row label (~6645-6780), the row Edit / Delete menu handlers (~6840-7031)
// Ported from src/slic3r/GUI/MixedFilamentDialog.cpp:
//   collect_result() in RATIO mode (the result a plain "a + b at x%" edit produces)
#include "full_spectrum.hpp"
#include "fs_color.hpp"

#include <libslic3r/libslic3r.h>
#include <libslic3r/MixedFilament.hpp>
#include <libslic3r/FilamentColorLibrary.hpp>
#include <libslic3r/Utils.hpp>

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cassert>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <numeric>
#include <queue>
#include <sstream>
#include <stdexcept>
#include <tuple>
#include <unordered_map>
#include <unistd.h>

namespace nozzle_fs {

using namespace Slic3r;
using json = nlohmann::ordered_json;

struct BadRequest : std::runtime_error { using std::runtime_error::runtime_error; };

// ---------------------------------------------------------------------------------------------------------------------
// MixedColorMatchHelpers.hpp / .cpp
// ---------------------------------------------------------------------------------------------------------------------

struct CIELab { double L, a, b; };

class BlendLUT {
public:
    BlendLUT() : m_n(0) {}
    explicit BlendLUT(size_t n) : m_n(n) {
        if (n < 2) { m_n = 0; return; }
        m_pair.resize(n);
        for (size_t a = 0; a < n; ++a) {
            m_pair[a].resize(n - a);
            for (size_t b = a; b < n; ++b) m_pair[a][b - a].resize(101);
        }
    }
    size_t size() const { return m_n; }
    bool   empty() const { return m_n < 2; }
    const CIELab& get(size_t a, size_t b, int percent) const {
        assert(a < b);
        return m_pair[a][b - a][percent];
    }

private:
    size_t m_n;
    std::vector<std::vector<std::vector<CIELab>>> m_pair;
    friend BlendLUT build_blend_lut(const std::vector<FsColour>& palette);
};

struct MixedColorMatchRecipeResult {
    bool         cancelled     = false;
    bool         valid         = false;
    unsigned int component_a   = 1;
    unsigned int component_b   = 2;
    int          mix_b_percent = 50;
    std::string  manual_pattern;
    std::string  gradient_component_ids;
    std::string  gradient_component_weights;
    FsColour     preview_color = colour_from_string("#26A69A");
    double       delta_e       = std::numeric_limits<double>::infinity();
};

std::vector<int> decode_color_match_gradient_weights(const std::string& value, size_t expected_components);
std::vector<unsigned int> build_color_match_sequence(const std::vector<unsigned int>& ids, const std::vector<int>& weights);
FsColour blend_sequence_filament_mixer(const std::vector<FsColour>& palette, const std::vector<unsigned int>& sequence);
bool color_match_weights_within_range(const std::vector<int>& weights, int min_component_percent);

std::vector<int> normalize_color_match_weights(const std::vector<int>& weights, size_t count)
{
    if (count == 0)
        return {};
    std::vector<int> out = weights;
    if (out.size() != count)
        out.assign(count, int(100 / count));

    int sum = 0;
    for (int& value : out) {
        value = std::max(0, value);
        sum += value;
    }
    if (sum <= 0 && count > 0) {
        out.assign(count, 0);
        out[0] = 100;
        return out;
    }

    std::vector<double> remainders(count, 0.0);
    int                 assigned = 0;
    for (size_t idx = 0; idx < count; ++idx) {
        const double exact = 100.0 * double(out[idx]) / double(sum);
        out[idx]           = int(std::floor(exact));
        remainders[idx]    = exact - double(out[idx]);
        assigned += out[idx];
    }

    int missing = std::max(0, 100 - assigned);
    while (missing > 0) {
        size_t best_idx       = 0;
        double best_remainder = -1.0;
        for (size_t idx = 0; idx < remainders.size(); ++idx) {
            if (remainders[idx] > best_remainder) {
                best_remainder = remainders[idx];
                best_idx       = idx;
            }
        }
        ++out[best_idx];
        remainders[best_idx] = 0.0;
        --missing;
    }
    return out;
}

std::vector<int> expand_color_match_recipe_weights(const MixedColorMatchRecipeResult& recipe, size_t num_physical)
{
    std::vector<int> weights(num_physical, 0);
    if (!recipe.valid || num_physical == 0)
        return weights;

    if (!recipe.gradient_component_ids.empty()) {
        const std::vector<unsigned int> ids = MixedFilamentManager::decode_gradient_component_ids(recipe.gradient_component_ids);
        const std::vector<int>          raw_weights =
            normalize_color_match_weights(decode_color_match_gradient_weights(recipe.gradient_component_weights, ids.size()), ids.size());
        if (ids.size() != raw_weights.size())
            return weights;
        for (size_t idx = 0; idx < ids.size(); ++idx) {
            if (ids[idx] >= 1 && ids[idx] <= num_physical)
                weights[ids[idx] - 1] = raw_weights[idx];
        }
        return weights;
    }

    if (recipe.component_a >= 1 && recipe.component_a <= num_physical)
        weights[recipe.component_a - 1] = std::max(0, 100 - std::clamp(recipe.mix_b_percent, 0, 100));
    if (recipe.component_b >= 1 && recipe.component_b <= num_physical)
        weights[recipe.component_b - 1] = std::max(0, std::clamp(recipe.mix_b_percent, 0, 100));
    return weights;
}

// The component ids and weights summarize_color_match_recipe prints (split out so the response can carry them as arrays).
static void color_match_recipe_components(const MixedColorMatchRecipeResult& recipe, std::vector<unsigned int>& ids, std::vector<int>& weights)
{
    ids.clear();
    weights.clear();
    if (!recipe.gradient_component_ids.empty()) {
        ids     = MixedFilamentManager::decode_gradient_component_ids(recipe.gradient_component_ids);
        weights = normalize_color_match_weights(decode_color_match_gradient_weights(recipe.gradient_component_weights, ids.size()),
                                                ids.size());
    } else {
        ids     = {recipe.component_a, recipe.component_b};
        weights = {std::max(0, 100 - std::clamp(recipe.mix_b_percent, 0, 100)), std::max(0, std::clamp(recipe.mix_b_percent, 0, 100))};
    }
}

std::string summarize_color_match_recipe(const MixedColorMatchRecipeResult& recipe)
{
    if (!recipe.valid)
        return {};

    std::vector<unsigned int> ids;
    std::vector<int>          weights;
    color_match_recipe_components(recipe, ids, weights);
    if (ids.empty() || ids.size() != weights.size())
        return {};

    std::ostringstream out;
    for (size_t idx = 0; idx < ids.size(); ++idx) {
        if (idx > 0)
            out << '/';
        out << 'F' << ids[idx];
    }
    out << ' ';
    for (size_t idx = 0; idx < weights.size(); ++idx) {
        if (idx > 0)
            out << '/';
        out << weights[idx] << '%';
    }
    return out.str();
}

CIELab sRGB_to_CIELab(const FsColour& c)
{
    double r = c.Red() / 255.0;
    double g = c.Green() / 255.0;
    double b = c.Blue() / 255.0;
    float  lab[3];
    RGB2Lab(float(r), float(g), float(b), &lab[0], &lab[1], &lab[2]);
    return {double(lab[0]), double(lab[1]), double(lab[2])};
}

double delta_e_lab(const CIELab& a, const CIELab& b)
{
    return double(DeltaE00(float(a.L), float(a.a), float(a.b), float(b.L), float(b.a), float(b.b)));
}

BlendLUT build_blend_lut(const std::vector<FsColour>& palette)
{
    const size_t n = palette.size();
    BlendLUT     lut(n);
    if (lut.empty()) return lut;

    for (size_t a = 0; a < n; ++a) {
        for (size_t b = a; b < n; ++b) {
            for (int pct = 0; pct <= 100; ++pct) {
                FsColour blended          = blend_pair_filament_mixer(palette[a], palette[b], float(pct) / 100.f);
                lut.m_pair[a][b - a][pct] = sRGB_to_CIELab(blended);
            }
        }
    }
    return lut;
}

CIELab blend_weighted_lab_accurate(const std::vector<FsColour>& palette, const std::vector<unsigned int>& ids, const std::vector<int>& weights)
{
    if (ids.size() != weights.size() || ids.empty())
        return {50.0, 0.0, 0.0};

    std::vector<std::pair<unsigned int, int>> sorted;
    sorted.reserve(ids.size());
    for (size_t i = 0; i < ids.size(); ++i)
        sorted.emplace_back(ids[i], weights[i]);
    std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.first < b.first; });

    std::vector<FsColour> colors;
    std::vector<double>   dweights;
    colors.reserve(sorted.size());
    dweights.reserve(sorted.size());
    for (const auto& [id, w] : sorted) {
        if (id == 0 || id > palette.size()) continue;
        colors.push_back(palette[id - 1]);
        dweights.push_back(double(std::max(0, w)));
    }

    FsColour blended = blend_multi_filament_mixer(colors, dweights);
    return sRGB_to_CIELab(blended);
}

double color_delta_e00(const FsColour& lhs, const FsColour& rhs)
{
    float lhs_l = 0.f, lhs_a = 0.f, lhs_b = 0.f;
    float rhs_l = 0.f, rhs_a = 0.f, rhs_b = 0.f;
    RGB2Lab(float(lhs.Red()) / 255.f, float(lhs.Green()) / 255.f, float(lhs.Blue()) / 255.f, &lhs_l, &lhs_a, &lhs_b);
    RGB2Lab(float(rhs.Red()) / 255.f, float(rhs.Green()) / 255.f, float(rhs.Blue()) / 255.f, &rhs_l, &rhs_a, &rhs_b);
    return double(DeltaE00(lhs_l, lhs_a, lhs_b, rhs_l, rhs_a, rhs_b));
}

// check_compatible is not ported: the batch match (the only caller here) passes false in both modes
// (MixedFilamentBatchDialog.cpp launch_background_match), which makes the compatibility matrix all-true.
MixedColorMatchRecipeResult build_best_color_match_recipe(const std::vector<std::string>& physical_colors,
                                                          const FsColour&                 target_color,
                                                          int                             min_component_percent,
                                                          int                             max_component_percent)
{
    MixedColorMatchRecipeResult best;
    if (!target_color.IsOk() || physical_colors.size() < 2)
        return best;

    if (max_component_percent < 50 || max_component_percent > 100)
        return best;

    // ---- Step 1: build palette & pre-convert to Lab ----
    const size_t          n = physical_colors.size();
    std::vector<FsColour> palette;
    std::vector<CIELab>   palette_lab;
    palette.reserve(n);
    palette_lab.reserve(n);
    for (const std::string& hex : physical_colors) {
        FsColour c = parse_mixed_color(hex);
        palette.emplace_back(c);
        palette_lab.emplace_back(sRGB_to_CIELab(c));
    }
    const CIELab target_lab = sRGB_to_CIELab(target_color);

    const int loop_min_weight = std::max(1, std::clamp(min_component_percent, 0, 50));

    std::vector<std::vector<bool>> compat;
    compat.assign(n, std::vector<bool>(n, true));

    auto encode_gradient_ids = [](const std::vector<unsigned int>& ids) -> std::string {
        return MixedFilamentManager::encode_gradient_component_ids(ids);
    };

    auto encode_gradient_weights = [](const std::vector<int>& weights) -> std::string {
        std::ostringstream ss;
        for (size_t i = 0; i < weights.size(); ++i) {
            if (i > 0) ss << '/';
            ss << weights[i];
        }
        return ss.str();
    };

    // ---- Step 2: build pair Blend LUT (polynomial mixing → Lab) ----
    const BlendLUT lut = build_blend_lut(palette);
    if (lut.empty()) return best;

    auto update_best_pair = [&](unsigned int a, unsigned int b, int pct, double de) {
        if (!best.valid || de + 1e-6 < best.delta_e) {
            best.valid         = true;
            best.component_a   = a;
            best.component_b   = b;
            best.mix_b_percent = pct;
            best.preview_color = blend_pair_filament_mixer(palette[a - 1], palette[b - 1], float(pct) / 100.f);
            best.delta_e       = de;
            best.gradient_component_ids.clear();
            best.gradient_component_weights.clear();
            best.manual_pattern.clear();
        }
    };

    // ---- Step 3: pair coarse scan (step=5%) ----
    constexpr int k_coarse_step = 5;
    constexpr int k_top_coarse  = 30;

    using HeapEntry = std::tuple<double, unsigned int, unsigned int, int>;
    auto cmp = [](const HeapEntry& x, const HeapEntry& y) { return std::get<0>(x) < std::get<0>(y); };
    std::priority_queue<HeapEntry, std::vector<HeapEntry>, decltype(cmp)> heap(cmp);

    for (size_t a = 0; a < n; ++a) {
        for (size_t b = a + 1; b < n; ++b) {
            if (!compat[a][b]) continue;
            for (int pct = std::max(loop_min_weight, 100 - max_component_percent);
                 pct <= std::min(100 - loop_min_weight, max_component_percent); pct += k_coarse_step) {
                const CIELab& blended_lab = lut.get(a, b, pct);
                double        de          = delta_e_lab(target_lab, blended_lab);
                update_best_pair(unsigned(a + 1), unsigned(b + 1), pct, de);
                if (heap.size() < k_top_coarse) {
                    heap.emplace(de, unsigned(a + 1), unsigned(b + 1), pct);
                } else if (de < std::get<0>(heap.top())) {
                    heap.pop();
                    heap.emplace(de, unsigned(a + 1), unsigned(b + 1), pct);
                }
            }
        }
    }

    // ---- Step 4: pair fine search (step=1%, top-N from coarse) ----
    while (!heap.empty()) {
        auto [de, a, b, coarse_pct] = heap.top();
        (void) de;
        heap.pop();
        int fine_min = std::max(std::max(loop_min_weight, 100 - max_component_percent), coarse_pct - k_coarse_step + 1);
        int fine_max = std::min(std::min(100 - loop_min_weight, max_component_percent), coarse_pct + k_coarse_step - 1);
        for (int pct = fine_min; pct <= fine_max; ++pct) {
            if ((pct - loop_min_weight) % k_coarse_step == 0) continue;
            const CIELab& blended_lab = lut.get(a - 1, b - 1, pct);
            update_best_pair(a, b, pct, delta_e_lab(target_lab, blended_lab));
        }
    }

    MixedColorMatchRecipeResult best_pair = best;

    // ---- Step 5: early termination ----
    if (best_pair.valid && best_pair.delta_e <= 0.5)
        return best_pair;

    // ---- Step 6: adaptive candidate pool (top-N by single-color ΔE) ----
    std::vector<std::pair<double, unsigned int>> ranked_ids;
    ranked_ids.reserve(n);
    for (size_t idx = 0; idx < n; ++idx)
        ranked_ids.emplace_back(delta_e_lab(target_lab, palette_lab[idx]), unsigned(idx + 1));
    std::sort(ranked_ids.begin(), ranked_ids.end(), [](const auto& x, const auto& y) {
        if (x.first != y.first) return x.first < y.first;
        return x.second < y.second;
    });

    const size_t              pool_size = std::min<size_t>(n, 8);
    std::vector<unsigned int> candidate_pool;
    candidate_pool.reserve(pool_size);
    for (size_t i = 0; i < pool_size; ++i)
        candidate_pool.emplace_back(ranked_ids[i].second);

    if (candidate_pool.size() < 3)
        return best;

    std::sort(candidate_pool.begin(), candidate_pool.end());

    // ---- Step 7: triple layered search ----
    constexpr int k_triple_coarse_step = 10;
    constexpr int k_top_triple         = 20;

    struct TripleEntry {
        double       de;
        unsigned int a, b, c;
        int          wa, wb;
        bool         operator<(const TripleEntry& o) const { return de < o.de; }
    };
    std::priority_queue<TripleEntry> triple_heap;

    for (size_t fi = 0; fi + 2 < candidate_pool.size(); ++fi) {
        for (size_t fj = fi + 1; fj + 1 < candidate_pool.size(); ++fj) {
            for (size_t fk = fj + 1; fk < candidate_pool.size(); ++fk) {
                unsigned int a = candidate_pool[fi], b = candidate_pool[fj], c = candidate_pool[fk];
                if (!compat[a - 1][b - 1] || !compat[b - 1][c - 1] || !compat[a - 1][c - 1]) continue;

                for (int wa = loop_min_weight; wa <= std::min(100 - 2 * loop_min_weight, max_component_percent); wa += k_triple_coarse_step) {
                    for (int wb = loop_min_weight; wb <= std::min(100 - wa - loop_min_weight, max_component_percent); wb += k_triple_coarse_step) {
                        int wc = 100 - wa - wb;
                        if (wc < loop_min_weight || wc > max_component_percent) continue;
                        CIELab blended = blend_weighted_lab_accurate(palette, {a, b, c}, {wa, wb, wc});
                        double de      = delta_e_lab(target_lab, blended);
                        if (!best.valid || de + 1e-6 < best.delta_e) {
                            best.valid                      = true;
                            best.component_a                = a;
                            best.component_b                = b;
                            best.mix_b_percent              = wa + wb > 0 ? int(std::lround(100.0 * double(wb) / double(wa + wb))) : 50;
                            best.gradient_component_ids     = encode_gradient_ids({a, b, c});
                            best.gradient_component_weights = encode_gradient_weights({wa, wb, wc});
                            best.preview_color = blend_multi_filament_mixer({palette[a - 1], palette[b - 1], palette[c - 1]},
                                                                            {double(wa), double(wb), double(wc)});
                            best.delta_e = de;
                            best.manual_pattern.clear();
                        }
                        if (triple_heap.size() < k_top_triple) {
                            triple_heap.push({de, a, b, c, wa, wb});
                        } else if (de < triple_heap.top().de) {
                            triple_heap.pop();
                            triple_heap.push({de, a, b, c, wa, wb});
                        }
                    }
                }
            }
        }
    }

    while (!triple_heap.empty()) {
        TripleEntry te = triple_heap.top();
        triple_heap.pop();
        int wa_min = std::max(loop_min_weight, te.wa - k_triple_coarse_step + 1);
        int wa_max = std::min(std::min(100 - 2 * loop_min_weight, max_component_percent), te.wa + k_triple_coarse_step - 1);
        for (int wa = wa_min; wa <= wa_max; ++wa) {
            if ((wa - loop_min_weight) % k_triple_coarse_step == 0) continue;
            int wb_min = std::max(loop_min_weight, te.wb - k_triple_coarse_step + 1);
            int wb_max = std::min(std::min(100 - wa - loop_min_weight, max_component_percent), te.wb + k_triple_coarse_step - 1);
            for (int wb = wb_min; wb <= wb_max; ++wb) {
                if ((wb - loop_min_weight) % k_triple_coarse_step == 0) continue;
                int wc = 100 - wa - wb;
                if (wc < loop_min_weight || wc > max_component_percent) continue;
                CIELab blended = blend_weighted_lab_accurate(palette, {te.a, te.b, te.c}, {wa, wb, wc});
                double de2     = delta_e_lab(target_lab, blended);
                if (!best.valid || de2 + 1e-6 < best.delta_e) {
                    best.valid                      = true;
                    best.component_a                = te.a;
                    best.component_b                = te.b;
                    best.mix_b_percent              = wa + wb > 0 ? int(std::lround(100.0 * double(wb) / double(wa + wb))) : 50;
                    best.gradient_component_ids     = encode_gradient_ids({te.a, te.b, te.c});
                    best.gradient_component_weights = encode_gradient_weights({wa, wb, wc});
                    best.preview_color = blend_multi_filament_mixer({palette[te.a - 1], palette[te.b - 1], palette[te.c - 1]},
                                                                    {double(wa), double(wb), double(wc)});
                    best.delta_e = de2;
                    best.manual_pattern.clear();
                }
            }
        }
    }

    // ---- final normalization: re-evaluate ΔE with consistent color_delta_e00 ----
    if (best.valid)
        best.delta_e = color_delta_e00(target_color, best.preview_color);
    if (best_pair.valid) {
        best_pair.delta_e = color_delta_e00(target_color, best_pair.preview_color);
        if (!best.valid || best_pair.delta_e + 1e-6 < best.delta_e)
            best = std::move(best_pair);
        else if (!best.gradient_component_ids.empty() && best_pair.delta_e <= best.delta_e + 0.5)
            best = std::move(best_pair);
    }

    return best;
}

FsColour compute_color_match_recipe_display_color(const MixedColorMatchRecipeResult& recipe, const MixedFilamentDisplayContext& context)
{
    if (!recipe.valid)
        return recipe.preview_color.IsOk() ? recipe.preview_color : colour_from_string("#26A69A");

    MixedFilament entry;
    entry.component_a                = recipe.component_a;
    entry.component_b                = recipe.component_b;
    entry.mix_b_percent              = recipe.mix_b_percent;
    entry.manual_pattern             = recipe.manual_pattern;
    entry.gradient_component_ids     = recipe.gradient_component_ids;
    entry.gradient_component_weights = recipe.gradient_component_weights;
    entry.distribution_mode          = recipe.gradient_component_ids.empty() ? int(MixedFilament::Simple) : int(MixedFilament::LayerCycle);

    return parse_mixed_color(compute_mixed_filament_display_color(entry, context));
}

std::vector<int> decode_color_match_gradient_weights(const std::string& value, size_t expected_components)
{
    std::vector<int> weights;
    if (value.empty() || expected_components == 0)
        return weights;

    std::string token;
    for (const char ch : value) {
        if (ch >= '0' && ch <= '9') {
            token.push_back(ch);
            continue;
        }
        if (!token.empty()) {
            weights.emplace_back(std::max(0, std::atoi(token.c_str())));
            token.clear();
        }
    }
    if (!token.empty())
        weights.emplace_back(std::max(0, std::atoi(token.c_str())));
    if (weights.size() != expected_components)
        weights.clear();
    return weights;
}

MixedColorMatchRecipeResult build_pair_color_match_candidate(
    const std::vector<FsColour>& palette, unsigned int component_a, unsigned int component_b, int mix_b_percent, int min_component_percent)
{
    MixedColorMatchRecipeResult candidate;
    if (component_a == 0 || component_b == 0 || component_a == component_b)
        return candidate;
    if (component_a > palette.size() || component_b > palette.size())
        return candidate;
    if (!color_match_weights_within_range({100 - std::clamp(mix_b_percent, 0, 100), std::clamp(mix_b_percent, 0, 100)},
                                          min_component_percent))
        return candidate;

    candidate.valid         = true;
    candidate.component_a   = component_a;
    candidate.component_b   = component_b;
    candidate.mix_b_percent = std::clamp(mix_b_percent, 0, 100);
    candidate.preview_color = blend_pair_filament_mixer(palette[component_a - 1], palette[component_b - 1],
                                                        float(candidate.mix_b_percent) / 100.f);
    return candidate;
}

MixedColorMatchRecipeResult build_multi_color_match_candidate(const std::vector<FsColour>&     palette,
                                                              const std::vector<unsigned int>& ids,
                                                              const std::vector<int>&          weights,
                                                              int                              min_component_percent)
{
    MixedColorMatchRecipeResult candidate;
    if (ids.size() < 3 || ids.size() != weights.size())
        return candidate;
    if (!color_match_weights_within_range(weights, min_component_percent))
        return candidate;

    std::vector<std::pair<int, unsigned int>> weighted_ids;
    weighted_ids.reserve(ids.size());
    for (size_t idx = 0; idx < ids.size(); ++idx) {
        if (ids[idx] == 0 || ids[idx] > palette.size())
            return candidate;
        if (weights[idx] <= 0)
            continue;
        weighted_ids.emplace_back(weights[idx], ids[idx]);
    }
    if (weighted_ids.size() < 3)
        return candidate;

    std::sort(weighted_ids.begin(), weighted_ids.end(), [](const auto& lhs, const auto& rhs) {
        if (lhs.first != rhs.first)
            return lhs.first > rhs.first;
        return lhs.second < rhs.second;
    });

    std::vector<unsigned int> ordered_ids;
    std::vector<int>          ordered_weights;
    ordered_ids.reserve(weighted_ids.size());
    ordered_weights.reserve(weighted_ids.size());
    for (const auto& [weight, filament_id] : weighted_ids) {
        ordered_ids.emplace_back(filament_id);
        ordered_weights.emplace_back(weight);
    }

    const std::vector<unsigned int> sequence = build_color_match_sequence(ordered_ids, ordered_weights);
    if (sequence.empty())
        return candidate;

    candidate.valid             = true;
    candidate.component_a       = ordered_ids[0];
    candidate.component_b       = ordered_ids[1];
    const int pair_weight_total = ordered_weights[0] + ordered_weights[1];
    candidate.mix_b_percent     = pair_weight_total > 0 ?
                                      std::clamp(int(std::lround(100.0 * double(ordered_weights[1]) / double(pair_weight_total))), 0, 100) :
                                      50;
    candidate.gradient_component_ids = MixedFilamentManager::encode_gradient_component_ids(ordered_ids);
    {
        std::ostringstream weights_ss;
        for (size_t weight_idx = 0; weight_idx < ordered_weights.size(); ++weight_idx) {
            if (weight_idx > 0)
                weights_ss << '/';
            weights_ss << ordered_weights[weight_idx];
        }
        candidate.gradient_component_weights = weights_ss.str();
    }
    candidate.preview_color = blend_sequence_filament_mixer(palette, sequence);
    return candidate;
}

bool color_match_weights_within_range(const std::vector<int>& weights, int min_component_percent)
{
    if (min_component_percent <= 0)
        return true;

    const int min_allowed       = std::clamp(min_component_percent, 0, 50);
    int       active_components = 0;
    for (const int weight : weights) {
        if (weight <= 0)
            continue;
        ++active_components;
        if (weight < min_allowed)
            return false;
    }
    return active_components >= 2;
}

std::vector<unsigned int> build_color_match_sequence(const std::vector<unsigned int>& ids, const std::vector<int>& weights)
{
    if (ids.empty() || ids.size() != weights.size())
        return {};

    constexpr int k_max_cycle = 48;

    std::vector<unsigned int> filtered_ids;
    std::vector<int>          counts;
    filtered_ids.reserve(ids.size());
    counts.reserve(weights.size());
    for (size_t idx = 0; idx < ids.size(); ++idx) {
        const int weight = std::max(0, weights[idx]);
        if (weight <= 0)
            continue;
        filtered_ids.emplace_back(ids[idx]);
        counts.emplace_back(std::max(1, int(std::round((double(weight) / 100.0) * k_max_cycle))));
    }

    if (filtered_ids.empty())
        return {};

    int cycle = std::accumulate(counts.begin(), counts.end(), 0);
    while (cycle > k_max_cycle) {
        auto it = std::max_element(counts.begin(), counts.end());
        if (it == counts.end() || *it <= 1)
            break;
        --(*it);
        --cycle;
    }

    if (cycle <= 0)
        return {};

    std::vector<unsigned int> sequence;
    sequence.reserve(size_t(cycle));
    std::vector<int> emitted(counts.size(), 0);
    for (int pos = 0; pos < cycle; ++pos) {
        size_t best_idx   = 0;
        double best_score = -1e9;
        for (size_t idx = 0; idx < counts.size(); ++idx) {
            const double target = double((pos + 1) * counts[idx]) / double(std::max(1, cycle));
            const double score  = target - double(emitted[idx]);
            if (score > best_score) {
                best_score = score;
                best_idx   = idx;
            }
        }
        ++emitted[best_idx];
        sequence.emplace_back(filtered_ids[best_idx]);
    }
    return sequence;
}

FsColour blend_sequence_filament_mixer(const std::vector<FsColour>& palette, const std::vector<unsigned int>& sequence)
{
    if (palette.empty() || sequence.empty())
        return colour_from_string("#26A69A");

    std::vector<int> counts(palette.size() + 1, 0);
    for (const unsigned int filament_id : sequence) {
        if (filament_id == 0 || filament_id > palette.size())
            continue;
        ++counts[filament_id];
    }

    std::vector<FsColour> colors;
    std::vector<double>   weights;
    colors.reserve(palette.size());
    weights.reserve(palette.size());
    for (size_t filament_id = 1; filament_id <= palette.size(); ++filament_id) {
        if (counts[filament_id] <= 0)
            continue;
        colors.emplace_back(palette[filament_id - 1]);
        weights.emplace_back(double(counts[filament_id]));
    }
    return blend_multi_filament_mixer(colors, weights);
}

// ---- Batch Match Mapping (MixedColorMatchHelpers.hpp) ----

struct ModelColorEntry {
    unsigned int              color_index;
    FsColour                  color;
    std::string               hex_value;
    std::vector<unsigned int> extruder_ids;
};

struct ColorMappingEntry {
    unsigned int                model_color_index = 0;
    FsColour                    source_color;
    MixedColorMatchRecipeResult recipe;
    unsigned int                target_filament_id = 0;
    FsColour                    matched_color;
    double                      delta_e        = std::numeric_limits<double>::infinity();
    bool                        is_pure_recipe = false;
    double                      pure_delta_e   = std::numeric_limits<double>::infinity();
    std::vector<unsigned int>   merged_model_indices;
    std::vector<unsigned int>   source_extruder_ids;
    bool                        in_place_edited = false;
};

struct BatchMatchResult {
    std::vector<unsigned int>      selected_physical_ids;
    std::vector<ColorMappingEntry> mappings;
    double                         avg_delta_e = std::numeric_limits<double>::infinity();
    bool                           success     = false;
    std::string                    error_message;
    int                            error_code          = 0;
    bool                           is_recommended_mode = false;
    std::vector<std::string>       recommended_physical_colors;
    std::vector<std::string>       recommended_physical_family_names;
};

void assign_batch_virtual_filament_ids(BatchMatchResult& result, size_t num_physical, size_t existing_mixed_count)
{
    if (num_physical < 1)
        return;
    unsigned int next_virtual_id = static_cast<unsigned int>(num_physical + existing_mixed_count + 1);

    for (auto& mapping : result.mappings) {
        if (mapping.is_pure_recipe) {
            if (mapping.recipe.component_a < 1 || mapping.recipe.component_a > num_physical) {
                mapping.target_filament_id = next_virtual_id++;
                continue;
            }
            mapping.target_filament_id = mapping.recipe.component_a;
        } else {
            mapping.target_filament_id = next_virtual_id++;
        }
    }
}

std::vector<ColorMappingEntry> merge_duplicate_recipe_mappings(const std::vector<ColorMappingEntry>& mappings)
{
    if (mappings.size() < 2) return mappings;

    auto fingerprint = [](const MixedColorMatchRecipeResult& r) {
        std::ostringstream oss;
        oss << r.component_a << '|' << r.component_b << '|' << r.mix_b_percent << '|' << r.manual_pattern << '|'
            << r.gradient_component_ids << '|' << r.gradient_component_weights;
        return oss.str();
    };

    std::vector<ColorMappingEntry> result;
    result.reserve(mappings.size());
    std::unordered_map<std::string, size_t> seen;

    for (const ColorMappingEntry& m : mappings) {
        if (m.is_pure_recipe) {
            result.push_back(m);
            continue;
        }
        const std::string key = fingerprint(m.recipe);
        auto              it  = seen.find(key);
        if (it != seen.end()) {
            ColorMappingEntry& survivor = result[it->second];
            survivor.source_extruder_ids.insert(survivor.source_extruder_ids.end(), m.source_extruder_ids.begin(), m.source_extruder_ids.end());
            survivor.merged_model_indices.insert(survivor.merged_model_indices.end(), m.merged_model_indices.begin(),
                                                 m.merged_model_indices.end());
        } else {
            seen.emplace(key, result.size());
            result.push_back(m);
        }
    }

    if (result.size() != mappings.size()) {
        for (ColorMappingEntry& e : result) {
            if (e.is_pure_recipe) continue;
            std::sort(e.source_extruder_ids.begin(), e.source_extruder_ids.end());
            e.source_extruder_ids.erase(std::unique(e.source_extruder_ids.begin(), e.source_extruder_ids.end()), e.source_extruder_ids.end());
            std::sort(e.merged_model_indices.begin(), e.merged_model_indices.end());
            e.merged_model_indices.erase(std::unique(e.merged_model_indices.begin(), e.merged_model_indices.end()),
                                         e.merged_model_indices.end());
        }
    }
    return result;
}

// Cancellation and progress callbacks are dropped (the match runs synchronously).
BatchMatchResult batch_match_model_colors(const std::vector<ModelColorEntry>& model_colors,
                                          const std::vector<std::string>&     physical_colors,
                                          int                                 min_component_percent,
                                          int                                 max_component_percent)
{
    BatchMatchResult result;
    result.success = true;

    if (min_component_percent < 0 || min_component_percent > 50) {
        result.success       = false;
        result.error_message = "min_component_percent must be in [0, 50]";
        return result;
    }
    if (max_component_percent < 50 || max_component_percent > 100) {
        result.success       = false;
        result.error_message = "max_component_percent must be in [50, 100]";
        return result;
    }
    if (model_colors.empty()) {
        result.success       = false;
        result.error_message = "No model colors to match";
        return result;
    }
    if (physical_colors.size() < 2) {
        result.success       = false;
        result.error_message = "Need at least 2 physical filaments";
        return result;
    }

    for (size_t i = 0; i < model_colors.size(); ++i) {
        const auto&                 entry = model_colors[i];
        MixedColorMatchRecipeResult recipe =
            build_best_color_match_recipe(physical_colors, entry.color, min_component_percent, max_component_percent);

        if (!recipe.valid)
            continue;

        ColorMappingEntry mapping;
        mapping.model_color_index    = entry.color_index;
        mapping.source_color         = entry.color;
        mapping.recipe               = recipe;
        mapping.delta_e              = recipe.delta_e;
        mapping.matched_color        = recipe.preview_color;
        mapping.is_pure_recipe       = (recipe.mix_b_percent == 0);
        mapping.pure_delta_e         = recipe.delta_e;
        mapping.merged_model_indices = {entry.color_index};
        mapping.source_extruder_ids  = entry.extruder_ids;
        result.mappings.push_back(mapping);
    }

    if (result.mappings.empty()) {
        result.success       = false;
        result.error_message = "No valid recipes found for any model color";
        result.error_code    = 1;
        return result;
    }

    double sum_de = 0.0;
    for (const auto& m : result.mappings)
        sum_de += m.delta_e;
    result.avg_delta_e = sum_de / double(result.mappings.size());

    result.selected_physical_ids.clear();
    for (size_t i = 1; i <= physical_colors.size(); ++i)
        result.selected_physical_ids.push_back(static_cast<unsigned int>(i));
    return result;
}

// ---------------------------------------------------------------------------------------------------------------------
// MixedFilamentBatchDialog.cpp
// ---------------------------------------------------------------------------------------------------------------------

static const std::vector<std::string> FULL_SPECTRUM_FALLBACK_COLORS = {
    "#08ABFB", // semi-translucent cyan
    "#D93B90", // semi-translucent magenta
    "#F9ED3D", // semi-translucent yellow
    "#9199A4", // semi-translucent gray
};
static constexpr int    kMinComponentPercent = 0;   // recommended mode floor (near-pure allowed)
static constexpr int    kMaxComponentPercent = 70;  // recommended mode cap
static constexpr size_t kMaxColors           = 64;  // target-list cap (load_palette_colors)
static constexpr double kDeltaEGoodMax       = 4.0; // <4.0 → Good
static constexpr double kDeltaEFairMax       = 8.0; // <8.0 → Fair, else Poor

// MixedColorMatchHelpers.cpp full_spectrum_preset_name() for the only shipped fallback SKU; the family identity
// (GetFilamentMatchName) strips the nozzle suffix, so it is the same for every nozzle.
static std::string default_full_spectrum_family_name()
{
    return GetFilamentMatchName("Snapmaker PLA Full Spectrum @U1 0.4 nozzle");
}

// load_recommended_palette. `library_loaded` is FilamentColorLibrary::EnsureLoaded()'s result.
static std::vector<FullSpectrumPaletteEntry> load_recommended_palette(bool library_loaded, bool& used_fallback)
{
    used_fallback = false;
    std::vector<FullSpectrumPaletteEntry> palette;
    if (library_loaded)
        palette = BuildFullSpectrumPalette(FilamentColorLibrary::Instance().GetAllFilamentInfos());

    for (auto it = palette.begin(); it != palette.end();) {
        FsColour parsed;
        if (try_parse_color_match_hex(it->hex, parsed))
            ++it;
        else
            it = palette.erase(it);
    }

    if (palette.size() < static_cast<size_t>(kFullSpectrumSlotCount)) {
        static const char* fallback_names[kFullSpectrumSlotCount] = {"Cyan", "Magenta", "Yellow", "Gray"};
        used_fallback = true;
        palette.clear();
        for (size_t i = 0; i < FULL_SPECTRUM_FALLBACK_COLORS.size() && i < static_cast<size_t>(kFullSpectrumSlotCount); ++i) {
            FullSpectrumPaletteEntry entry;
            entry.hex         = FULL_SPECTRUM_FALLBACK_COLORS[i];
            entry.en_name     = fallback_names[i];
            entry.family_name = default_full_spectrum_family_name();
            palette.push_back(std::move(entry));
        }
    }
    return palette;
}

enum MatchingMethod { RECOMMENDED, MANUAL };

// The worker body of launch_background_match, run synchronously. `manual_selections` are the manual combos' 0-based
// selections (m_filament_combo[i]->GetSelection()); `recommended_selections` the palette dropdowns' indices.
static BatchMatchResult run_batch_match(MatchingMethod                               matching_method,
                                        const std::vector<ModelColorEntry>&          model_colors,
                                        const std::vector<std::string>&              m_physical_colors,
                                        const std::vector<int>&                      manual_selections,
                                        const std::vector<FullSpectrumPaletteEntry>& m_recommended_palette,
                                        const std::vector<int>&                      m_recommended_selections,
                                        size_t                                       existing_mixed_count,
                                        int                                          match_min_override,
                                        int                                          match_max_override)
{
    std::vector<std::string>  active_colors;
    std::vector<unsigned int> manual_full_ids;
    if (matching_method == MANUAL) {
        for (int sel : manual_selections) {
            if (sel >= 0 && sel < static_cast<int>(m_physical_colors.size())) {
                active_colors.push_back(m_physical_colors[sel]);
                manual_full_ids.push_back(static_cast<unsigned int>(sel + 1));
            }
        }
        if (active_colors.size() < 2) {
            active_colors = m_physical_colors;
            manual_full_ids.clear();
            for (size_t i = 0; i < m_physical_colors.size(); ++i)
                manual_full_ids.push_back(static_cast<unsigned int>(i + 1));
        }
    }
    const auto manual_colors = std::move(active_colors);
    const auto all_physical  = m_physical_colors;

    std::vector<std::string> preset_colors;
    std::vector<std::string> preset_family_names;
    if (matching_method == RECOMMENDED) {
        for (int i = 0; i < kFullSpectrumSlotCount && i < int(m_recommended_selections.size()); ++i) {
            const int sel = m_recommended_selections[i];
            if (sel >= 0 && sel < static_cast<int>(m_recommended_palette.size())) {
                preset_colors.push_back(m_recommended_palette[sel].hex);
                preset_family_names.push_back(m_recommended_palette[sel].family_name);
            }
        }
        if (preset_colors.size() < static_cast<size_t>(kFullSpectrumSlotCount)) {
            preset_colors = FULL_SPECTRUM_FALLBACK_COLORS;
            preset_family_names.assign(preset_colors.size(), default_full_spectrum_family_name());
        }
    }

    // ---- worker lambda body ----
    std::vector<std::string> physical_colors;
    if (matching_method == MANUAL) {
        physical_colors = manual_colors;
    } else {
        if (preset_colors.size() >= static_cast<size_t>(kFullSpectrumSlotCount))
            physical_colors = preset_colors;
        else
            physical_colors = all_physical;
    }

    BatchMatchResult result;
    result.success = true;
    std::vector<ModelColorEntry> unmatched_colors;

    const size_t              num_physical = physical_colors.size();
    std::vector<FsColour>     existing_palette;
    std::vector<unsigned int> existing_ids;
    for (size_t i = 0; i < physical_colors.size(); ++i) {
        FsColour c;
        if (try_parse_color_match_hex(physical_colors[i], c)) {
            existing_palette.push_back(c);
            existing_ids.push_back(static_cast<unsigned int>(i + 1));
        }
    }

    // Pass 1: a model colour within ΔE<1 of an existing filament maps directly to it.
    constexpr double K_REUSE_THRESHOLD = 1.0;
    for (const auto& mc : model_colors) {
        double best_de  = std::numeric_limits<double>::max();
        size_t best_idx = 0;
        for (size_t j = 0; j < existing_palette.size(); ++j) {
            double de = color_delta_e00(mc.color, existing_palette[j]);
            if (de < best_de) { best_de = de; best_idx = j; }
        }
        if (best_de < K_REUSE_THRESHOLD && best_idx < existing_ids.size()) {
            ColorMappingEntry mapping;
            mapping.model_color_index    = mc.color_index;
            mapping.source_color         = mc.color;
            mapping.target_filament_id   = existing_ids[best_idx];
            mapping.matched_color        = existing_palette[best_idx];
            mapping.delta_e              = best_de;
            mapping.is_pure_recipe       = (existing_ids[best_idx] <= static_cast<unsigned int>(num_physical));
            mapping.pure_delta_e         = best_de;
            mapping.merged_model_indices = {mc.color_index};
            mapping.source_extruder_ids  = mc.extruder_ids;
            mapping.recipe.valid         = true;
            mapping.recipe.component_a   = existing_ids[best_idx];
            mapping.recipe.mix_b_percent = 0;
            mapping.recipe.preview_color = existing_palette[best_idx];
            mapping.recipe.delta_e       = best_de;
            result.mappings.push_back(mapping);
        } else {
            unmatched_colors.push_back(mc);
        }
    }

    // Pass 2: batch-match the remaining colours (check_compatible=false in both modes).
    const int match_min = match_min_override >= 0 ? match_min_override : kMinComponentPercent;
    const int match_max = match_max_override >= 0 ? match_max_override : ((matching_method == MANUAL) ? 100 : kMaxComponentPercent);
    if (!unmatched_colors.empty()) {
        auto sub_result = batch_match_model_colors(unmatched_colors, physical_colors, match_min, match_max);
        if (sub_result.success) {
            assign_batch_virtual_filament_ids(sub_result, physical_colors.size(), existing_mixed_count);
            result.mappings.insert(result.mappings.end(), sub_result.mappings.begin(), sub_result.mappings.end());
        } else {
            result.success       = false;
            result.error_message = sub_result.error_message;
            result.error_code    = sub_result.error_code;
        }
    }

    // Manual mode: remap subset-relative ids back to the project's physical ids.
    const bool need_manual_remap = (matching_method == MANUAL && !manual_full_ids.empty() &&
                                    manual_full_ids.size() == physical_colors.size() && physical_colors.size() != all_physical.size());
    if (need_manual_remap) {
        std::vector<unsigned int> remap(physical_colors.size() + 1, 0);
        for (size_t i = 0; i < manual_full_ids.size(); ++i)
            remap[i + 1] = manual_full_ids[i];

        auto remap_id = [&](unsigned int& id) {
            if (id > 0 && id < remap.size() && remap[id] > 0)
                id = remap[id];
        };

        for (auto& mapping : result.mappings) {
            remap_id(mapping.target_filament_id);
            remap_id(mapping.recipe.component_a);
            remap_id(mapping.recipe.component_b);
            if (!mapping.recipe.gradient_component_ids.empty()) {
                auto ids = MixedFilamentManager::decode_gradient_component_ids(mapping.recipe.gradient_component_ids);
                for (auto& id : ids) remap_id(id);
                mapping.recipe.gradient_component_ids = MixedFilamentManager::encode_gradient_component_ids(ids);
            }
        }

        const unsigned int full_phys = static_cast<unsigned int>(all_physical.size());
        for (auto& mapping : result.mappings) {
            if (mapping.is_pure_recipe)
                mapping.is_pure_recipe = (mapping.recipe.component_a <= full_phys);
        }

        unsigned int next_vid = static_cast<unsigned int>(all_physical.size() + existing_mixed_count + 1);
        for (auto& mapping : result.mappings) {
            if (!mapping.is_pure_recipe)
                mapping.target_filament_id = next_vid++;
        }
    }

    result.mappings = merge_duplicate_recipe_mappings(result.mappings);

    if (result.success && result.mappings.empty()) {
        result.success       = false;
        result.error_message = "No valid recipes found for any model color";
        result.error_code    = 1;
    }
    if (result.success) {
        double sum_de = 0.0;
        for (const auto& m : result.mappings) sum_de += m.delta_e;
        result.avg_delta_e = sum_de / double(result.mappings.size());
        if (need_manual_remap) {
            result.selected_physical_ids = manual_full_ids;
        } else {
            for (size_t i = 1; i <= physical_colors.size(); ++i)
                result.selected_physical_ids.push_back(static_cast<unsigned int>(i));
        }
        if (matching_method == RECOMMENDED) {
            result.is_recommended_mode         = true;
            result.recommended_physical_colors = physical_colors;
            if (physical_colors.size() == preset_family_names.size())
                result.recommended_physical_family_names = preset_family_names;
        }
    }
    return result;
}

// ---------------------------------------------------------------------------------------------------------------------
// Plater.cpp: the Color Mixing panel and the batch-match apply path
// ---------------------------------------------------------------------------------------------------------------------

struct PanelSettings {
    std::vector<double> nozzle_diameters; // printer nozzle_diameter (per extruder; the last value repeats)
    float               lower_bound            = 0.04f;
    float               upper_bound            = 0.16f;
    bool                local_z_mode           = false;
    bool                component_bias_enabled = false;
};

// Sidebar::update_color_mix_panel's display context.
static MixedFilamentDisplayContext panel_display_context(const std::vector<std::string>& colors, const PanelSettings& s)
{
    const size_t             num_physical    = colors.size();
    std::vector<std::string> physical_colors = colors;
    physical_colors.resize(num_physical, "#26A69A");

    std::vector<double> nozzle_diameters(num_physical, 0.4);
    const size_t        opt_count = s.nozzle_diameters.size();
    if (opt_count > 0)
        for (size_t i = 0; i < num_physical; ++i)
            nozzle_diameters[i] = std::max(0.05, s.nozzle_diameters[std::min(i, opt_count - 1)]);

    float lower_bound = std::max(0.01f, s.lower_bound);
    float upper_bound = std::max(lower_bound, s.upper_bound);

    const MixedFilamentPreviewSettings preview_settings{0.2f, lower_bound, upper_bound, 0.f, 0.f, s.local_z_mode, false, 1};
    return MixedFilamentDisplayContext{num_physical, physical_colors, nozzle_diameters, preview_settings, s.component_bias_enabled};
}

// The row label Sidebar::update_color_mix_panel builds for the Color Mixing list.
static std::string panel_row_label(const MixedFilament& mf, size_t num_physical)
{
    char buf[64];
    const std::string         normalized_pattern_cm = MixedFilamentManager::normalize_manual_pattern(mf.manual_pattern);
    std::vector<unsigned int> gradient_ids          = MixedFilamentManager::decode_gradient_component_ids(mf.gradient_component_ids, 0);
    const bool z_gradient_tile = mf.gradient_enabled && mf.component_a != mf.component_b && normalized_pattern_cm.empty() && gradient_ids.size() < 3;
    std::string lbl;
    if (!normalized_pattern_cm.empty()) {
        // MixedColorMatchHelpers.cpp summarize_cycle_pattern_text
        const auto groups = MixedFilamentManager::split_pattern_groups(normalized_pattern_cm);
        std::map<unsigned int, int> counts;
        int                         total = 0;
        for (const auto& group : groups) {
            const auto tokens = MixedFilamentManager::split_pattern_group_to_tokens(group, num_physical);
            for (const auto& token : tokens) {
                unsigned int eid = MixedFilamentManager::physical_filament_from_token(token, mf, num_physical);
                if (eid >= 1 && eid <= static_cast<unsigned>(num_physical)) {
                    counts[eid]++;
                    total++;
                }
            }
        }
        if (num_physical == 0 || groups.empty() || total <= 0 || counts.empty())
            return {};
        std::vector<std::pair<unsigned int, int>> sorted(counts.begin(), counts.end());
        std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
        std::vector<int> pcts(sorted.size());
        int              sum_pct = 0;
        for (size_t i = 0; i < sorted.size(); ++i) {
            pcts[i] = int((static_cast<long long>(sorted[i].second) * 100) / total);
            sum_pct += pcts[i];
        }
        if (sum_pct < 100) {
            std::vector<std::pair<size_t, int>> rem;
            rem.reserve(sorted.size());
            for (size_t i = 0; i < sorted.size(); ++i)
                rem.emplace_back(i, int((static_cast<long long>(sorted[i].second) * 100) % total));
            std::sort(rem.begin(), rem.end(), [](const auto& a, const auto& b) {
                if (a.second != b.second) return a.second > b.second;
                return a.first < b.first;
            });
            for (int extra = 100 - sum_pct; extra > 0; --extra) {
                pcts[rem.front().first]++;
                rem.erase(rem.begin());
            }
        }
        std::ostringstream out;
        for (size_t i = 0; i < sorted.size(); ++i) {
            if (i > 0) out << '+';
            out << 'F' << sorted[i].first << ' ' << pcts[i] << '%';
        }
        lbl = out.str();
    } else if (gradient_ids.size() >= 3) {
        const size_t     n = gradient_ids.size();
        std::vector<int> weights;
        {
            std::string token;
            for (const char c : mf.gradient_component_weights) {
                if (c >= '0' && c <= '9') { token.push_back(c); continue; }
                if (!token.empty()) { weights.emplace_back(std::max(0, std::atoi(token.c_str()))); token.clear(); }
            }
            if (!token.empty()) weights.emplace_back(std::max(0, std::atoi(token.c_str())));
            if (weights.size() != n) weights.assign(n, int(100 / n));
        }
        int sum = 0;
        for (int v : weights) sum += v;
        if (sum <= 0) { weights.assign(n, 0); weights[0] = 100; sum = 100; }
        for (size_t k = 0; k < n; ++k) {
            const unsigned int fid = gradient_ids[k];
            const int          pct = int(std::round(100.0 * weights[k] / sum));
            if (k > 0) lbl += "+";
            std::snprintf(buf, sizeof buf, "F%u %d%%", fid, pct);
            lbl += buf;
        }
    } else if (z_gradient_tile) {
        const unsigned from_id = mf.gradient_start >= mf.gradient_end ? mf.component_a : mf.component_b;
        const unsigned to_id   = mf.gradient_start >= mf.gradient_end ? mf.component_b : mf.component_a;
        std::snprintf(buf, sizeof buf, "F%u->F%u", from_id, to_id);
        lbl = buf;
    } else {
        const int pct_b = std::clamp(mf.mix_b_percent, 0, 100);
        const int pct_a = 100 - pct_b;
        std::snprintf(buf, sizeof buf, "F%u %d%%+F%u %d%%", mf.component_a, pct_a, mf.component_b, pct_b);
        lbl = buf;
        if (mf.distribution_mode != int(MixedFilament::Simple))
            for (unsigned int fid : gradient_ids) {
                std::snprintf(buf, sizeof buf, "+F%u", fid);
                lbl += buf;
            }
    }
    return lbl;
}

// PresetBundle::update_multi_material_filament_presets' mixed-filament reload: auto_generate, drop custom rows, load the
// project's definitions against the current physical colours.
static void load_manager(MixedFilamentManager& mgr, const std::string& definitions, const std::vector<std::string>& colors)
{
    mgr.auto_generate(colors);
    mgr.clear_custom_entries();
    mgr.load_custom_entries(definitions, colors);
}

// Index into mixed_filaments() of the row the Color Mixing panel shows as virtual id `id` (non-deleted rows, in order).
static int panel_index_from_id(const MixedFilamentManager& mgr, size_t num_physical, long long id)
{
    if (id <= static_cast<long long>(num_physical)) return -1;
    size_t visible = 0;
    const auto& mfs = mgr.mixed_filaments();
    for (size_t i = 0; i < mfs.size(); ++i) {
        if (mfs[i].deleted) continue;
        if (static_cast<long long>(num_physical + visible + 1) == id) return int(i);
        ++visible;
    }
    return -1;
}

static json panel_rows(MixedFilamentManager& mgr, const std::vector<std::string>& colors, const PanelSettings& settings)
{
    const MixedFilamentDisplayContext ctx = panel_display_context(colors, settings);
    mgr.set_display_context(ctx);
    json rows  = json::array();
    int  index = 0;
    for (MixedFilament& mf : mgr.mixed_filaments()) {
        if (mf.deleted) continue;
        const std::string synced_color = compute_mixed_filament_display_color(mf, ctx);
        if (mf.display_color != synced_color) mf.display_color = synced_color;
        json r;
        r["id"]                = int(colors.size()) + index + 1;
        r["a"]                 = mf.component_a;
        r["b"]                 = mf.component_b;
        r["mix_b_percent"]     = mf.mix_b_percent;
        r["gradient_ids"]      = mf.gradient_component_ids;
        r["gradient_weights"]  = mf.gradient_component_weights;
        r["manual_pattern"]    = mf.manual_pattern;
        r["distribution_mode"] = mf.distribution_mode;
        r["enabled"]           = mf.enabled;
        r["custom"]            = mf.custom;
        r["ui_mode"]           = mf.ui_mode;
        r["stable_id"]         = mf.stable_id;
        r["display"]           = mf.display_color;
        r["label"]             = panel_row_label(mf, colors.size());
        rows.push_back(std::move(r));
        ++index;
    }
    return rows;
}

// ---------------------------------------------------------------------------------------------------------------------
// Request handling
// ---------------------------------------------------------------------------------------------------------------------

static std::string require_colour(const json& v, const char* what)
{
    if (!v.is_string()) throw BadRequest(std::string(what) + " must be a \"#RRGGBB\" string");
    FsColour c;
    if (!try_parse_color_match_hex(v.get<std::string>(), c)) throw BadRequest(std::string(what) + " is not a colour: " + v.get<std::string>());
    return c.hex();
}

// "physical": ["#RRGGBB", ...] or [{"color": "#RRGGBB", "type": "PLA"}, ...]
static std::vector<std::string> read_physical(const json& req)
{
    if (!req.contains("physical") || !req["physical"].is_array()) throw BadRequest("\"physical\" must be an array of colours");
    std::vector<std::string> out;
    for (const json& p : req["physical"]) {
        if (p.is_object()) {
            if (!p.contains("color")) throw BadRequest("a physical entry has no \"color\"");
            out.push_back(require_colour(p["color"], "physical colour"));
        } else {
            out.push_back(require_colour(p, "physical colour"));
        }
    }
    if (out.size() < 2) throw BadRequest("Colour mixing needs at least 2 physical filaments");
    if (out.size() > MixedFilamentManager::kMaxPhysicalFilaments) throw BadRequest("Too many physical filaments");
    return out;
}

static std::string read_definitions(const json& req)
{
    if (!req.contains("definitions") || req["definitions"].is_null()) return {};
    if (!req["definitions"].is_string()) throw BadRequest("\"definitions\" must be a string");
    return req["definitions"].get<std::string>();
}

static int read_int(const json& req, const char* key, int fallback)
{
    if (!req.contains(key) || req[key].is_null()) return fallback;
    if (!req[key].is_number_integer()) throw BadRequest(std::string("\"") + key + "\" must be an integer");
    return req[key].get<int>();
}

static PanelSettings read_settings(const json& req)
{
    PanelSettings s;
    if (req.contains("nozzle_diameter")) {
        const json& v = req["nozzle_diameter"];
        if (v.is_number()) s.nozzle_diameters = {v.get<double>()};
        else if (v.is_array()) for (const json& d : v) { if (!d.is_number()) throw BadRequest("bad nozzle_diameter"); s.nozzle_diameters.push_back(d.get<double>()); }
        else throw BadRequest("bad nozzle_diameter");
    }
    auto num = [&](const char* key, float& out) {
        if (!req.contains(key)) return;
        if (!req[key].is_number()) throw BadRequest(std::string("\"") + key + "\" must be a number");
        out = req[key].get<float>();
    };
    auto flag = [&](const char* key, bool& out) {
        if (!req.contains(key)) return;
        if (req[key].is_boolean()) out = req[key].get<bool>();
        else if (req[key].is_number_integer()) out = req[key].get<int>() != 0;
        else throw BadRequest(std::string("\"") + key + "\" must be a boolean");
    };
    num("mixed_filament_height_lower_bound", s.lower_bound);
    num("mixed_filament_height_upper_bound", s.upper_bound);
    flag("dithering_local_z_mode", s.local_z_mode);
    flag("mixed_filament_component_bias_enabled", s.component_bias_enabled);
    return s;
}

// MixedFilamentDialog::collect_result in RATIO mode, starting from `base` (the row being edited, or a fresh row), with
// the request's explicit fields applied on top.
static MixedFilament dialog_result(const MixedFilament& base, const json& req, size_t num_physical)
{
    MixedFilament r = base;
    r.component_a   = unsigned(read_int(req, "a", int(base.component_a)));
    r.component_b   = unsigned(read_int(req, "b", int(base.component_b)));
    if (r.component_a < 1 || r.component_a > num_physical || r.component_b < 1 || r.component_b > num_physical)
        throw BadRequest("a and b must be physical filament ids (1.." + std::to_string(num_physical) + ")");
    r.mix_b_percent = std::clamp(read_int(req, "mix_b_percent", base.mix_b_percent), 0, 100);
    r.ui_mode       = 0; // MODE_RATIO
    r.gradient_enabled = false;
    std::string gids = base.gradient_component_ids;
    if (req.contains("gradient_ids")) {
        if (!req["gradient_ids"].is_string()) throw BadRequest("\"gradient_ids\" must be a string such as \"124\"");
        gids = req["gradient_ids"].get<std::string>();
    } else if (req.contains("a") || req.contains("b") || req.contains("mix_b_percent")) {
        gids.clear();
    }
    const std::vector<unsigned int> ids = MixedFilamentManager::decode_gradient_component_ids(gids, num_physical);
    if (ids.size() >= 3) {
        // Three filaments: weighted layer cycle over all of them.
        r.distribution_mode      = int(MixedFilament::LayerCycle);
        r.manual_pattern.clear();
        r.gradient_component_ids = MixedFilamentManager::encode_gradient_component_ids(ids);
        if (req.contains("gradient_weights")) {
            if (!req["gradient_weights"].is_string()) throw BadRequest("\"gradient_weights\" must be a string such as \"50/25/25\"");
            r.gradient_component_weights = req["gradient_weights"].get<std::string>();
        }
    } else {
        r.gradient_component_weights.clear();
        r.distribution_mode = int(MixedFilament::Simple);
        r.gradient_component_ids.clear();
        r.manual_pattern.clear();
        const int pct_b   = std::clamp(r.mix_b_percent, 0, 100);
        int       ratio_a = 1, ratio_b = 0;
        if (pct_b >= 100) {
            ratio_a = 0; ratio_b = 1;
        } else if (pct_b > 0) {
            const int  pct_a      = 100 - pct_b;
            const bool b_is_major = pct_b >= pct_a;
            const int  major_pct  = b_is_major ? pct_b : pct_a;
            const int  minor_pct  = b_is_major ? pct_a : pct_b;
            const int  g          = std::gcd(major_pct, minor_pct);
            ratio_a               = b_is_major ? (minor_pct / g) : (major_pct / g);
            ratio_b               = b_is_major ? (major_pct / g) : (minor_pct / g);
        }
        r.ratio_a = std::max(0, ratio_a);
        r.ratio_b = std::max(0, ratio_b);
    }
    // Explicit overrides (the dialog's other modes produce these directly).
    if (req.contains("distribution_mode")) r.distribution_mode = std::clamp(read_int(req, "distribution_mode", r.distribution_mode), 0, 2);
    if (req.contains("manual_pattern")) {
        if (!req["manual_pattern"].is_string()) throw BadRequest("\"manual_pattern\" must be a string");
        r.manual_pattern = MixedFilamentManager::normalize_manual_pattern(req["manual_pattern"].get<std::string>());
    }
    if (req.contains("ui_mode")) r.ui_mode = read_int(req, "ui_mode", r.ui_mode);
    return r;
}

static json display_response(MixedFilamentManager& mgr, const std::vector<std::string>& colors, const PanelSettings& settings)
{
    json out;
    json rows          = panel_rows(mgr, colors, settings);
    out["definitions"] = mgr.serialize_custom_entries();
    out["rows"]        = std::move(rows);
    return out;
}

// Sidebar::init_color_mix_panel "+" handler.
static json op_add(const json& req)
{
    const auto          colors   = read_physical(req);
    const PanelSettings settings = read_settings(req);
    MixedFilamentManager mgr;
    load_manager(mgr, read_definitions(req), colors);
    for (const char* k : {"a", "b"})
        if (!req.contains(k)) throw BadRequest(std::string("add needs \"") + k + "\"");

    MixedFilament fresh; // MixedFilamentDialog's initial m_result (mix_b_percent 50)
    fresh.mix_b_percent = 50;
    const MixedFilament r = dialog_result(fresh, req, colors.size());

    long long added_id = 0;
    if (mgr.total_filaments(colors.size()) < MAXIMUM_FILAMENT_NUMBER) {
        mgr.add_custom_filament(r.component_a, r.component_b, r.mix_b_percent, colors);
        auto& mfs = mgr.mixed_filaments();
        if (!mfs.empty()) {
            mfs.back().distribution_mode          = r.distribution_mode;
            mfs.back().manual_pattern             = r.manual_pattern;
            mfs.back().gradient_component_ids     = r.gradient_component_ids;
            mfs.back().gradient_component_weights = r.gradient_component_weights;
            mfs.back().ratio_a                    = r.ratio_a;
            mfs.back().ratio_b                    = r.ratio_b;
            mfs.back().local_z_max_sublayers      = r.local_z_max_sublayers;
            mfs.back().gradient_enabled           = r.gradient_enabled;
            mfs.back().gradient_start             = r.gradient_start;
            mfs.back().gradient_end               = r.gradient_end;
            mfs.back().ui_mode                    = r.ui_mode;
            mfs.back().custom                     = true;
            size_t visible = 0;
            for (const MixedFilament& mf : mfs) if (!mf.deleted) ++visible;
            added_id = (long long) (colors.size() + visible);
        }
    }
    if (added_id == 0) throw std::runtime_error("The filament list is full (" + std::to_string(MAXIMUM_FILAMENT_NUMBER) + " filaments).");
    // Round-trip through the project string, as the GUI does (it stores serialize_custom_entries() and the panel reloads).
    const std::string defs = mgr.serialize_custom_entries();
    MixedFilamentManager saved;
    load_manager(saved, defs, colors);
    json out = display_response(saved, colors, settings);
    out["added_id"] = added_id;
    return out;
}

// Sidebar::init_color_mix_panel "-" handler (no id: last custom row) and the row menu's Delete (with id).
static json op_remove(const json& req)
{
    const auto          colors   = read_physical(req);
    const PanelSettings settings = read_settings(req);
    MixedFilamentManager mgr;
    load_manager(mgr, read_definitions(req), colors);
    auto& mfs = mgr.mixed_filaments();
    if (req.contains("id") && !req["id"].is_null()) {
        if (!req["id"].is_number_integer()) throw BadRequest("\"id\" must be an integer");
        const int i = panel_index_from_id(mgr, colors.size(), req["id"].get<long long>());
        if (i < 0) throw BadRequest("No colour-mixing row has id " + std::to_string(req["id"].get<long long>()));
        mfs[size_t(i)].deleted = true;
        mfs[size_t(i)].enabled = false;
    } else {
        for (int i = static_cast<int>(mfs.size()) - 1; i >= 0; --i) {
            if (mfs[i].custom && !mfs[i].deleted) {
                mfs[i].deleted = true;
                break;
            }
        }
    }
    const std::string defs = mgr.serialize_custom_entries();
    MixedFilamentManager saved;
    load_manager(saved, defs, colors);
    return display_response(saved, colors, settings);
}

// The row Edit handler (MixedFilamentDialog on an existing row).
static json op_update(const json& req)
{
    const auto          colors   = read_physical(req);
    const PanelSettings settings = read_settings(req);
    MixedFilamentManager mgr;
    load_manager(mgr, read_definitions(req), colors);
    if (!req.contains("id") || !req["id"].is_number_integer()) throw BadRequest("update needs an integer \"id\"");
    const int i = panel_index_from_id(mgr, colors.size(), req["id"].get<long long>());
    if (i < 0) throw BadRequest("No colour-mixing row has id " + std::to_string(req["id"].get<long long>()));
    auto&               mfs2 = mgr.mixed_filaments();
    const MixedFilament r    = dialog_result(mfs2[size_t(i)], req, colors.size());
    mfs2[i].component_a                = r.component_a;
    mfs2[i].component_b                = r.component_b;
    mfs2[i].mix_b_percent              = r.mix_b_percent;
    mfs2[i].distribution_mode          = r.distribution_mode;
    mfs2[i].manual_pattern             = r.manual_pattern;
    mfs2[i].gradient_component_ids     = r.gradient_component_ids;
    mfs2[i].gradient_component_weights = r.gradient_component_weights;
    mfs2[i].ratio_a                    = r.ratio_a;
    mfs2[i].ratio_b                    = r.ratio_b;
    mfs2[i].local_z_max_sublayers      = r.local_z_max_sublayers;
    mfs2[i].gradient_enabled           = r.gradient_enabled;
    mfs2[i].gradient_start             = r.gradient_start;
    mfs2[i].gradient_end               = r.gradient_end;
    mfs2[i].ui_mode                    = r.ui_mode;
    mfs2[i].custom                     = true;
    const std::string defs = mgr.serialize_custom_entries();
    MixedFilamentManager saved;
    load_manager(saved, defs, colors);
    return display_response(saved, colors, settings);
}

static json op_display(const json& req)
{
    const auto           colors = read_physical(req);
    MixedFilamentManager mgr;
    load_manager(mgr, read_definitions(req), colors);
    return display_response(mgr, colors, read_settings(req));
}

// FilamentColorLibrary reads filaments_colours.json from <data_dir>/system/Snapmaker/filament/ or
// <resources_dir>/profiles/Snapmaker/filament/. The request names the file directly, so it is staged into a private
// resources tree for the library to find.
struct StagedLibrary {
    std::filesystem::path root;
    ~StagedLibrary() { std::error_code ec; if (!root.empty()) std::filesystem::remove_all(root, ec); }
};

static bool load_colour_library(const std::string& path, StagedLibrary& staged)
{
    std::ifstream in(path, std::ios::binary);
    if (!in) throw BadRequest("Cannot read colour_library " + path);
    std::string tmpl = (std::filesystem::temp_directory_path() / "nozzle-fs-XXXXXX").string();
    if (mkdtemp(tmpl.data()) == nullptr) throw std::runtime_error("Cannot create a temporary directory");
    staged.root = tmpl;
    const auto dir = staged.root / "profiles" / "Snapmaker" / "filament";
    std::filesystem::create_directories(dir);
    std::filesystem::copy_file(path, dir / "filaments_colours.json", std::filesystem::copy_options::overwrite_existing);
    set_resources_dir(staged.root.string());
    set_data_dir((staged.root / "no-data-dir").string());
    return FilamentColorLibrary::Instance().EnsureLoaded();
}

static const char* quality(double de) { return de < kDeltaEGoodMax ? "Good" : (de < kDeltaEFairMax ? "Fair" : "Poor"); }

static json op_match(const json& req)
{
    const auto          m_physical_colors = read_physical(req);
    const PanelSettings settings          = read_settings(req);
    const std::string   definitions       = read_definitions(req);
    const std::string   mode              = req.value("mode", std::string("auto"));
    if (mode != "auto" && mode != "manual") throw BadRequest("\"mode\" must be \"auto\" or \"manual\"");
    const MatchingMethod method = mode == "auto" ? RECOMMENDED : MANUAL;

    // Targets, de-duplicated by normalized hex as load_palette_colors does (ids accumulate per colour).
    if (!req.contains("targets") || !req["targets"].is_array()) throw BadRequest("\"targets\" must be an array");
    std::vector<std::pair<std::string, std::vector<unsigned int>>> hex_to_eids;
    for (const json& t : req["targets"]) {
        if (!t.is_object() || !t.contains("color")) throw BadRequest("each target needs a \"color\"");
        const std::string hex_norm = normalize_color_match_hex(require_colour(t["color"], "target colour"));
        std::vector<unsigned int> ids;
        if (t.contains("ids")) {
            if (!t["ids"].is_array()) throw BadRequest("target \"ids\" must be an array");
            for (const json& id : t["ids"]) {
                if (!id.is_number_integer() || id.get<long long>() < 1) throw BadRequest("target ids must be positive integers");
                ids.push_back(id.get<unsigned int>());
            }
        }
        auto it = std::find_if(hex_to_eids.begin(), hex_to_eids.end(), [&](const auto& p) { return p.first == hex_norm; });
        if (it != hex_to_eids.end()) it->second.insert(it->second.end(), ids.begin(), ids.end());
        else hex_to_eids.push_back({hex_norm, ids});
    }
    std::vector<ModelColorEntry> model_colors;
    for (const auto& [hex, eids] : hex_to_eids) {
        FsColour c;
        try_parse_color_match_hex(hex, c);
        model_colors.push_back({static_cast<unsigned int>(model_colors.size() + 1), c, hex, eids});
    }
    while (model_colors.size() > kMaxColors) model_colors.pop_back();
    if (model_colors.empty()) throw BadRequest("No model colours to match");

    // Manual combos: the chosen physical slots (1-based in the request); default = the dialog's default, the first
    // min(4, N) slots.
    std::vector<int> manual_selections;
    if (method == MANUAL) {
        if (req.contains("manual_slots")) {
            if (!req["manual_slots"].is_array()) throw BadRequest("\"manual_slots\" must be an array");
            for (const json& s : req["manual_slots"]) {
                if (!s.is_number_integer()) throw BadRequest("manual_slots must be integers");
                manual_selections.push_back(s.get<int>() - 1);
            }
            if (manual_selections.size() < 2 || manual_selections.size() > 4) throw BadRequest("manual_slots takes 2 to 4 slots");
        } else {
            const size_t n = m_physical_colors.size();
            const int    count = n > 4 ? 4 : (n >= 2 ? static_cast<int>(n) : 2);
            for (int i = 0; i < count; ++i) manual_selections.push_back(i);
        }
    }

    // Recommended palette + the dialog's default dropdown selections.
    StagedLibrary                         staged;
    bool                                  library_loaded = false;
    bool                                  palette_fallback = false;
    std::vector<FullSpectrumPaletteEntry> recommended_palette;
    std::vector<int>                      recommended_selections;
    if (method == RECOMMENDED) {
        if (req.contains("colour_library") && req["colour_library"].is_string())
            library_loaded = load_colour_library(req["colour_library"].get<std::string>(), staged);
        recommended_palette = load_recommended_palette(library_loaded, palette_fallback);
        const auto defaults = DefaultFullSpectrumSelections(recommended_palette, default_full_spectrum_family_name());
        for (int i = 0; i < kFullSpectrumSlotCount; ++i)
            recommended_selections.push_back(i < static_cast<int>(defaults.size()) ? defaults[i] : -1);
    }

    const int min_override = req.contains("min_percent") ? read_int(req, "min_percent", 0) : -1;
    const int max_override = req.contains("max_percent") ? read_int(req, "max_percent", 0) : -1;
    if (min_override != -1 && (min_override < 0 || min_override > 50)) throw BadRequest("min_percent must be in [0, 50]");
    if (max_override != -1 && (max_override < 50 || max_override > 100)) throw BadRequest("max_percent must be in [50, 100]");

    // The project's mixed list at dialog time.
    MixedFilamentManager mgr;
    load_manager(mgr, definitions, m_physical_colors);
    const size_t existing_mixed_count = mgr.enabled_count();

    BatchMatchResult result = run_batch_match(method, model_colors, m_physical_colors, manual_selections, recommended_palette,
                                              recommended_selections, existing_mixed_count, min_override, max_override);
    if (!result.success) throw std::runtime_error(result.error_message.empty() ? "Colour matching failed" : result.error_message);

    // ---- Plater.cpp apply path ----
    std::unordered_map<unsigned int, uint64_t> dialog_vid_to_sid;
    const size_t dialog_num_physical = m_physical_colors.size();
    {
        unsigned int vid = static_cast<unsigned int>(dialog_num_physical) + 1;
        for (const MixedFilament& mf : mgr.mixed_filaments()) {
            if (!mf.enabled || mf.deleted) continue;
            dialog_vid_to_sid[vid++] = mf.stable_id;
        }
    }

    std::vector<std::string> colors_vec;
    if (result.is_recommended_mode && result.recommended_physical_colors.size() >= 4) {
        const auto&  cm            = result.recommended_physical_colors;
        const size_t current_count = m_physical_colors.size();
        const size_t target_count  = std::max<size_t>(4, current_count);
        colors_vec                 = m_physical_colors;
        colors_vec.resize(target_count);
        for (size_t i = 0; i < 4 && i < cm.size(); ++i)
            colors_vec[i] = cm[i];
        // set_num_filaments + the "Restore custom entries" reload, against the new palette.
        const std::string saved = mgr.serialize_custom_entries();
        MixedFilamentManager fresh;
        load_manager(fresh, saved, colors_vec);
        mgr = std::move(fresh);
    } else {
        colors_vec = m_physical_colors;
    }

    BatchMatchResult model_result = result;
    {
        const size_t cur_num_physical = colors_vec.size();
        bool         any_in_place     = false;
        for (auto& mapping : model_result.mappings) {
            if (mapping.is_pure_recipe || mapping.in_place_edited) continue;
            const MixedColorMatchRecipeResult& r = mapping.recipe;
            if (!r.valid) continue;
            if (r.component_a < 1 || r.component_a > cur_num_physical || r.component_b < 1 || r.component_b > cur_num_physical ||
                r.component_a == r.component_b)
                continue;
            uint64_t sid = 0;
            for (unsigned int src : mapping.source_extruder_ids) {
                auto it = dialog_vid_to_sid.find(src);
                if (it != dialog_vid_to_sid.end() && it->second != 0) { sid = it->second; break; }
            }
            if (sid == 0) continue;
            MixedFilament* target     = nullptr;
            unsigned int   target_vid = 0;
            unsigned int   vid        = static_cast<unsigned int>(cur_num_physical) + 1;
            for (MixedFilament& mf : mgr.mixed_filaments()) {
                if (!mf.enabled || mf.deleted) continue;
                if (mf.stable_id == sid) { target = &mf; target_vid = vid; break; }
                ++vid;
            }
            if (target == nullptr || !target->custom) continue;
            target->component_a                = r.component_a;
            target->component_b                = r.component_b;
            target->mix_b_percent              = r.mix_b_percent;
            target->gradient_component_ids     = r.gradient_component_ids;
            target->gradient_component_weights = r.gradient_component_weights;
            target->distribution_mode = r.gradient_component_ids.empty() ? int(MixedFilament::Simple) : int(MixedFilament::LayerCycle);
            target->gradient_enabled  = !r.gradient_component_ids.empty();
            target->manual_pattern.clear();
            target->ratio_a            = 1;
            target->ratio_b            = 1;
            target->ui_mode            = 2;
            mapping.in_place_edited    = true;
            mapping.target_filament_id = target_vid;
            any_in_place               = true;
        }
        if (any_in_place) mgr.refresh_display_colors(colors_vec);
    }

    // Translate mixed-slot source ids from the dialog epoch to the current epoch.
    std::vector<std::vector<unsigned int>> source_ids_before;
    for (auto& mapping : model_result.mappings) source_ids_before.push_back(mapping.source_extruder_ids);
    {
        const unsigned int cur_num_physical = static_cast<unsigned int>(colors_vec.size());
        for (auto& mapping : model_result.mappings) {
            std::vector<unsigned int> translated;
            for (unsigned int src : mapping.source_extruder_ids) {
                if (src <= static_cast<unsigned int>(dialog_num_physical)) { translated.push_back(src); continue; }
                auto it = dialog_vid_to_sid.find(src);
                if (it == dialog_vid_to_sid.end() || it->second == 0) continue;
                unsigned int cur_vid = 0, vid = cur_num_physical + 1;
                for (const MixedFilament& mf : mgr.mixed_filaments()) {
                    if (!mf.enabled || mf.deleted) continue;
                    if (mf.stable_id == it->second) { cur_vid = vid; break; }
                    ++vid;
                }
                if (cur_vid != 0) translated.push_back(cur_vid);
            }
            mapping.source_extruder_ids = std::move(translated);
        }
    }

    std::vector<MixedFilamentBatchEntry> batch_entries;
    for (const auto& mapping : model_result.mappings) {
        if (mapping.is_pure_recipe || mapping.in_place_edited) continue;
        MixedFilamentBatchEntry entry;
        entry.component_a                = mapping.recipe.component_a;
        entry.component_b                = mapping.recipe.component_b;
        entry.mix_b_percent              = mapping.recipe.mix_b_percent;
        entry.manual_pattern             = mapping.recipe.manual_pattern;
        entry.gradient_component_ids     = mapping.recipe.gradient_component_ids;
        entry.gradient_component_weights = mapping.recipe.gradient_component_weights;
        entry.distribution_mode = mapping.recipe.gradient_component_ids.empty() ? int(MixedFilament::Simple) : int(MixedFilament::LayerCycle);
        entry.display_color     = mapping.matched_color.hex();
        batch_entries.push_back(std::move(entry));
    }
    std::vector<unsigned int> assigned_ids;
    mgr.add_batch_custom_filaments(batch_entries, colors_vec, &assigned_ids);
    {
        size_t k = 0;
        for (auto& mapping : model_result.mappings) {
            if (mapping.is_pure_recipe || mapping.in_place_edited) continue;
            const unsigned int vid = (k < assigned_ids.size()) ? assigned_ids[k] : 0u;
            ++k;
            if (vid != 0u) {
                mapping.target_filament_id = vid;
            } else {
                mapping.target_filament_id = 0;
                mapping.source_extruder_ids.clear();
            }
        }
    }

    // ---- response ----
    const MixedFilamentDisplayContext ctx = panel_display_context(colors_vec, settings);
    mgr.set_display_context(ctx);
    json out;
    out["mode"] = mode;
    json palette = json::array();
    if (method == RECOMMENDED) {
        out["palette_source"] = palette_fallback ? "fallback" : "library";
        for (size_t i = 0; i < result.recommended_physical_colors.size(); ++i) {
            json p;
            p["slot"]   = i + 1;
            p["color"]  = result.recommended_physical_colors[i];
            p["family"] = i < result.recommended_physical_family_names.size() ? result.recommended_physical_family_names[i] : std::string();
            palette.push_back(std::move(p));
        }
    } else {
        for (unsigned int id : result.selected_physical_ids) {
            json p;
            p["slot"]  = id;
            p["color"] = m_physical_colors[id - 1];
            palette.push_back(std::move(p));
        }
    }
    out["palette"]        = std::move(palette);
    out["physical_after"] = colors_vec;

    json results = json::array();
    for (size_t mi = 0; mi < model_result.mappings.size(); ++mi) {
        const ColorMappingEntry& m = model_result.mappings[mi];
        json                     r;
        const ModelColorEntry&   src = model_colors[m.model_color_index - 1];
        r["target"] = src.hex_value;
        if (m.merged_model_indices.size() > 1) {
            json merged = json::array();
            for (unsigned int idx : m.merged_model_indices) merged.push_back(model_colors[idx - 1].hex_value);
            r["merged_targets"] = std::move(merged);
        }
        r["source_ids"] = source_ids_before[mi];
        if (m.source_extruder_ids != source_ids_before[mi]) r["source_ids_after"] = m.source_extruder_ids;
        r["pure"] = m.is_pure_recipe;
        if (m.is_pure_recipe) {
            r["slot"] = m.target_filament_id;
        } else {
            r["id"] = m.target_filament_id; // 0 when the filament list was full and the row was dropped
            std::vector<unsigned int> ids;
            std::vector<int>          weights;
            color_match_recipe_components(m.recipe, ids, weights);
            r["components"] = ids;
            r["weights"]    = weights;
            r["summary"]    = summarize_color_match_recipe(m.recipe);
            if (m.in_place_edited) r["in_place"] = true;
            if (const MixedFilament* mf = mgr.mixed_filament_from_id(m.target_filament_id, colors_vec.size()))
                r["display"] = mf->display_color;
        }
        r["preview"] = m.matched_color.hex();
        r["delta_e"] = std::round(m.delta_e * 100.0) / 100.0;
        r["quality"] = quality(m.delta_e);
        results.push_back(std::move(r));
    }
    out["results"]     = std::move(results);
    out["avg_delta_e"] = std::round(result.avg_delta_e * 100.0) / 100.0;

    // Snapmaker then runs Sidebar::cleanup_unused_filaments_after_batch_match, which deletes the physical and mixed
    // filaments the model no longer uses. That needs the model; this reports what compute_redundant_filaments would
    // remove given the ids the caller says are still used ("keep_ids", post-remap), and leaves the list untouched.
    {
        const size_t              num_physical = colors_vec.size();
        std::vector<unsigned int> kept_physical, kept_mixed;
        for (unsigned int id : result.selected_physical_ids) if (id >= 1 && id <= num_physical) kept_physical.push_back(id);
        for (const auto& m : model_result.mappings) if (!m.is_pure_recipe && m.target_filament_id > num_physical) kept_mixed.push_back(m.target_filament_id);
        if (req.contains("keep_ids") && req["keep_ids"].is_array())
            for (const json& id : req["keep_ids"])
                if (id.is_number_integer() && id.get<long long>() > (long long) num_physical) kept_mixed.push_back(id.get<unsigned int>());
        const RedundantFilamentSet red = compute_redundant_filaments(num_physical, kept_physical, kept_mixed, mgr.mixed_filaments());
        json c;
        c["physical"] = red.redundant_physical;
        c["mixed"]    = red.redundant_mixed;
        out["redundant"] = std::move(c);
    }

    out["definitions"] = mgr.serialize_custom_entries();
    out["rows"]        = panel_rows(mgr, colors_vec, settings);
    return out;
}

int run_full_spectrum(const std::string& request_text, std::string& response)
{
    try {
        json req;
        try {
            req = json::parse(request_text);
        } catch (const std::exception& e) {
            throw BadRequest(std::string("The request is not valid JSON: ") + e.what());
        }
        if (!req.is_object() || !req.contains("op") || !req["op"].is_string()) throw BadRequest("The request needs an \"op\"");
        const std::string op = req["op"].get<std::string>();
        json              out;
        if (op == "display") out = op_display(req);
        else if (op == "add") out = op_add(req);
        else if (op == "remove") out = op_remove(req);
        else if (op == "update") out = op_update(req);
        else if (op == "match") out = op_match(req);
        else throw BadRequest("Unknown op: " + op);
        response = out.dump();
        return 0;
    } catch (const BadRequest& e) {
        response = json{{"error", e.what()}}.dump();
        return 2;
    } catch (const std::exception& e) {
        response = json{{"error", e.what()}}.dump();
        return 1;
    }
}

} // namespace nozzle_fs
