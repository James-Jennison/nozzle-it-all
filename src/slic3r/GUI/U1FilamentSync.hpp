#ifndef slic3r_GUI_U1FilamentSync_hpp_
#define slic3r_GUI_U1FilamentSync_hpp_

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <nlohmann/json_fwd.hpp>

class wxWindow;

namespace Slic3r {

class DynamicPrintConfig;
struct FilamentProfileRecord;
class Preset;

} // namespace Slic3r

namespace Slic3r::GUI {

struct U1ReportedFilament
{
    bool present{false};
    bool from_tag{false};
    std::string vendor;
    std::string type;
    std::string sub_type;
    std::string color;
    bool color_invalid{false};
};

enum class U1ReportSource { None, DirectLan, PrinterAgent, Conflict };

/// Selects one whole authoritative report without combining slot identity from
/// different devices. A same-device printer-agent report containing RFID
/// identity takes precedence over the direct LAN material summary. Conflicting
/// occupancy or identity from the same printer must be resolved by the user.
U1ReportSource u1_authoritative_report_source(const std::vector<U1ReportedFilament>& direct,
                                              const std::vector<U1ReportedFilament>& printer_agent,
                                              bool reports_same_printer);

/// Converts the U1 report arrays to fixed physical-slot records, including whether the printer
/// reports that identity as RFID/tag-derived (filament_official).
std::vector<U1ReportedFilament> parse_u1_filament_report(const nlohmann::json& report);

/// Converts the printer-agent U1 virtual-tray protocol ids 255..252 to physical
/// toolheads 0..3. Other or malformed ids are not U1 physical toolhead slots.
std::optional<std::size_t> u1_virtual_tray_toolhead_index(const std::string& tray_id);

/// Accepts a printer-agent rack only when it identifies one in-range physical
/// toolhead and contains exactly one loaded tray.
std::optional<std::size_t> u1_unambiguous_rack_toolhead_index(int extruder_id, std::size_t loaded_trays,
                                                              std::size_t toolhead_capacity = 4);

/// True only when printer-agent metadata or a U1-specific virtual-tray id
/// identifies the selected device as a Snapmaker U1. This prevents an
/// unrelated selected printer from supplying physical T0-T3 slot identity.
bool u1_printer_agent_identity_matches(const std::string& model_id, const std::string& display_name,
                                       const std::string& product_name, const std::vector<std::string>& tray_ids);

/// A loaded physical slot requires an explicit mapping; empty slots do not.
bool u1_all_loaded_slots_mapped(const std::vector<U1ReportedFilament>& slots, const std::vector<bool>& mapped);

/// Ranks a compatible same-type preset for a U1-reported vendor and subtype. Identity fields
/// precede visibility so an enabled unrelated brand cannot beat an exact or generic match.
std::uint64_t u1_filament_preset_rank(const Preset& preset, const std::string& vendor, const std::string& sub_type);

/// True when a preset is an exact reported-vendor candidate that may be selected automatically.
/// RFID-tagged Generic reports always require an explicit user choice.
bool u1_preset_matches_reported_identity(const Preset& preset, const U1ReportedFilament& reported);

/// True when keeping the current project slot is safe for a loaded U1
/// toolhead: the preset is compatible and its base material matches the
/// printer report. Missing or ambiguous report material requires a choice.
bool u1_current_preset_matches_reported_material(const Preset& preset, const U1ReportedFilament& reported);

/// True when a same-named preset has the catalog record's vendor and base material identity.
/// A name collision alone is not sufficient to reuse an unrelated slicing preset.
bool u1_catalog_preset_matches_record(const Preset& preset, const FilamentProfileRecord& record);

/// Returns the number of physical filament slots exposed by a Snapmaker U1 printer config,
/// or zero when the config is not for a U1. The U1 has four independently loaded toolheads.
std::size_t u1_physical_filament_capacity(const DynamicPrintConfig& printer_config);

/// Stores a U1 solid color in every parallel project-config representation used by rendering.
void set_u1_filament_color(DynamicPrintConfig& config, std::size_t index, const std::string& color);

/// Converts a printer-reported RGB or RGBA hexadecimal value to #RRGGBB.
/// Returns an empty string for malformed input so callers can preserve the current project color.
std::string normalize_u1_filament_color(const std::string& color);

/// True when the edited printer is a Snapmaker U1 reachable either through a configured
/// LAN address or through the currently selected printer-agent machine.
bool u1_filament_sync_available();

/// Reads loaded filaments from the U1's direct endpoint or selected printer-agent state,
/// then shows an explicit per-toolhead mapping review before applying count, preset,
/// RFID identity and colour. Returns false without project mutation when cancelled.
bool sync_filaments_from_u1(wxWindow* parent);

} // namespace Slic3r::GUI

#endif
