#include "U1PrintTask.hpp"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <optional>
#include <set>

namespace Slic3r {
namespace U1PrintTask {

namespace {

bool is_physical(int toolhead) { return toolhead >= 0 && toolhead < PHYSICAL_EXTRUDER_COUNT; }

std::string upper(std::string s)
{
    for (char &c : s)
        c = char(std::toupper(static_cast<unsigned char>(c)));
    return s;
}

// "RRGGBBAA", "RRGGBB" or "#RRGGBB" -> "#RRGGBB"; anything else -> "".
std::string normalize_color(std::string s)
{
    if (!s.empty() && s[0] == '#')
        s.erase(0, 1);
    if (s.size() != 6 && s.size() != 8)
        return {};
    for (char c : s)
        if (!std::isxdigit(static_cast<unsigned char>(c)))
            return {};
    return "#" + upper(s.substr(0, 6));
}

// Squared RGB distance; unknown colors compare as far apart.
long color_distance(const std::string &a, const std::string &b)
{
    if (a.size() != 7 || b.size() != 7)
        return 3 * 255 * 255 + 1;
    long sum = 0;
    for (int i = 1; i < 7; i += 2) {
        const long d = std::stol(a.substr(i, 2), nullptr, 16) - std::stol(b.substr(i, 2), nullptr, 16);
        sum += d * d;
    }
    return sum;
}

template<class T> T array_item(const nlohmann::json &obj, const char *key, size_t i, T fallback)
{
    const auto it = obj.find(key);
    if (it == obj.end() || !it->is_array() || i >= it->size())
        return fallback;
    try {
        return (*it)[i].get<T>();
    } catch (const nlohmann::json::exception &) {
        return fallback;
    }
}

bool flag(const nlohmann::json &obj, const char *key)
{
    const auto it = obj.find(key);
    return it != obj.end() && it->is_boolean() && it->get<bool>();
}

} // namespace

PrinterState parse_printer_state(const nlohmann::json &cfg)
{
    PrinterState state;
    state.toolheads.resize(PHYSICAL_EXTRUDER_COUNT);
    if (!cfg.is_object())
        return state;

    for (size_t i = 0; i < state.toolheads.size(); ++i) {
        Filament &f = state.toolheads[i];
        f.present   = array_item<bool>(cfg, "filament_exist", i, false);
        f.vendor    = array_item<std::string>(cfg, "filament_vendor", i, "");
        f.type      = upper(array_item<std::string>(cfg, "filament_type", i, ""));
        f.sub_type  = array_item<std::string>(cfg, "filament_sub_type", i, "");
        if (f.type == "NONE")
            f.type.clear();
        if (upper(f.vendor) == "NONE")
            f.vendor.clear();
        if (upper(f.sub_type) == "NONE")
            f.sub_type.clear();
        f.color = normalize_color(array_item<std::string>(cfg, "filament_color_rgba", i, ""));
    }
    state.bed_level         = flag(cfg, "auto_bed_leveling");
    state.flow_calibrate    = flag(cfg, "flow_calibrate");
    state.shaper_calibrate  = flag(cfg, "shaper_calibrate");
    state.time_lapse_camera = flag(cfg, "time_lapse_camera");
    return state;
}

std::vector<int> suggest_mapping(const std::vector<Filament> &plate_filaments, const std::vector<Filament> &toolheads)
{
    std::vector<int> mapping(plate_filaments.size(), UNMAPPED);
    const size_t     count = std::min(toolheads.size(), size_t(PHYSICAL_EXTRUDER_COUNT));

    struct Pair
    {
        size_t filament, toolhead;
        bool   same_type;
        long   distance;
        bool   own_number; // the toolhead with the filament's own number
    };
    const auto pair_of = [&](size_t f, size_t t) {
        const Filament &want = plate_filaments[f];
        return Pair{f, t, !want.type.empty() && upper(want.type) == toolheads[t].type,
                    color_distance(normalize_color(want.color), toolheads[t].color), t == f % size_t(PHYSICAL_EXTRUDER_COUNT)};
    };
    // Closest color first; among equals the filament's own number, so like filaments keep their numbers.
    const auto closer = [](const Pair &a, const Pair &b) {
        if (a.distance != b.distance)
            return a.distance < b.distance;
        if (a.own_number != b.own_number)
            return a.own_number;
        return a.filament != b.filament ? a.filament < b.filament : a.toolhead < b.toolhead;
    };

    // First, every filament that can have a loaded toolhead of its own material gets one, best matches first.
    // A toolhead already given to another filament is shared only when its color is exactly the filament's;
    // otherwise two filaments that merely look alike (the two halves of a color mix, say) would both be sent to
    // the nearest color and print from one spool. This is how Orca matches filaments to AMS trays.
    std::vector<Pair> pairs;
    for (size_t f = 0; f < plate_filaments.size(); ++f)
        for (size_t t = 0; plate_filaments[f].present && t < count; ++t)
            if (toolheads[t].present)
                if (const Pair pair = pair_of(f, t); pair.same_type)
                    pairs.push_back(pair);
    std::sort(pairs.begin(), pairs.end(), closer);
    std::vector<bool> taken(count, false);
    for (const Pair &pair : pairs)
        if (mapping[pair.filament] == UNMAPPED && (!taken[pair.toolhead] || pair.distance == 0)) {
            mapping[pair.filament] = int(pair.toolhead);
            taken[pair.toolhead]   = true;
        }

    // Whatever is left has no toolhead of its own to go to: the best loaded toolhead, shared if need be, with
    // the right material before the right color; with nothing loaded at all, the filament's own number.
    for (size_t f = 0; f < plate_filaments.size(); ++f) {
        if (!plate_filaments[f].present || mapping[f] != UNMAPPED)
            continue;
        std::optional<Pair> best;
        for (size_t t = 0; t < count; ++t)
            if (toolheads[t].present) {
                const Pair pair = pair_of(f, t);
                if (!best || (pair.same_type && !best->same_type) || (pair.same_type == best->same_type && closer(pair, *best)))
                    best = pair;
            }
        mapping[f] = best ? int(best->toolhead) : int(f % size_t(PHYSICAL_EXTRUDER_COUNT));
    }
    return mapping;
}

nlohmann::json build_filament_report(const nlohmann::json &status)
{
    const auto cfg = status.find("print_task_config");
    if (!status.is_object() || cfg == status.end() || !cfg->is_object())
        return nullptr;

    nlohmann::json report = nlohmann::json::object();
    for (const char *key : {"filament_vendor", "filament_type", "filament_sub_type", "filament_color", "filament_color_rgba",
                            "filament_color_multi", "filament_official", "extruder_map_table", "filament_exist"})
        if (cfg->contains(key))
            report[key] = (*cfg)[key];

    nlohmann::json diameters = nlohmann::json::array(), flows = nlohmann::json::array();
    for (int i = 0; i < PHYSICAL_EXTRUDER_COUNT; ++i) {
        const std::string name = i == 0 ? "extruder" : "extruder" + std::to_string(i);
        const auto        ex   = status.find(name);
        double            d    = 0.4;
        std::string       flow = "standard";
        if (ex != status.end() && ex->is_object()) {
            if (ex->contains("nozzle_diameter") && (*ex)["nozzle_diameter"].is_number())
                d = (*ex)["nozzle_diameter"].get<double>();
            if (ex->contains("nozzle_volume_type") && (*ex)["nozzle_volume_type"].is_string())
                flow = (*ex)["nozzle_volume_type"].get<std::string>();
        }
        char buf[16];
        std::snprintf(buf, sizeof(buf), "%.1f", d);
        diameters.push_back(buf);
        flows.push_back(flow);
    }
    report["nozzle_diameters"]    = diameters;
    report["nozzle_volume_types"] = flows;
    return report;
}

std::string set_filament_command(int toolhead, const std::string &vendor, const std::string &type,
                                 const std::string &sub_type, const std::string &color, bool force)
{
    const std::string rgb = normalize_color(color);
    if (!is_physical(toolhead) || rgb.empty())
        return {};
    auto clean = [](const std::string &in) {
        std::string out;
        for (char c : in)
            if (c != '"' && c != '\\' && static_cast<unsigned char>(c) >= 0x20)
                out += c;
        while (!out.empty() && out.back() == ' ')
            out.pop_back();
        while (!out.empty() && out.front() == ' ')
            out.erase(0, 1);
        return out.empty() ? std::string("NONE") : out;
    };
    return "SET_PRINT_FILAMENT_CONFIG CONFIG_EXTRUDER=" + std::to_string(toolhead) + " VENDOR=\"" + clean(vendor) +
           "\" FILAMENT_TYPE=\"" + clean(type) + "\" FILAMENT_SUBTYPE=\"" + clean(sub_type) +
           "\" FILAMENT_COLOR_RGBA=" + rgb.substr(1) + "FF" + (force ? " FORCE=1" : "");
}

std::string reread_tag_command(int toolhead)
{
    if (!is_physical(toolhead))
        return {};
    const std::string ch = std::to_string(toolhead);
    return "FILAMENT_DT_CLEAR CHANNEL=" + ch + "\nFILAMENT_DT_UPDATE CHANNEL=" + ch;
}

const std::vector<std::string> &known_filament_types()
{
    static const std::vector<std::string> types{"PLA", "PETG", "ABS", "ASA", "TPU", "PVA", "PA", "PC"};
    return types;
}

const std::vector<std::string> &known_filament_sub_types()
{
    static const std::vector<std::string> sub_types{"Basic", "Matte", "SnapSpeed", "Silk", "Support", "HF", "95A", "95A HF"};
    return sub_types;
}

bool validate(const Options &options, std::string &error)
{
    if (options.filament_to_toolhead.size() > size_t(LOGICAL_EXTRUDER_COUNT)) {
        error = "The U1 supports at most " + std::to_string(LOGICAL_EXTRUDER_COUNT) + " filaments per print.";
        return false;
    }
    for (size_t i = 0; i < options.filament_to_toolhead.size(); ++i) {
        const int toolhead = options.filament_to_toolhead[i];
        if (toolhead != UNMAPPED && !is_physical(toolhead)) {
            error = "Filament " + std::to_string(i + 1) + " is mapped to toolhead " + std::to_string(toolhead + 1) +
                    ", but the U1 has toolheads 1-" + std::to_string(PHYSICAL_EXTRUDER_COUNT) + ".";
            return false;
        }
    }
    if (options.flow_calibrate.value_or(false)) {
        if (options.flow_calibrate_toolheads.size() > size_t(PHYSICAL_EXTRUDER_COUNT)) {
            error = "Too many toolheads selected for flow calibration.";
            return false;
        }
        std::set<int> seen;
        for (int toolhead : options.flow_calibrate_toolheads) {
            if (!is_physical(toolhead) || !seen.insert(toolhead).second) {
                error = "Invalid toolhead selected for flow calibration.";
                return false;
            }
        }
    }
    return true;
}

std::string format_map_table(const std::vector<int> &filament_to_toolhead)
{
    std::string out = "[";
    bool        first = true;
    for (size_t i = 0; i < filament_to_toolhead.size(); ++i) {
        if (filament_to_toolhead[i] == UNMAPPED)
            continue;
        if (!first)
            out += ",";
        out += "[" + std::to_string(i) + "," + std::to_string(filament_to_toolhead[i]) + "]";
        first = false;
    }
    return out + "]";
}

std::string format_index_list(const std::vector<int> &indices)
{
    std::string out = "[";
    for (size_t i = 0; i < indices.size(); ++i) {
        if (i > 0)
            out += ",";
        out += std::to_string(indices[i]);
    }
    return out + "]";
}

nlohmann::json build_start_local_print_body(const std::string &path, int print_plate, const Options &options)
{
    nlohmann::json opts = nlohmann::json::object();

    const std::string map_table = format_map_table(options.filament_to_toolhead);
    if (map_table != "[]")
        opts["map_table"] = map_table;

    auto put_flag = [&opts](const char *key, const std::optional<bool> &value) {
        if (value)
            opts[key] = *value ? 1 : 0;
    };
    put_flag("bed_level", options.bed_level);
    put_flag("flow_calibrate", options.flow_calibrate);
    put_flag("shaper_calibrate", options.shaper_calibrate);
    put_flag("time_lapse_camera", options.time_lapse_camera);
    if (options.flow_calibrate.value_or(false) && !options.flow_calibrate_toolheads.empty())
        opts["flow_calibrate_extruders"] = format_index_list(options.flow_calibrate_toolheads);

    nlohmann::json body = {{"path", path}, {"print_plate", print_plate > 0 ? print_plate : 1}};
    if (!opts.empty())
        body["options"] = std::move(opts);
    return body;
}

} // namespace U1PrintTask
} // namespace Slic3r
