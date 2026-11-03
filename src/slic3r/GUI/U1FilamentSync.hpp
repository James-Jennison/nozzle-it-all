#ifndef slic3r_GUI_U1FilamentSync_hpp_
#define slic3r_GUI_U1FilamentSync_hpp_

#include <cstddef>
#include <cstdint>
#include <string>

class wxWindow;

namespace Slic3r {

class DynamicPrintConfig;
class Preset;

}

namespace Slic3r::GUI {

/// Ranks a compatible same-type preset for a U1-reported vendor and subtype. Identity fields
/// precede visibility so an enabled unrelated brand cannot beat an exact or generic match.
std::uint64_t u1_filament_preset_rank(const Preset &preset, const std::string &vendor, const std::string &sub_type);

/// Returns the number of physical filament slots exposed by a Snapmaker U1 printer config,
/// or zero when the config is not for a U1. The U1 has four independently loaded toolheads.
std::size_t u1_physical_filament_capacity(const DynamicPrintConfig &printer_config);

/// Stores a U1 solid color in every parallel project-config representation used by rendering.
void set_u1_filament_color(DynamicPrintConfig &config, std::size_t index, const std::string &color);

/// True when the edited printer is a Snapmaker U1 with a LAN address configured.
bool u1_filament_sync_available();

/// Reads the loaded filaments from the U1's Moonraker and applies them to the plate's
/// filament slots: count, matching filament preset (by type, preferring the reported
/// vendor) and colour. Shows a summary and returns false when nothing was applied.
bool sync_filaments_from_u1(wxWindow *parent);

} // namespace Slic3r::GUI

#endif
