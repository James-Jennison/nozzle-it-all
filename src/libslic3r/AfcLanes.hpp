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

/// The brand of the spool in *lane* (one of *status*'s lane objects), empty when the printer does not know it.
/// AFC's own "spool_vendor" (filled from Spoolman) comes first. Otherwise the brand the printer's configuration
/// keeps per lane in Klipper's saved variables, "save_variables" -> "variables" -> "canvas_tag_<lane name>" with
/// "manufacturer", "type", "color" and "source" (a CANVAS on COSMOS whose tag macros save what was scanned or
/// told): it is used only while the lane is ready and still reports that entry's material and colour, and never
/// from an entry whose source is the Elegoo tag format, which names no maker.
std::string lane_brand(const nlohmann::json &status, const nlohmann::json &lane);

/// lane_brand() by tool number: always U1PrintTask::PHYSICAL_EXTRUDER_COUNT entries.
std::vector<std::string> tool_brands(const nlohmann::json &status);

/// The identity of the tag last read for the spool in *lane*, where the printer keeps one in its saved variables
/// (canvas_tag_<lane>.tag, as the Owner's CANVAS configuration writes it). Empty unless the lane has filament
/// ready and still reports the material and colour that entry was written with: the entry is then about the
/// spool that is in the lane now.
std::string lane_tag(const nlohmann::json &status, const nlohmann::json &lane);
/// lane_tag() by tool number: always U1PrintTask::PHYSICAL_EXTRUDER_COUNT entries.
std::vector<std::string> tool_tags(const nlohmann::json &status);

/// What the printer keeps of the weight of the spool in a lane: what it held when put in ("initial_weight") and
/// what the printer counts as left on it now ("weight"), in grams. 0 where the lane gives no usable figure. The
/// printer's count is its own estimate from what it fed, not a weighing.
struct Weight
{
    double initial_g = 0.;
    double left_g    = 0.;
};
Weight lane_weight(const nlohmann::json &lane);
/// lane_weight() by tool number, for lanes with filament ready: always U1PrintTask::PHYSICAL_EXTRUDER_COUNT entries.
std::vector<Weight> tool_weights(const nlohmann::json &status);

/// The lanes as the send dialog's sources, indexed by tool number, always
/// U1PrintTask::PHYSICAL_EXTRUDER_COUNT entries; a tool no lane is mapped to, or whose lane is not ready,
/// is not present. Null when *status* holds no AFC object.
std::optional<U1PrintTask::PrinterState> parse_printer_state(const nlohmann::json &status);

} // namespace AfcLanes
} // namespace Slic3r
