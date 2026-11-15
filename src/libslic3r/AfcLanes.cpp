#include "AfcLanes.hpp"

#include "U1PrintTask.hpp"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <optional>
#include <string>
#include <vector>

namespace Slic3r {
namespace AfcLanes {

namespace {

const nlohmann::json *object(const nlohmann::json &parent, const std::string &name)
{
    if (!parent.is_object())
        return nullptr;
    const auto it = parent.find(name);
    return it != parent.end() && it->is_object() ? &*it : nullptr;
}

std::string text(const nlohmann::json &parent, const char *key)
{
    const auto it = parent.find(key);
    return it != parent.end() && it->is_string() ? it->get<std::string>() : std::string();
}

bool flag(const nlohmann::json &parent, const char *key)
{
    const auto it = parent.find(key);
    return it != parent.end() && it->is_boolean() && it->get<bool>();
}

// "T3" -> 3; -1 for anything else.
int tool_number(const std::string &map)
{
    if (map.size() < 2 || map.size() > 4 || (map[0] != 'T' && map[0] != 't'))
        return -1;
    int number = 0;
    for (size_t i = 1; i < map.size(); ++i) {
        if (!std::isdigit(static_cast<unsigned char>(map[i])))
            return -1;
        number = number * 10 + (map[i] - '0');
    }
    return number;
}

// AFC 1.2.7 reports a lane's "map" as a list of tool names; earlier versions as one name.
bool mapped_to(const nlohmann::json &lane, int tool)
{
    const auto it = lane.find("map");
    if (it == lane.end())
        return false;
    if (it->is_string())
        return tool_number(it->get<std::string>()) == tool;
    if (it->is_array())
        return std::any_of(it->begin(), it->end(), [tool](const nlohmann::json &entry) { return entry.is_string() && tool_number(entry.get<std::string>()) == tool; });
    return false;
}

std::string upper(std::string value)
{
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return value;
}

// "#RRGGBB", upper case; empty for anything else.
std::string color(std::string value)
{
    if (value.size() == 6)
        value = "#" + value;
    if (value.size() != 7 || value[0] != '#')
        return {};
    for (size_t i = 1; i < value.size(); ++i)
        if (!std::isxdigit(static_cast<unsigned char>(value[i])))
            return {};
    return upper(value);
}

} // namespace

std::vector<std::string> lane_names(const nlohmann::json &status)
{
    std::vector<std::string> names;
    const nlohmann::json    *afc = object(status, "AFC");
    if (afc == nullptr)
        return names;
    const auto it = afc->find("lanes");
    if (it != afc->end() && it->is_array())
        for (const nlohmann::json &entry : *it)
            if (entry.is_string())
                names.push_back(entry.get<std::string>());
    return names;
}

const nlohmann::json *lane_for_tool(const nlohmann::json &status, int tool)
{
    for (const std::string &name : lane_names(status))
        if (const nlohmann::json *lane = object(status, "AFC_lane " + name); lane != nullptr && mapped_to(*lane, tool))
            return lane;
    return nullptr;
}

bool lane_ready(const nlohmann::json &lane) { return flag(lane, "prep") && flag(lane, "load"); }

std::optional<U1PrintTask::PrinterState> parse_printer_state(const nlohmann::json &status)
{
    if (object(status, "AFC") == nullptr)
        return std::nullopt;
    U1PrintTask::PrinterState state;
    state.toolheads.resize(U1PrintTask::PHYSICAL_EXTRUDER_COUNT);
    for (int tool = 0; tool < U1PrintTask::PHYSICAL_EXTRUDER_COUNT; ++tool) {
        const nlohmann::json *lane = lane_for_tool(status, tool);
        if (lane == nullptr)
            continue;
        U1PrintTask::Filament &filament = state.toolheads[tool];
        filament.present = lane_ready(*lane);
        filament.type    = upper(text(*lane, "material"));
        filament.vendor  = text(*lane, "spool_vendor");
        filament.name    = text(*lane, "filament_name");
        filament.color   = color(text(*lane, "color"));
    }
    return state;
}

} // namespace AfcLanes
} // namespace Slic3r
