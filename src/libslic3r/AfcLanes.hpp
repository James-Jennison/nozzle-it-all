#pragma once

#include "U1PrintTask.hpp"

#include <nlohmann/json.hpp>

#include <optional>
#include <string>
#include <vector>

namespace Slic3r {

/// What a Klipper printer with AFC (Armored Turtle's filament changer: a Centauri Carbon on COSMOS with a
/// CANVAS) reports about its lanes, read from a Moonraker status that holds the "AFC" object and one
/// "AFC_lane <name>" object per lane. A lane answers to the tool numbers in its "map" ("T0", ...): `T<n>`
/// in a file loads the lane mapped to that number.
///
/// No wxWidgets or network dependency, so it can be unit-tested directly.
namespace AfcLanes {

/// The names of the lanes, from the "AFC" object's "lanes" list; empty when *status* holds no AFC object.
std::vector<std::string> lane_names(const nlohmann::json &status);

/// The lane object mapped to tool *tool* (0-based), or null when no lane in *status* is.
const nlohmann::json *lane_for_tool(const nlohmann::json &status, int tool);

/// True when the lane reports filament at both of its sensors: ready to be loaded.
bool lane_ready(const nlohmann::json &lane);

/// The lanes as the send dialog's sources, indexed by tool number, always
/// U1PrintTask::PHYSICAL_EXTRUDER_COUNT entries; a tool no lane is mapped to, or whose lane is not ready,
/// is not present. Null when *status* holds no AFC object.
std::optional<U1PrintTask::PrinterState> parse_printer_state(const nlohmann::json &status);

} // namespace AfcLanes
} // namespace Slic3r
