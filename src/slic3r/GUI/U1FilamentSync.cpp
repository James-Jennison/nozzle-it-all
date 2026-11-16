#include "U1FilamentSync.hpp"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <functional>
#include <future>
#include <iterator>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <boost/algorithm/string/case_conv.hpp>
#include <boost/algorithm/string/predicate.hpp>
#include <boost/algorithm/string/trim.hpp>
#include <boost/format.hpp>
#include <boost/log/trivial.hpp>
#include <nlohmann/json.hpp>
#include <wx/bitmap.h>
#include <wx/dcmemory.h>
#include <wx/image.h>
#include <wx/panel.h>
#include <wx/progdlg.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/stattext.h>
#include <wx/utils.h>
#include <wx/weakref.h>

#include "3DScene.hpp"
#include "CreatePresetsDialog.hpp"
#include "DeviceManager.hpp"
#include "DeviceCore/DevFilaSystem.h"
#include "DeviceCore/DevManager.h"
#include "FilamentBitmapUtils.hpp"
#include "GUI_App.hpp"
#include "GUI_Utils.hpp"
#include "I18N.hpp"
#include "MsgDialog.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "PartPlate.hpp"
#include "Plater.hpp"
#include "format.hpp"
#include "GUI.hpp"
#include "Tab.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/CheckBox.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/ProgressDialog.hpp"
#include "Widgets/TextInput.hpp"
#include "libslic3r/GCode/ThumbnailData.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/SpoolTags.hpp"
#include "slic3r/Utils/FilamentProfilesClient.hpp"
#include "slic3r/Utils/Http.hpp"
#include "slic3r/Utils/NetworkAgent.hpp"
#include "slic3r/Utils/NetworkAgentFactory.hpp"
#include "slic3r/Utils/U1LanPrintHost.hpp"

namespace Slic3r::GUI {

std::size_t u1_physical_filament_capacity(const DynamicPrintConfig& printer_config)
{
    const std::string model = printer_config.has("printer_model") ? printer_config.opt_string("printer_model") : std::string();
    return boost::iequals(model, "Snapmaker U1") ? 4u : 0u;
}

std::uint64_t u1_filament_preset_rank(const Preset& preset, const std::string& reported_vendor, const std::string& reported_sub_type)
{
    const std::string vendor        = boost::to_lower_copy(reported_vendor);
    const std::string sub_type      = boost::to_lower_copy(reported_sub_type);
    const std::string name          = boost::to_lower_copy(preset.name);
    const std::string preset_vendor = boost::to_lower_copy(preset.config.opt_string("filament_vendor", 0u));
    const bool vendor_known         = !vendor.empty() && vendor != "none";
    // Current profiles carry filament_vendor. Keep a name fallback only for older profiles that
    // do not, without allowing an explicitly different vendor to match by substring.
    auto contains_bounded = [](const std::string& value, const std::string& token) {
        for (size_t pos = value.find(token); pos != std::string::npos; pos = value.find(token, pos + 1)) {
            const bool left  = pos == 0 || !std::isalnum(static_cast<unsigned char>(value[pos - 1]));
            const size_t end = pos + token.size();
            const bool right = end == value.size() || !std::isalnum(static_cast<unsigned char>(value[end]));
            if (left && right)
                return true;
        }
        return false;
    };
    const bool vendor_match  = vendor_known && (preset_vendor == vendor || (preset_vendor.empty() && contains_bounded(name, vendor)));
    const bool subtype_match = !sub_type.empty() && sub_type != "none" && boost::contains(name, sub_type);
    const bool generic_match = boost::contains(name, "generic");

    std::uint64_t rank = vendor_match ? 1 : 0;
    rank               = rank * 2 + (generic_match ? 1 : 0);
    rank               = rank * 2 + (subtype_match ? 1 : 0);
    rank               = rank * 2 + (preset.is_system ? 1 : 0);
    rank               = rank * 2 + (preset.is_visible ? 1 : 0);
    return rank * 1000 + (999 - std::min<std::size_t>(name.size(), 999));
}

bool u1_preset_matches_reported_identity(const Preset& preset, const U1ReportedFilament& reported)
{
    const std::string preset_vendor = preset.config.opt_string("filament_vendor", 0u);
    if (!boost::iequals(preset_vendor, reported.vendor))
        return false;
    FilamentProfileRecord report_record;
    report_record.material      = reported.type;
    report_record.material_type = reported.sub_type;
    const std::string reported_material = filament_profile_base_material(report_record);
    if (reported_material.empty())
        return false;
    FilamentProfileRecord preset_record;
    preset_record.material = preset.config.opt_string("filament_type", 0u);
    if (!boost::iequals(filament_profile_base_material(preset_record), reported_material))
        return false;
    return !reported.from_tag || !boost::iequals(preset_vendor, "Generic");
}

bool u1_current_preset_matches_reported_material(const Preset& preset, const U1ReportedFilament& reported)
{
    FilamentProfileRecord report_record;
    report_record.material      = reported.type;
    report_record.material_type = reported.sub_type;
    const std::string reported_material = filament_profile_base_material(report_record);
    if (!preset.is_compatible || reported_material.empty())
        return false;

    FilamentProfileRecord preset_record;
    preset_record.material = preset.config.opt_string("filament_type", 0u);
    return boost::iequals(filament_profile_base_material(preset_record), reported_material);
}

void set_u1_filament_color(DynamicPrintConfig& config, std::size_t index, const std::string& color)
{
    auto set_value = [index](ConfigOptionStrings* option, const std::string& value, const std::string& fill) {
        if (option == nullptr)
            return;
        if (option->values.size() <= index)
            option->values.resize(index + 1, fill);
        option->values[index] = value;
    };
    set_value(config.option<ConfigOptionStrings>("filament_colour", true), color, "#FFFFFF");
    set_value(config.option<ConfigOptionStrings>("filament_multi_colour", true), color, "#FFFFFF");
    set_value(config.option<ConfigOptionStrings>("filament_colour_type", true), "1", "1");
}

std::string normalize_u1_filament_color(const std::string& reported_color)
{
    std::string color = reported_color;
    if (!color.empty() && color.front() == '#')
        color.erase(color.begin());
    if ((color.size() != 6 && color.size() != 8) ||
        !std::all_of(color.begin(), color.end(), [](unsigned char value) { return std::isxdigit(value) != 0; }))
        return {};
    // The alpha says how see-through the filament is (a U1 reports E3 for some spools); the project
    // color is the RGB part. Fully transparent is how "no color" is reported.
    if (color.size() == 8 && color.compare(6, 2, "00") == 0)
        return {};
    color.resize(6);
    boost::to_upper(color);
    return "#" + color;
}

namespace {

using Slot = U1ReportedFilament;

std::string json_string(const nlohmann::json& arr, size_t i)
{
    if (!arr.is_array() || i >= arr.size() || !arr[i].is_string())
        return {};
    return arr[i].get<std::string>();
}

std::string reported_color(const nlohmann::json& report, size_t i)
{
    std::string c = json_string(report.value("filament_color_rgba", nlohmann::json::array()), i);
    if (c.empty())
        c = json_string(report.value("filament_color", nlohmann::json::array()), i);
    return c;
}

bool json_flag(const nlohmann::json& arr, size_t i, bool fallback)
{
    if (!arr.is_array() || i >= arr.size())
        return fallback;
    if (arr[i].is_boolean())
        return arr[i].get<bool>();
    if (arr[i].is_number_integer())
        return arr[i].get<int>() != 0;
    return fallback;
}

// True while a printer with filament lanes on one nozzle (a CANVAS or other AFC unit) is being synced: the
// dialogs then speak of slots, not toolheads. Main thread only; the sync is modal.
bool s_lane_sync = false;

// Whether lane data has the form parse_lane_data_impl() reads: at least one entry that is an object naming
// its tool as a string. Data in another form is not "no filament": the sync says it cannot read it.
bool lane_data_is_recognised(const nlohmann::json& value)
{
    if (!value.is_object())
        return false;
    if (value.empty())
        return true; // no lanes at all: nothing to misread
    for (const auto& [name, lane] : value.items())
        if (lane.is_object() && lane.contains("lane") && lane["lane"].is_string())
            return true;
    return false;
}

std::vector<Slot> parse_lane_data_impl(const nlohmann::json& value)
{
    constexpr size_t capacity = 4;
    std::vector<Slot> slots(capacity);
    if (!value.is_object())
        return slots;
    std::vector<bool> seen(capacity, false);
    for (const auto& [name, lane] : value.items()) {
        if (!lane.is_object())
            continue;
        // "lane" is the tool the lane is mapped to, as a string ("0" for T0). A lane without one is not a slot
        // a sliced file can ask for.
        const auto tool_it = lane.find("lane");
        if (tool_it == lane.end() || !tool_it->is_string())
            continue;
        std::size_t consumed = 0;
        int         tool     = -1;
        try {
            tool = std::stoi(tool_it->get<std::string>(), &consumed);
        } catch (const std::exception&) {
            continue;
        }
        if (consumed != tool_it->get<std::string>().size() || tool < 0 || size_t(tool) >= capacity || seen[size_t(tool)])
            continue;
        seen[size_t(tool)] = true;
        const auto text = [&lane](const char* key) {
            const auto it = lane.find(key);
            return it != lane.end() && it->is_string() ? boost::trim_copy(it->get<std::string>()) : std::string();
        };
        Slot& s = slots[size_t(tool)];
        s.type  = boost::to_upper_copy(text("material"));
        const std::string raw_color = text("color");
        s.color         = normalize_u1_filament_color(raw_color);
        s.color_invalid = !raw_color.empty() && s.color.empty();
        // The brand is there only when the printer knows the spool (AFC fills "vendor_name" from Spoolman); it then
        // chooses among the presets of that material as a toolhead's reported brand does.
        s.vendor = text("vendor_name");
        // An unloaded lane has an empty material. Nothing here comes from a tag.
        s.present = !s.type.empty();
    }
    return slots;
}

std::vector<Slot> parse_report_impl(const nlohmann::json& report)
{
    constexpr size_t capacity = 4;
    std::vector<Slot> slots(capacity);
    const auto types = report.value("filament_type", nlohmann::json::array());
    const auto exist    = report.value("filament_exist", nlohmann::json::array());
    const auto official = report.value("filament_official", nlohmann::json::array());
    for (size_t i = 0; i < capacity; ++i) {
        Slot& s            = slots[i];
        s.type             = types.is_array() ? json_string(types, i) : std::string{};
        s.vendor           = json_string(report.value("filament_vendor", nlohmann::json::array()), i);
        s.sub_type         = json_string(report.value("filament_sub_type", nlohmann::json::array()), i);
        const std::string raw_color = reported_color(report, i);
        s.color            = normalize_u1_filament_color(raw_color);
        s.color_invalid    = !raw_color.empty() && s.color.empty();
        s.from_tag         = json_flag(official, i, false);
        s.tag_id           = SpoolTags::normalized(json_string(report.value("tag_ids", nlohmann::json::array()), i));
        // A missing or malformed presence flag is not evidence that a physical
        // toolhead is loaded.  Treat it as unresolved instead of allowing an
        // incomplete report to mutate the project.
        const bool flagged = json_flag(exist, i, false);
        // A positive physical-presence flag remains a loaded, unresolved
        // toolhead when older firmware omits the parallel type entry. The
        // mapping dialog must then require an explicit compatible choice
        // instead of silently shifting or discarding that physical slot.
        s.present          = flagged && !boost::iequals(s.type, "NONE");
    }
    return slots;
}

std::optional<std::size_t> virtual_tray_toolhead_index_impl(const std::string& tray_id)
{
    try {
        std::size_t consumed = 0;
        const int virtual_id = std::stoi(tray_id, &consumed);
        if (consumed != tray_id.size() || virtual_id < 252 || virtual_id > 255)
            return std::nullopt;
        // The Snapmaker U1 printer-agent protocol exposes its four virtual
        // trays in descending order: 255, 254, 253, 252 are T0, T1, T2, T3.
        return static_cast<std::size_t>(255 - virtual_id);
    } catch (const std::exception&) {
        return std::nullopt;
    }
}

bool has_tag_identity(const std::string& tag_uid)
{
    return std::any_of(tag_uid.begin(), tag_uid.end(), [](char value) { return value != '0' && value != ' '; });
}

std::string tray_color(const DevAmsTray& tray)
{
    return normalize_u1_filament_color(tray.color);
}

Slot reported_filament_from_tray(const DevAmsTray& tray, const PresetBundle& bundle)
{
    Slot slot;
    slot.present  = tray.is_exists && !tray.is_slot_placeholder;
    slot.from_tag = has_tag_identity(tray.tag_uid);
    slot.type     = tray.m_fila_type;
    slot.sub_type = tray.sub_brands;
    slot.color    = tray_color(tray);
    slot.color_invalid = !tray.color.empty() && slot.color.empty();

    if (const auto info = bundle.get_filament_by_filament_id(tray.setting_id)) {
        slot.vendor = info->vendor;
        if (slot.type.empty())
            slot.type = info->filament_type;
        if (slot.sub_type.empty() && !info->filament_name.empty() && !boost::iequals(info->filament_name, info->vendor))
            slot.sub_type = info->filament_name;
    }
    if (slot.vendor.empty())
        slot.vendor = tray.sub_brands;
    return slot;
}

std::vector<Slot> selected_machine_report(PresetBundle& bundle, MachineObject& machine, bool& ambiguous)
{
    ambiguous = false;
    std::vector<Slot> slots(4);

    bool found = false;
    for (const DevAmsTray& tray : machine.vt_slot) {
        const std::optional<std::size_t> index = virtual_tray_toolhead_index_impl(tray.id);
        if (!index || *index >= slots.size())
            continue;
        slots[*index] = reported_filament_from_tray(tray, bundle);
        found         = found || slots[*index].present;
    }

    // Some printer agents expose U1 toolheads as one tray per filament system rack
    // instead of virtual trays. Preserve the rack's physical extruder index.
    if (!found && machine.GetFilaSystem() != nullptr) {
        for (const auto& [ams_id, ams] : machine.GetFilaSystem()->GetAmsList()) {
            if (ams == nullptr)
                continue;
            const int index = ams->GetExtruderId();
            const DevAmsTray* loaded = nullptr;
            size_t loaded_count = 0;
            for (const auto& [tray_id, tray] : ams->GetTrays()) {
                if (tray == nullptr || !tray->is_exists || tray->is_slot_placeholder)
                    continue;
                loaded = tray;
                ++loaded_count;
            }
            const std::optional<size_t> physical = u1_unambiguous_rack_toolhead_index(index, loaded_count, slots.size());
            if (loaded_count != 0 && (!physical || slots[*physical].present)) {
                ambiguous = true;
                return {};
            }
            if (!physical || loaded == nullptr)
                continue;
            slots[*physical] = reported_filament_from_tray(*loaded, bundle);
            found            = true;
        }
    }
    return found ? slots : std::vector<Slot>{};
}

bool all_loaded_slots_mapped(const std::vector<Slot>& slots, const std::vector<bool>& mapped)
{
    for (size_t i = 0; i < slots.size(); ++i)
        if (slots[i].present && (i >= mapped.size() || !mapped[i]))
            return false;
    return true;
}

std::string preset_base_name(const std::string& name)
{
    const size_t pos = name.find(" @");
    return pos == std::string::npos ? name : name.substr(0, pos);
}

const Preset* preset_named(PresetBundle& bundle, const std::string& name)
{
    for (const Preset& preset : bundle.filaments.get_presets())
        if (preset.name == name)
            return &preset;
    return nullptr;
}

std::string canonical_material(const std::string& type, const std::string& sub_type = {})
{
    FilamentProfileRecord record;
    record.material      = type;
    record.material_type = sub_type;
    return filament_profile_base_material(record);
}

struct CatalogPresetResult
{
    std::string preset_name;
    std::vector<std::string> created_preset_names;
};

// *from_search*: the record was picked in a catalogue search that no printer is part of (find_filament_in_catalog()),
// so the preset's notes do not say what a printer reported.
CatalogPresetResult catalog_preset(PresetBundle& bundle, const FilamentProfileRecord& record, const Slot& reported,
                                   std::vector<std::string>& transaction_created_presets, std::string& error, bool from_search = false)
{
    const std::string name     = filament_profile_product_name(record);
    const std::string material = filament_profile_base_material(record);
    if (material.empty()) {
        error = "The catalog product names multiple base material families and cannot be mapped safely.";
        return {};
    }
    for (const Preset& preset : bundle.filaments.get_presets())
        if (preset.is_compatible && (preset.is_system || preset.is_user()) && boost::iequals(preset_base_name(preset.name), name) &&
            u1_catalog_preset_matches_record(preset, record))
            return {preset.name, {}};

    std::string unique_name = name;
    const Preset* const selected_printer      = bundle.printers.get_preset_base(bundle.printers.get_edited_preset());
    const std::string   selected_printer_name = selected_printer ? selected_printer->name : bundle.printers.get_edited_preset().name;
    // A preset made for a brand a filament changer reports is named "<brand> <material> @<printer>": the same
    // filament made earlier for another printer is not a clash, only one for this printer is.
    const bool brand_only = s_lane_sync && record.id.empty();
    const auto name_exists = [&](const std::string& candidate) {
        return std::any_of(bundle.filaments.get_presets().begin(), bundle.filaments.get_presets().end(), [&](const Preset& preset) {
            return brand_only ? boost::iequals(preset.name, candidate + " @" + selected_printer_name) : boost::iequals(preset_base_name(preset.name), candidate);
        });
    };
    if (name_exists(unique_name)) {
        std::string catalog_id;
        std::copy_if(record.id.begin(), record.id.end(), std::back_inserter(catalog_id),
                     [](unsigned char ch) { return std::isalnum(ch) || ch == '-' || ch == '_'; });
        if (catalog_id.empty())
            catalog_id = "catalog";
        const std::string disambiguated = name + " (3DFP " + catalog_id + ")";
        unique_name = disambiguated;
        for (size_t suffix = 2; name_exists(unique_name); ++suffix)
            unique_name = disambiguated + " " + std::to_string(suffix);
    }
    const Preset* base         = nullptr;
    for (const Preset& preset : bundle.filaments.get_presets())
        if (preset.is_system && preset.is_compatible && boost::iequals(preset_base_name(preset.name), "Generic " + material)) {
            base = &preset;
            break;
        }
    if (base == nullptr) {
        error = "No compatible Generic " + material + " preset is available.";
        return {};
    }

    const Preset* printer          = bundle.printers.get_preset_base(bundle.printers.get_edited_preset());
    const std::string printer_name = printer ? printer->name : bundle.printers.get_edited_preset().name;
    const std::string source       = record.website.empty() ? "https://3dfilamentprofiles.com/" : record.website;
    std::string notes              = "Source: 3D Filament Profiles (" + source + ")";
    if (!record.id.empty())
        notes += "; record " + record.id;
    if (from_search)
        notes += "; found by a search in the app, not reported by a printer";
    else
        notes += "; Snapmaker U1 reported " + (reported.from_tag ? std::string("RFID") : std::string("printer setting"));
    // A preset made for a brand a filament changer's lane reports: its settings are the generic ones of the
    // material; only the brand comes from the printer.
    if (s_lane_sync && record.id.empty())
        notes = "Made from the generic preset for a brand the printer's filament changer reports";
    if (!reported.vendor.empty())
        notes += "; vendor " + reported.vendor;
    if (!reported.type.empty())
        notes += "; material " + reported.type;
    if (!reported.sub_type.empty())
        notes += "; subtype " + reported.sub_type;
    if (!reported.color.empty())
        notes += "; color " + reported.color;

    DynamicConfig overrides;
    overrides.set_key_value("filament_vendor", new ConfigOptionStrings({record.brand.empty() ? "Generic" : record.brand}));
    overrides.set_key_value("filament_type", new ConfigOptionStrings({material}));
    overrides.set_key_value("filament_notes", new ConfigOptionStrings({notes}));
    overrides.set_key_value("compatible_printers", new ConfigOptionStrings({printer_name}));
    std::set<std::string> existing_user_presets;
    for (const Preset& preset : bundle.filaments.get_presets())
        if (preset.is_user())
            existing_user_presets.insert(preset.name);

    std::vector<std::string> failures;
    if (!bundle.filaments.clone_presets_for_filament(base, failures, unique_name, new_user_filament_id(unique_name), overrides, printer_name)) {
        // Returning an empty result is a transaction failure: the caller must
        // throw and roll back every preset registered in
        // transaction_created_presets. Do not add a non-throwing fallback
        // here without separating per-attempt cleanup from transaction cleanup.
        // A failed multi-clone may nevertheless have written entries. Remove
        // only the entries introduced by this attempt, never an older preset.
        std::vector<std::string> partial_names;
        for (const Preset& existing : bundle.filaments.get_presets()) {
            if (existing.is_user() && existing_user_presets.count(existing.name) == 0)
                partial_names.push_back(existing.name);
        }
        transaction_created_presets.insert(transaction_created_presets.end(), partial_names.begin(), partial_names.end());
        for (auto it = partial_names.rbegin(); it != partial_names.rend(); ++it) {
            bundle.filaments.delete_preset(*it, true);
            transaction_created_presets.erase(
                std::remove(transaction_created_presets.begin(), transaction_created_presets.end(), *it),
                transaction_created_presets.end());
        }
        error = failures.empty() ? "The catalog preset could not be saved." : "The catalog preset could not be saved: " + failures.front();
        return {};
    }
    std::vector<std::string> created_preset_names;
    for (const Preset& preset : bundle.filaments.get_presets())
        if (preset.is_user() && existing_user_presets.count(preset.name) == 0)
            created_preset_names.push_back(preset.name);
    // Register every persisted preset with the outer transaction before any
    // later operation can throw. The caller can then compensate even when a
    // compatibility refresh fails after clone_presets_for_filament writes.
    transaction_created_presets.insert(transaction_created_presets.end(), created_preset_names.begin(),
                                       created_preset_names.end());
    bundle.update_compatible(PresetSelectCompatibleType::Always);
    for (const Preset& preset : bundle.filaments.get_presets())
        if (preset.is_user() && preset.is_compatible && existing_user_presets.count(preset.name) == 0 &&
            boost::iequals(preset_base_name(preset.name), unique_name))
            return {preset.name, std::move(created_preset_names)};
    error = "The catalog preset was saved but could not be selected.";
    for (auto it = created_preset_names.rbegin(); it != created_preset_names.rend(); ++it) {
        bundle.filaments.delete_preset(*it, true);
        transaction_created_presets.erase(
            std::remove(transaction_created_presets.begin(), transaction_created_presets.end(), *it),
            transaction_created_presets.end());
    }
    return {};
}

struct MappingChoice
{
    std::string preset_name;
    std::optional<FilamentProfileRecord> catalog;
    bool keep_current{false};
};

struct MappingRow
{
    ::ComboBox* control{nullptr};
    std::vector<MappingChoice> choices;
};

class FilamentProfilesKeyDialog final : public DPIDialog
{
public:
    explicit FilamentProfilesKeyDialog(wxWindow* parent) : DPIDialog(parent, wxID_ANY, _L("3D Filament Profiles API key"))
    {
        std::string existing_key = load_filament_profiles_api_key();
        m_replacing = !existing_key.empty();
        std::fill(existing_key.begin(), existing_key.end(), '\0');
        const int em = wxGetApp().em_unit();
        const bool dark = wxGetApp().dark_mode();
        SetBackgroundColour(NIATheme::surface(dark));
        auto* root   = new wxBoxSizer(wxVERTICAL);
        auto* heading = new wxStaticText(this, wxID_ANY, _L("Connect 3D Filament Profiles"));
        heading->SetFont(Label::Head_18);
        heading->SetForegroundColour(NIATheme::text(dark));
        root->Add(heading, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, em);
        auto* explanation = new wxStaticText(this, wxID_ANY,
            m_replacing
                ? _L("A 3D Filament Profiles API key is already stored in the operating-system keychain. Enter a new key only to replace it.")
                : _L("Enter your personal API key. It will be stored only in the operating-system keychain."));
        explanation->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(explanation, 0, wxALL | wxEXPAND, em);
        m_key = new ::TextInput(this, "", "", "", wxDefaultPosition, wxSize(42 * em, -1), wxTE_PASSWORD);
        root->Add(m_key, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);
        auto* buttons = new DialogButtons(this, {"Cancel", "Save securely"}, _L("Save securely"));
        buttons->GetLAST()->SetId(wxID_OK);
        root->Add(buttons, 0, wxALL | wxALIGN_RIGHT, em);
        SetSizerAndFit(root);
        wxGetApp().UpdateDlgDarkUI(this);
        Bind(
            wxEVT_BUTTON,
            [this](wxCommandEvent&) {
                std::string key(m_key->GetTextCtrl()->GetValue().utf8_str());
                if (key.empty()) {
                    MessageDialog(this, _L("Enter an API key before saving."), _L("3D Filament Profiles"), wxOK | wxICON_WARNING).ShowModal();
                    return;
                }
                if (m_replacing) {
                    wxMessageDialog replace(
                        this,
                        _L("A 3D Filament Profiles API key is already stored. Replace it with the new key you entered?"),
                        _L("Replace stored API key?"), wxYES_NO | wxNO_DEFAULT | wxICON_WARNING);
                    if (replace.ShowModal() != wxID_YES) {
                        std::fill(key.begin(), key.end(), '\0');
                        return;
                    }
                }
                if (!save_filament_profiles_api_key(key)) {
                    std::fill(key.begin(), key.end(), '\0');
                    m_key->GetTextCtrl()->Clear();
                    MessageDialog(this, _L("The API key could not be stored in the operating-system keychain."), _L("3D Filament Profiles"),
                                  wxOK | wxICON_ERROR)
                        .ShowModal();
                    return;
                }
                std::fill(key.begin(), key.end(), '\0');
                m_key->GetTextCtrl()->Clear();
                EndModal(wxID_OK);
            },
            wxID_OK);
        Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { EndModal(wxID_CANCEL); }, wxID_CANCEL);
        Bind(wxEVT_CLOSE_WINDOW, [this](wxCloseEvent&) { EndModal(wxID_CANCEL); });
    }

    ~FilamentProfilesKeyDialog() override
    {
        if (m_key != nullptr)
            m_key->GetTextCtrl()->Clear();
    }

private:
    void on_dpi_changed(const wxRect&) override
    {
        m_key->Rescale();
        if (GetSizer() != nullptr)
            GetSizer()->SetSizeHints(this);
        Fit();
        Refresh();
    }

    ::TextInput* m_key{nullptr};
    bool m_replacing{false};
};

// A search of the 3D Filament Profiles catalogue that no printer is part of: the person types what they look
// for, picks a result, and it becomes a filament preset of their own for the selected printer, made the same
// way the filament sync makes one (catalog_preset). Only the search request leaves this computer, and only
// when Search is pressed; the key is the person's own, from the system keychain.
class FilamentCatalogSearchDialog final : public DPIDialog
{
public:
    explicit FilamentCatalogSearchDialog(wxWindow* parent)
        : DPIDialog(parent, wxID_ANY, _L("Find a filament"), wxDefaultPosition, wxDefaultSize,
                    wxCAPTION | wxCLOSE_BOX)
        , m_alive(std::make_shared<std::atomic<bool>>(true))
    {
        const int  em   = wxGetApp().em_unit();
        const bool dark = wxGetApp().dark_mode();
        SetBackgroundColour(NIATheme::surface(dark));
        auto* root  = new wxBoxSizer(wxVERTICAL);
        auto* intro = new Label(this, _L("Searches 3dfilamentprofiles.com for a filament by brand, material or name, with your own API key. The one you "
                                         "pick becomes a filament preset of your own for the selected printer, with the settings of that material's "
                                         "generic preset; only its name and brand come from the catalogue."),
                                LB_AUTO_WRAP, wxSize(46 * em, -1));
        intro->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(intro, 0, wxALL | wxEXPAND, em);

        auto* search_row = new wxBoxSizer(wxHORIZONTAL);
        m_query          = new ::TextInput(this, "", "", "", wxDefaultPosition, wxSize(30 * em, -1), wxTE_PROCESS_ENTER);
        m_search         = new ::Button(this, _L("Search"));
        m_search->SetStyle(ButtonStyle::Confirm, ButtonType::Window);
        auto* key = new ::Button(this, _L("Configure API key"));
        key->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        search_row->Add(m_query, 1, wxALIGN_CENTER_VERTICAL | wxRIGHT, em / 2);
        search_row->Add(m_search, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, em / 2);
        search_row->Add(key, 0, wxALIGN_CENTER_VERTICAL);
        root->Add(search_row, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);

        m_results = new ::ComboBox(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(46 * em, -1), 0, nullptr, wxCB_READONLY);
        m_results->Disable();
        root->Add(m_results, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);
        m_status = new Label(this, wxEmptyString, LB_AUTO_WRAP, wxSize(46 * em, -1));
        m_status->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(m_status, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);

        auto* buttons = new DialogButtons(this, {"Add to my filaments", "Close"}, _L("Add to my filaments"));
        m_add         = buttons->GetButtonFromIndex(0);
        m_add->Disable();
        root->Add(buttons, 0, wxEXPAND);

        m_search->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { search(); });
        m_query->Bind(wxEVT_TEXT_ENTER, [this](wxCommandEvent&) { search(); });
        key->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { FilamentProfilesKeyDialog(this).ShowModal(); });
        m_results->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent& event) {
            m_add->Enable(m_results->GetSelection() >= 0);
            event.Skip();
        });
        m_add->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { add(); });
        buttons->GetButtonFromIndex(1)->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { EndModal(m_added.empty() ? wxID_CANCEL : wxID_OK); });
        Bind(wxEVT_CLOSE_WINDOW, [this](wxCloseEvent&) { EndModal(m_added.empty() ? wxID_CANCEL : wxID_OK); });

        SetSizerAndFit(root);
        CenterOnParent();
        wxGetApp().UpdateDlgDarkUI(this);
        m_query->GetTextCtrl()->SetFocus(); // what is typed first is what to look for
    }

    ~FilamentCatalogSearchDialog() override
    {
        m_alive->store(false);
        if (m_request)
            m_request->cancel();
    }

    /// The presets made while the dialog was open.
    const std::vector<std::string>& added() const { return m_added; }

private:
    void on_dpi_changed(const wxRect&) override
    {
        if (GetSizer() != nullptr)
            GetSizer()->SetSizeHints(this);
        Refresh();
    }

    void say(const wxString& text)
    {
        m_status->SetLabel(text);
        Layout();
        Fit();
    }

    void search()
    {
        const std::string query = into_u8(m_query->GetTextCtrl()->GetValue().Trim().Trim(false));
        if (query.size() < 2) {
            say(_L("Type at least two letters of a brand, a material or a name."));
            return;
        }
        // The key is read for this one request and overwritten once the request has taken its copy.
        std::string api_key = load_filament_profiles_api_key();
        if (api_key.empty()) {
            BOOST_LOG_TRIVIAL(warning) << "Filament catalogue: no API key is stored, so nothing was asked";
            say(_L("No API key is stored yet. Press Configure API key and enter your own key from 3dfilamentprofiles.com."));
            return;
        }
        if (m_request)
            m_request->cancel();
        const unsigned generation = ++m_generation;
        m_search->Disable();
        m_add->Disable();
        // What the last search found is not left to be picked while the next one is under way.
        m_records.clear();
        m_results->Clear();
        m_results->Disable();
        say(_L("Searching…"));
        auto* dialog = this;
        m_request    = search_filament_profiles_async(query, api_key, [dialog, alive = m_alive, generation](FilamentProfilesSearchReply reply) {
            // On the request's thread: the answer is handed to the main thread, which checks the dialog is still there.
            wxGetApp().CallAfter([dialog, alive, generation, reply = std::move(reply)] {
                if (alive->load() && generation == dialog->m_generation)
                    dialog->took(reply);
            });
        });
        std::fill(api_key.begin(), api_key.end(), '\0');
    }

    void took(const FilamentProfilesSearchReply& reply)
    {
        m_search->Enable();
        m_records.clear();
        m_results->Clear();
        if (!reply.ok) {
            m_results->Disable();
            // TRN %1% is what the catalogue's server or the network said.
            say(format_wxstr(_L("The search did not succeed: %1%"), from_u8(reply.error)));
            return;
        }
        for (const FilamentProfileRecord& record : reply.records)
            if (filament_profile_record_is_usable(record)) {
                m_records.push_back(record);
                wxString line = from_u8(filament_profile_product_name(record));
                if (!record.color.empty())
                    line += wxString::FromUTF8(" · ") + from_u8(record.color);
                m_results->Append(line);
            }
        m_results->Enable(!m_records.empty());
        // TRN %1% is a number of filaments found.
        say(m_records.empty() ? _L("Nothing usable was found. Try other words.") :
                                format_wxstr(_L_PLURAL("%1% filament found. Choose one in the list.", "%1% filaments found. Choose one in the list.", m_records.size()),
                                             m_records.size()));
    }

    void add()
    {
        const int at = m_results->GetSelection();
        if (at < 0 || size_t(at) >= m_records.size())
            return;
        PresetBundle&            bundle = *wxGetApp().preset_bundle;
        std::vector<std::string> created;
        std::string              error;
        Slot                     none;
        const CatalogPresetResult result = catalog_preset(bundle, m_records[size_t(at)], none, created, error, true);
        if (result.preset_name.empty()) {
            // TRN %1% is the reason, in English as the preset code gives it.
            say(format_wxstr(_L("The preset could not be made: %1%"), from_u8(error)));
            return;
        }
        if (wxGetApp().plater() != nullptr)
            wxGetApp().plater()->sidebar().update_presets(Preset::TYPE_FILAMENT);
        BOOST_LOG_TRIVIAL(warning) << "Filament catalogue: preset \"" << result.preset_name << "\" " << (result.created_preset_names.empty() ? "was already there" : "made");
        if (!result.created_preset_names.empty())
            m_added.push_back(result.preset_name);
        // TRN %1% is the name of a filament preset.
        say(result.created_preset_names.empty() ? format_wxstr(_L("%1% is already among your filaments for this printer."), from_u8(result.preset_name)) :
                                                  format_wxstr(_L("%1% was added to your filaments for this printer. Choose it in a filament's list."), from_u8(result.preset_name)));
    }

    ::TextInput*                       m_query{nullptr};
    ::Button*                          m_search{nullptr};
    ::Button*                          m_add{nullptr};
    ::ComboBox*                        m_results{nullptr};
    ::Label*                           m_status{nullptr};
    std::vector<FilamentProfileRecord> m_records;
    std::vector<std::string>           m_added;
    Http::Ptr                          m_request;
    unsigned                           m_generation{0};
    std::shared_ptr<std::atomic<bool>> m_alive;
};

// The preset a loaded toolhead gets without asking: the highest ranked compatible preset of the
// reported material (see u1_filament_preset_rank). Empty when the material is not recognised or no
// compatible preset has it; such a toolhead is mapped by hand.
std::string automatic_preset(const PresetBundle& bundle, const Slot& slot)
{
    const std::string reported_material = canonical_material(slot.type, slot.sub_type);
    if (reported_material.empty())
        return {};
    const Preset* best = nullptr;
    std::uint64_t best_rank = 0;
    bool          best_exact = false;
    size_t        best_plain = 0;
    for (const Preset& preset : bundle.filaments.get_presets()) {
        const std::string preset_type = preset.config.opt_string("filament_type", 0u);
        if (!preset.is_compatible || preset.is_default || !boost::iequals(canonical_material(preset_type), reported_material))
            continue;
        // A report that names only the family ("PLA", no subtype) is that plain material: a preset of exactly
        // that type comes before a filled or blended one of the same family ("PLA-CF").
        const bool          exact = slot.sub_type.empty() && boost::iequals(preset_type, slot.type);
        const std::uint64_t rank  = u1_filament_preset_rank(preset, slot.vendor, slot.sub_type);
        // Among those, the plainest name: "Generic PLA @Printer" before "Generic PLA Silk @System". The rank's
        // last three digits only prefer a short full name, which the "@..." part would decide.
        const size_t plain = slot.sub_type.empty() ? preset.name.substr(0, preset.name.find(" @")).size() : 0;
        const auto   better = [&] {
            if (exact != best_exact)
                return exact;
            if (rank / 1000 != best_rank / 1000)
                return rank / 1000 > best_rank / 1000;
            if (plain != best_plain)
                return plain < best_plain;
            return rank > best_rank;
        };
        if (best == nullptr || better()) {
            best       = &preset;
            best_rank  = rank;
            best_exact = exact;
            best_plain = plain;
        }
    }
    return best != nullptr ? best->name : std::string();
}

// What the user chose in U1SyncDialog.
struct SyncChoice
{
    // Mapping keeps the project's filaments and gives each the type and color of a toolhead;
    // overwriting replaces the project's filaments with the four toolheads.
    bool mapping{true};
    // Per physical project filament: the toolhead it takes its type and color from, -1 to leave it.
    std::vector<int> toolhead_for_filament;
    bool add_unused{false};
    bool merge_same{true};
    bool choose_presets{false};
};

// The project slots that hold ordinary filaments, in order. Mixed filaments normally follow them, but a
// project can list one in between (a U1 project reopened with fewer filaments than toolheads is topped
// up behind its mixed filament), so a filament is always addressed by its own slot, never by counting.
std::vector<size_t> physical_slots(const PresetBundle& bundle)
{
    const auto* colours = bundle.project_config.option<ConfigOptionStrings>("filament_colour");
    const size_t total  = std::min(bundle.filament_presets.size(), colours != nullptr ? colours->values.size() : size_t(0));
    std::vector<size_t> result;
    for (size_t i = 0; i < total; ++i)
        if (!bundle.is_mixed_filament(i))
            result.push_back(i);
    return result;
}

wxColour colour_from_hex(const std::string& hex, const wxColour& fallback)
{
    wxColour colour(from_u8(hex.size() > 7 ? hex.substr(0, 7) : hex));
    return colour.IsOk() ? colour : fallback;
}

// The color the 3D scene draws a filament color with; the flat plate picture holds these.
wxColour scene_colour(const wxColour& colour)
{
    const ColorRGBA adjusted = adjust_color_for_rendering(
        ColorRGBA{colour.Red() / 255.0f, colour.Green() / 255.0f, colour.Blue() / 255.0f, 1.0f});
    return wxColour(int(adjusted[0] * 255.0f), int(adjusted[1] * 255.0f), int(adjusted[2] * 255.0f));
}

// The plate picture with filaments recolored, as OrcaSlicer's own sync dialog draws it: `flat` is the
// same view without lighting, with 255 minus the filament index in its alpha; each lit pixel keeps its
// shading and takes the new color of its filament. Pixels blended across two filaments (edges) take the
// average of their recolored neighbors.
wxImage recolored_plate(const ThumbnailData& lit, const ThumbnailData& flat, const std::vector<wxColour>& before,
                        const std::vector<wxColour>& after)
{
    if (!lit.is_valid() || lit.width == 0 || lit.height == 0)
        return wxImage();
    const unsigned w = lit.width, h = lit.height;
    const bool recolor = flat.is_valid() && flat.width == w && flat.height == h && before.size() == after.size();
    std::vector<unsigned char> out(lit.pixels.begin(), lit.pixels.end());
    std::vector<char>          edge(size_t(w) * h, 0);
    if (recolor) {
        std::vector<wxColour> flat_before, flat_after;
        for (const wxColour& colour : before)
            flat_before.push_back(scene_colour(colour));
        for (const wxColour& colour : after)
            flat_after.push_back(scene_colour(colour));
        for (size_t at = 0; at < size_t(w) * h; ++at) {
            const unsigned char* lit_px  = lit.pixels.data() + 4 * at;
            const unsigned char* flat_px = flat.pixels.data() + 4 * at;
            if (lit_px[3] == 0)
                continue;
            const bool plain = std::any_of(flat_before.begin(), flat_before.end(), [flat_px](const wxColour& colour) {
                return colour.Red() == flat_px[0] && colour.Green() == flat_px[1] && colour.Blue() == flat_px[2];
            });
            if (!plain) {
                edge[at] = 1;
                continue;
            }
            const size_t filament = size_t(255 - flat_px[3]);
            if (filament >= flat_after.size() || before[filament] == after[filament])
                continue;
            const wxColour& to       = flat_after[filament];
            const int       lit_sum  = lit_px[0] + lit_px[1] + lit_px[2];
            const int       flat_sum = flat_px[0] + flat_px[1] + flat_px[2];
            const int       target[3] = {to.Red(), to.Green(), to.Blue()};
            for (int channel = 0; channel < 3; ++channel)
                out[4 * at + channel] = static_cast<unsigned char>(
                    lit_sum >= flat_sum || flat_sum == 0 ? std::clamp(target[channel] + (lit_px[channel] - flat_px[channel]), 0, 255) :
                                                           std::clamp(int(target[channel] * (lit_sum / float(flat_sum))), 0, 255));
        }
        for (unsigned row = 0; row < h; ++row)
            for (unsigned column = 0; column < w; ++column) {
                const size_t at = size_t(row) * w + column;
                if (!edge[at])
                    continue;
                int sum[3] = {0, 0, 0}, count = 0;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int y = int(row) + dy, x = int(column) + dx;
                        if ((dx == 0 && dy == 0) || y < 0 || x < 0 || y >= int(h) || x >= int(w))
                            continue;
                        const size_t near = size_t(y) * w + size_t(x);
                        if (edge[near] || lit.pixels[4 * near + 3] == 0)
                            continue;
                        for (int channel = 0; channel < 3; ++channel)
                            sum[channel] += out[4 * near + channel];
                        ++count;
                    }
                if (count > 0)
                    for (int channel = 0; channel < 3; ++channel)
                        out[4 * at + channel] = static_cast<unsigned char>(sum[channel] / count);
            }
    }
    wxImage image;
    image.Create(int(w), int(h));
    image.InitAlpha();
    for (unsigned row = 0; row < h; ++row) {
        const size_t source = size_t(h - 1 - row) * w; // the picture is stored bottom row first
        for (unsigned column = 0; column < w; ++column) {
            const unsigned char* px = out.data() + 4 * (source + column);
            image.SetRGB(int(column), int(row), px[0], px[1], px[2]);
            image.SetAlpha(int(column), int(row), px[3]);
        }
    }
    return image;
}

// Asks before anything changes, as OrcaSlicer's "Synchronize AMS Filament Information" does: mapping or
// overwriting, a toolhead per project filament, and the plate before and after.
class U1SyncDialog final : public DPIDialog
{
public:
    // *to_make* names, per toolhead, a preset the sync will make for it (empty: none).
    U1SyncDialog(wxWindow* parent, const std::vector<Slot>& slots, const std::vector<std::string>& automatic, PresetBundle& bundle,
                 const std::vector<std::string>& to_make = {})
        : DPIDialog(wxGetTopLevelParent(parent), wxID_ANY, _L("Synchronize filaments from the printer"),
                    wxDefaultPosition, wxDefaultSize, wxCAPTION | wxCLOSE_BOX)
        , m_slots(slots)
        , m_bundle(bundle)
    {
        const int  em   = wxGetApp().em_unit();
        const bool dark = wxGetApp().dark_mode();
        SetBackgroundColour(NIATheme::background(dark));
        SetFont(Label::Body_14);

        // The project's physical filaments and their colors; mixed filaments follow them and are not mapped.
        m_index    = physical_slots(bundle);
        m_physical = m_index.size();
        const auto* colours = bundle.project_config.option<ConfigOptionStrings>("filament_colour");
        for (size_t i = 0; i < bundle.filament_presets.size(); ++i)
            m_before.push_back(colour_from_hex(colours != nullptr && i < colours->values.size() ? colours->values[i] : std::string(), *wxWHITE));
        recompute_mixed_slot_colors(m_before, bundle.project_config);

        auto* root = new wxBoxSizer(wxVERTICAL);

        auto* modes  = new wxBoxSizer(wxHORIZONTAL);
        m_mapping    = new ::Button(this, _L("Mapping"));
        m_overwrite  = new ::Button(this, _L("Overwriting"));
        m_mapping->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { set_mode(true); });
        m_overwrite->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { set_mode(false); });
        modes->AddStretchSpacer();
        modes->Add(m_mapping, 0, wxRIGHT, em);
        modes->Add(m_overwrite);
        modes->AddStretchSpacer();
        root->Add(modes, 0, wxEXPAND | wxALL, em);

        // Mapping: one column per project filament, the toolhead it takes its type and color from below it.
        m_map_panel = new wxPanel(this);
        m_map_panel->SetBackgroundColour(GetBackgroundColour());
        auto* grid = new wxFlexGridSizer(2, int(m_physical) + 1, em / 2, em / 2);
        grid->Add(text(m_map_panel, _L("Project:"), true), 0, wxALIGN_CENTER_VERTICAL);
        for (size_t i = 0; i < m_physical; ++i) {
            const Preset* preset = preset_named(bundle, bundle.filament_presets[m_index[i]]);
            const wxString type  = preset != nullptr ? from_u8(preset->config.opt_string("filament_type", 0u)) : wxString();
            grid->Add(chip(m_map_panel, wxString::Format("%d %s", int(m_index[i] + 1), type), m_before[m_index[i]]), 0, wxEXPAND);
        }
        grid->Add(text(m_map_panel, (s_lane_sync ? _L("Slot:") : _L("Toolhead:")), true), 0, wxALIGN_CENTER_VERTICAL);
        for (size_t i = 0; i < m_physical; ++i) {
            auto* combo = new ::ComboBox(m_map_panel, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(9 * em, -1), 0, nullptr, wxCB_READONLY);
            // TRN first choice in a list of toolheads: this project filament keeps its type and color
            combo->Append(_L("Leave as is"));
            std::vector<int> toolheads{-1};
            for (size_t t = 0; t < m_slots.size(); ++t)
                if (m_slots[t].present) {
                    combo->Append(wxString::Format("T%d %s", int(t), from_u8(m_slots[t].type)), swatch(slot_colour(t)));
                    toolheads.push_back(int(t));
                }
            const int best = best_toolhead(i);
            const auto at  = std::find(toolheads.begin(), toolheads.end(), best);
            combo->SetSelection(at != toolheads.end() ? int(at - toolheads.begin()) : 0);
            combo->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent& event) {
                update_pictures();
                event.Skip();
            });
            grid->Add(combo, 0, wxEXPAND);
            m_combos.push_back(combo);
            m_combo_toolheads.push_back(std::move(toolheads));
        }
        m_map_panel->SetSizer(grid);
        root->Add(m_map_panel, 0, wxLEFT | wxRIGHT | wxBOTTOM, em);

        // Overwriting: the four toolheads as the printer reports them, and the preset each one gets.
        m_over_panel = new wxPanel(this);
        m_over_panel->SetBackgroundColour(GetBackgroundColour());
        auto* over = new wxBoxSizer(wxHORIZONTAL);
        over->Add(text(m_over_panel, _L("Printer:"), true), 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, em / 2);
        for (size_t t = 0; t < m_slots.size(); ++t)
            over->Add(chip(m_over_panel,
                           m_slots[t].present ? wxString::Format("T%d %s", int(t), from_u8(m_slots[t].type)) :
                                                // TRN %d is a toolhead number
                                                wxString::Format(_L("T%d empty"), int(t)),
                           m_slots[t].present ? slot_colour(t) : NIATheme::surface_raised(dark)),
                      0, wxRIGHT, em / 2);
        m_over_panel->SetSizer(over);
        root->Add(m_over_panel, 0, wxLEFT | wxRIGHT | wxBOTTOM, em);

        m_tip = new wxStaticText(this, wxID_ANY, wxEmptyString);
        m_tip->SetForegroundColour(NIATheme::text_muted(dark));
        m_tip->SetMinSize(wxSize(52 * em, -1));
        m_tip->SetMaxSize(wxSize(52 * em, -1));
        root->Add(m_tip, 0, wxLEFT | wxRIGHT | wxBOTTOM, em);

        // The plate before and after.
        auto* pictures = new wxBoxSizer(wxHORIZONTAL);
        auto* left     = new wxBoxSizer(wxVERTICAL);
        auto* right    = new wxBoxSizer(wxVERTICAL);
        m_picture_before = new wxStaticBitmap(this, wxID_ANY, wxNullBitmap, wxDefaultPosition, wxSize(FromDIP(110), FromDIP(110)));
        m_picture_after  = new wxStaticBitmap(this, wxID_ANY, wxNullBitmap, wxDefaultPosition, wxSize(FromDIP(300), FromDIP(300)));
        m_after_title    = text(this, wxEmptyString, false);
        left->Add(text(this, _L("Original"), false), 0, wxALIGN_CENTER | wxBOTTOM, em / 2);
        left->Add(m_picture_before, 0, wxALIGN_CENTER);
        right->Add(m_after_title, 0, wxALIGN_CENTER | wxBOTTOM, em / 2);
        right->Add(m_picture_after, 0, wxALIGN_CENTER);
        m_previous = new ::Button(this, "<");
        m_next     = new ::Button(this, ">");
        m_previous->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        m_next->SetStyle(ButtonStyle::Regular, ButtonType::Window);
        m_previous->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { set_plate(m_plate - 1); });
        m_next->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { set_plate(m_plate + 1); });
        pictures->Add(m_previous, 0, wxALIGN_CENTER_VERTICAL);
        pictures->AddStretchSpacer();
        pictures->Add(left, 0, wxALIGN_TOP | wxRIGHT, 2 * em);
        pictures->Add(right, 0, wxALIGN_TOP);
        pictures->AddStretchSpacer();
        pictures->Add(m_next, 0, wxALIGN_CENTER_VERTICAL);
        root->Add(pictures, 0, wxEXPAND | wxLEFT | wxRIGHT, em);

        Plater* plater = wxGetApp().plater();
        m_plate_count  = plater != nullptr ? plater->get_partplate_list().get_plate_count() : 0;
        m_plate        = plater != nullptr ? plater->get_partplate_list().get_curr_plate_index() : 0;
        auto* plates   = new wxBoxSizer(wxHORIZONTAL);
        m_plate_combo  = new ::ComboBox(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(6 * em, -1), 0, nullptr, wxCB_READONLY);
        for (int i = 0; i < m_plate_count; ++i)
            m_plate_combo->Append(wxString::Format("%02d", i + 1));
        m_plate_combo->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent& event) {
            set_plate(m_plate_combo->GetSelection());
            event.Skip();
        });
        plates->AddStretchSpacer();
        plates->Add(text(this, _L_CONTEXT("Plate", "Sync_AMS"), false), 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, em / 2);
        plates->Add(m_plate_combo, 0, wxALIGN_CENTER_VERTICAL);
        plates->AddStretchSpacer();
        root->Add(plates, 0, wxEXPAND | wxALL, em);

        // The preset each loaded toolhead's filament gets.
        wxString presets;
        bool     all_known = true;
        for (size_t t = 0; t < m_slots.size(); ++t) {
            if (!m_slots[t].present)
                continue;
            const Preset* preset = t < automatic.size() && !automatic[t].empty() ? preset_named(bundle, automatic[t]) : nullptr;
            const bool    made   = t < to_make.size() && !to_make[t].empty();
            all_known            = all_known && (preset != nullptr || made);
            // TRN toolhead number, what the printer reports there (vendor, material), then the preset chosen for it
            presets += format_wxstr(_L("T%1%: %2% %3% -> %4%"), int(t), from_u8(m_slots[t].vendor), from_u8(m_slots[t].type),
                                    made ? from_u8(to_make[t]) : preset != nullptr ? from_u8(preset->label(false)) : _L("chosen after confirming")) +
                       "\n";
        }
        presets.Trim();
        auto* preset_lines = new wxStaticText(this, wxID_ANY, presets);
        preset_lines->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(preset_lines, 0, wxLEFT | wxRIGHT, em);
        m_choose_presets = option(root, (s_lane_sync ? _L("Let me choose the filament preset for each slot.") : _L("Let me choose the filament preset for each toolhead.")), !all_known);
        if (!all_known)
            m_choose_presets->Disable(); // a toolhead without a known preset is always asked about

        m_advanced = new wxPanel(this);
        m_advanced->SetBackgroundColour(GetBackgroundColour());
        auto* advanced = new wxBoxSizer(wxVERTICAL);
        // A filament changer's four slots can be filled up like a U1's four toolheads.
        const size_t capacity = s_lane_sync ? 4 : u1_physical_filament_capacity(bundle.printers.get_edited_preset().config);
        m_can_add             = bundle.num_mixed_filaments() == 0 && m_physical < capacity;
        m_add_unused = option(advanced, (s_lane_sync ? _L("Add unused slot filaments to filaments list.") : _L("Add unused toolhead filaments to filaments list.")), false, m_advanced);
        // Removing a filament while the project has a mixed filament can leave the mix without a
        // component, and on a tool changer the list is topped up again behind the mix at the next
        // start. With a mixed filament in the project the sync changes presets and colors only.
        m_can_merge  = bundle.num_mixed_filaments() == 0;
        m_merge_same = option(advanced, _L("Automatically merge the same colors in the model after mapping."), m_can_merge, m_advanced);
        if (!m_can_add)
            m_add_unused->Disable();
        if (!m_can_merge)
            m_merge_same->Disable();
        m_advanced->SetSizer(advanced);
        root->Add(m_advanced, 0, wxEXPAND);

        m_undone = new wxStaticText(this, wxID_ANY, wxEmptyString);
        m_undone->SetForegroundColour(NIATheme::text(dark));
        m_undone->SetMinSize(wxSize(52 * em, -1));
        m_undone->SetMaxSize(wxSize(52 * em, -1));
        root->Add(m_undone, 0, wxALL, em);

        auto* buttons = new DialogButtons(this, {"Synchronize now", "Cancel"}, _L("Synchronize now"));
        buttons->GetFIRST()->SetId(wxID_OK);
        buttons->GetFIRST()->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { EndModal(wxID_OK); });
        buttons->GetCANCEL()->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { EndModal(wxID_CANCEL); });
        root->Add(buttons, 0, wxEXPAND);

        // With nothing loaded there is nothing to map to; with nothing in the project to recolor, overwriting is the plain case.
        set_mode(m_physical > 0);
        if (m_plate_count > 0)
            m_plate_combo->SetSelection(std::clamp(m_plate, 0, m_plate_count - 1));
        m_previous->Show(m_plate_count > 1);
        m_next->Show(m_plate_count > 1);
        SetSizerAndFit(root);
        CenterOnParent();
        wxGetApp().UpdateDlgDarkUI(this);
        set_mode(m_mode_mapping); // the dark-mode pass restyles the two mode buttons
    }

    SyncChoice choice() const
    {
        SyncChoice result;
        result.mapping        = m_mode_mapping;
        result.add_unused     = m_mode_mapping && m_can_add && m_add_unused->GetValue();
        result.merge_same     = m_mode_mapping && m_can_merge && m_merge_same->GetValue();
        result.choose_presets = m_choose_presets->GetValue();
        for (size_t i = 0; i < m_combos.size(); ++i)
            result.toolhead_for_filament.push_back(toolhead_of(i));
        return result;
    }

private:
    void on_dpi_changed(const wxRect&) override
    {
        for (::ComboBox* combo : m_combos)
            combo->Rescale();
        m_plate_combo->Rescale();
        if (GetSizer() != nullptr)
            GetSizer()->SetSizeHints(this);
        Refresh();
    }

    wxStaticText* text(wxWindow* parent, const wxString& label, bool muted)
    {
        const bool dark = wxGetApp().dark_mode();
        auto* control   = new wxStaticText(parent, wxID_ANY, label);
        control->SetForegroundColour(muted ? NIATheme::text_muted(dark) : NIATheme::text(dark));
        return control;
    }

    // A filament as a filled label: its number and material on its color.
    wxWindow* chip(wxWindow* parent, const wxString& label, const wxColour& colour)
    {
        auto* panel = new wxPanel(parent, wxID_ANY, wxDefaultPosition, wxDefaultSize, wxBORDER_SIMPLE);
        panel->SetBackgroundColour(colour);
        auto* caption = new wxStaticText(panel, wxID_ANY, label, wxDefaultPosition, wxDefaultSize, wxALIGN_CENTRE_HORIZONTAL);
        caption->SetBackgroundColour(colour);
        caption->SetForegroundColour(colour.GetLuminance() > 0.55 ? *wxBLACK : *wxWHITE);
        auto* sizer = new wxBoxSizer(wxVERTICAL);
        sizer->Add(caption, 0, wxEXPAND | wxALL, FromDIP(4));
        panel->SetSizer(sizer);
        // The app-wide dark pass recolors plain panels; these carry a filament color and keep it.
        m_chips.emplace_back(panel, colour);
        m_chips.emplace_back(caption, colour);
        return panel;
    }

    wxBitmap swatch(const wxColour& colour)
    {
        const int size = FromDIP(14);
        wxBitmap  bitmap(size, size);
        wxMemoryDC dc(bitmap);
        dc.SetBrush(wxBrush(colour));
        dc.SetPen(wxPen(NIATheme::line(wxGetApp().dark_mode())));
        dc.DrawRectangle(0, 0, size, size);
        dc.SelectObject(wxNullBitmap);
        return bitmap;
    }

    // A check box with its label, added to `sizer`.
    ::CheckBox* option(wxSizer* sizer, const wxString& label, bool value, wxWindow* parent = nullptr)
    {
        if (parent == nullptr)
            parent = this;
        const int em = wxGetApp().em_unit();
        auto* row    = new wxBoxSizer(wxHORIZONTAL);
        auto* box    = new ::CheckBox(parent);
        box->SetValue(value);
        row->Add(box, 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, em / 2);
        row->Add(text(parent, label, false), 0, wxALIGN_CENTER_VERTICAL);
        sizer->Add(row, 0, wxLEFT | wxRIGHT | wxTOP, em);
        return box;
    }

    wxColour slot_colour(size_t toolhead) const { return colour_from_hex(m_slots[toolhead].color, wxColour(0xCE, 0xCE, 0xCE)); }

    int toolhead_of(size_t filament) const
    {
        const int selected = m_combos[filament]->GetSelection();
        return selected >= 0 && size_t(selected) < m_combo_toolheads[filament].size() ? m_combo_toolheads[filament][size_t(selected)] : -1;
    }

    // The loaded toolhead closest to a project filament: the same material first, then the nearest color.
    int best_toolhead(size_t filament) const
    {
        const Preset*     preset   = preset_named(m_bundle, m_bundle.filament_presets[m_index[filament]]);
        const wxColour&   own      = m_before[m_index[filament]];
        const std::string material = preset != nullptr ? canonical_material(preset->config.opt_string("filament_type", 0u)) : std::string();
        int    best = -1;
        double best_distance = 0.0;
        for (size_t t = 0; t < m_slots.size(); ++t) {
            if (!m_slots[t].present)
                continue;
            const wxColour colour = slot_colour(t);
            const double   dr = double(colour.Red()) - own.Red(), dg = double(colour.Green()) - own.Green(),
                           db = double(colour.Blue()) - own.Blue();
            const bool same_material = !material.empty() && boost::iequals(canonical_material(m_slots[t].type, m_slots[t].sub_type), material);
            const double distance    = dr * dr + dg * dg + db * db + (same_material ? 0.0 : 1e9);
            if (best < 0 || distance < best_distance) {
                best          = int(t);
                best_distance = distance;
            }
        }
        return best;
    }

    void set_mode(bool mapping)
    {
        m_mode_mapping = mapping;
        m_mapping->SetStyle(mapping ? ButtonStyle::Confirm : ButtonStyle::Regular, ButtonType::Choice);
        m_overwrite->SetStyle(mapping ? ButtonStyle::Regular : ButtonStyle::Confirm, ButtonType::Choice);
        m_map_panel->Show(mapping);
        m_over_panel->Show(!mapping);
        m_advanced->Show(mapping);
        m_after_title->SetLabel(mapping ? _L("After mapping") : _L("After overwriting"));
        m_tip->SetLabel(mapping ?
                            (s_lane_sync ? _L("Each project filament takes the type and color of the slot chosen for it.") : _L("Each project filament takes the type and color of the toolhead chosen for it. The toolhead a filament prints from is chosen when the print is sent.")) :
                            (s_lane_sync ? _L("The project's filaments are replaced by the printer's slots, in slot order. An empty slot leaves its project filament as it is.") : _L("The project's filaments are replaced by the printer's four toolheads, in toolhead order. An empty toolhead leaves its project filament as it is.")));
        m_undone->SetLabel((mapping ?
                                _L("After being synced, the project's filament presets and colors will be replaced with the mapped filament types and colors. This action cannot be undone.") :
                                (s_lane_sync ? _L("After being synced, the project's filament presets and colors will be replaced with those of the printer's slots. This action cannot be undone.") : _L("After being synced, the project's filament presets and colors will be replaced with those of the printer's toolheads. This action cannot be undone."))) +
                           "\n\n" + _L("Are you sure to synchronize the filaments?"));
        m_tip->Wrap(-1);
        m_tip->Wrap(m_tip->GetMinSize().GetWidth());
        m_undone->Wrap(-1);
        m_undone->Wrap(m_undone->GetMinSize().GetWidth());
        // The dark pass recolors text as well as panels; a chip's text is chosen against its filament color.
        for (auto& [window, colour] : m_chips) {
            window->SetBackgroundColour(colour);
            window->SetForegroundColour(colour.GetLuminance() > 0.55 ? *wxBLACK : *wxWHITE);
        }
        update_pictures();
        if (GetSizer() != nullptr) {
            Layout();
            GetSizer()->SetSizeHints(this);
        }
        Refresh();
    }

    void set_plate(int plate)
    {
        if (m_plate_count <= 0)
            return;
        m_plate = std::clamp(plate, 0, m_plate_count - 1);
        m_plate_combo->SetSelection(m_plate);
        m_previous->Enable(m_plate > 0);
        m_next->Enable(m_plate + 1 < m_plate_count);
        update_pictures();
    }

    void update_pictures()
    {
        Plater* plater = wxGetApp().plater();
        if (plater == nullptr || m_plate_count <= 0 || m_picture_after == nullptr)
            return;
        PartPlate* plate = plater->get_partplate_list().get_plate(m_plate);
        if (plate == nullptr)
            return;
        std::vector<wxColour> after = m_before;
        // Mapping: each filament takes its chosen toolhead. Overwriting: the k-th filament takes toolhead k.
        for (size_t k = 0; k < m_index.size() && m_index[k] < after.size(); ++k) {
            if (m_mode_mapping) {
                if (k < m_combos.size())
                    if (const int toolhead = toolhead_of(k); toolhead >= 0)
                        after[m_index[k]] = slot_colour(size_t(toolhead));
            } else if (k < m_slots.size() && m_slots[k].present && !m_slots[k].color.empty()) {
                after[m_index[k]] = slot_colour(k);
            }
        }
        recompute_mixed_slot_colors(after, m_bundle.project_config);
        const auto show = [](wxStaticBitmap* target, wxImage image, int size) {
            if (!image.IsOk())
                return;
            target->SetBitmap(wxBitmap(image.Rescale(size, size, wxIMAGE_QUALITY_BOX_AVERAGE)));
        };
        show(m_picture_before, recolored_plate(plate->thumbnail_data, plate->no_light_thumbnail_data, m_before, m_before), FromDIP(110));
        show(m_picture_after, recolored_plate(plate->thumbnail_data, plate->no_light_thumbnail_data, m_before, after), FromDIP(300));
    }

    const std::vector<Slot>& m_slots;
    PresetBundle&            m_bundle;
    std::vector<size_t>      m_index; // the project slot of each ordinary filament
    size_t                   m_physical{0};
    std::vector<wxColour>    m_before;
    bool                     m_mode_mapping{true};
    bool                     m_can_add{false};
    bool                     m_can_merge{false};
    int                      m_plate{0};
    int                      m_plate_count{0};

    ::Button*                      m_mapping{nullptr};
    ::Button*                      m_overwrite{nullptr};
    ::Button*                      m_previous{nullptr};
    ::Button*                      m_next{nullptr};
    wxPanel*                       m_map_panel{nullptr};
    wxPanel*                       m_over_panel{nullptr};
    wxPanel*                       m_advanced{nullptr};
    wxStaticText*                  m_tip{nullptr};
    wxStaticText*                  m_undone{nullptr};
    wxStaticText*                  m_after_title{nullptr};
    wxStaticBitmap*                m_picture_before{nullptr};
    wxStaticBitmap*                m_picture_after{nullptr};
    ::ComboBox*                    m_plate_combo{nullptr};
    ::CheckBox*                    m_choose_presets{nullptr};
    ::CheckBox*                    m_add_unused{nullptr};
    ::CheckBox*                    m_merge_same{nullptr};
    std::vector<::ComboBox*>       m_combos;
    std::vector<std::vector<int>>  m_combo_toolheads;
    std::vector<std::pair<wxWindow*, wxColour>> m_chips;
};

class U1FilamentMappingDialog final : public DPIDialog
{
public:
    U1FilamentMappingDialog(wxWindow* parent, const std::vector<Slot>& slots, const wxString& report_source,
                            PresetBundle& bundle)
        : DPIDialog(parent,
                    wxID_ANY,
                    _L("Map Snapmaker U1 filaments"),
                    wxDefaultPosition,
                    wxDefaultSize,
                    wxDEFAULT_DIALOG_STYLE | wxRESIZE_BORDER)
        , m_slots(slots)
        , m_rows(slots.size())
    {
        const int em = wxGetApp().em_unit();
        const bool dark = wxGetApp().dark_mode();
        SetBackgroundColour(NIATheme::background(dark));
        auto* root   = new wxBoxSizer(wxVERTICAL);
        auto* heading = new wxStaticText(this, wxID_ANY, (s_lane_sync ? _L("Map the material in each slot") : _L("Map the material in each toolhead")));
        heading->SetFont(Label::Head_18);
        heading->SetForegroundColour(NIATheme::text(dark));
        root->Add(heading, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, em);
        auto* explanation = new wxStaticText(
            this, wxID_ANY,
            (s_lane_sync ? _L("Review every loaded slot. Empty slots keep their current project filament.") : _L("Review every loaded toolhead. Applying expands the project to the U1's fixed T0-T3 slots; empty toolheads keep their current project slot.")));
        explanation->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(explanation, 0, wxALL | wxEXPAND, em);
        auto* source = new wxStaticText(this, wxID_ANY, wxString::Format(_L("Report source: %s"), report_source));
        source->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(source, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);
        auto* grid = new wxFlexGridSizer(3, em / 2, em);
        grid->AddGrowableCol(2, 1);
        for (const wxString& label : {s_lane_sync ? _L("Slot") : _L("Toolhead"), s_lane_sync ? _L("Printer report") : _L("RFID / printer report"), _L("Filament preset")}) {
            auto* column = new wxStaticText(this, wxID_ANY, label);
            column->SetFont(Label::Head_13);
            column->SetForegroundColour(NIATheme::text_muted(dark));
            grid->Add(column, 0, wxBOTTOM, em / 2);
        }
        for (size_t i = 0; i < slots.size(); ++i) {
            const Slot& slot = slots[i];
            grid->Add(new wxStaticText(this, wxID_ANY, wxString::Format("T%d", int(i))), 0, wxALIGN_CENTER_VERTICAL);
            if (!slot.present) {
                auto* empty = new wxStaticText(this, wxID_ANY, (s_lane_sync ? _L("Empty slot") : _L("Empty toolhead")));
                empty->SetName(format_wxstr((s_lane_sync ? _L("Slot T%1%: empty slot") : _L("Toolhead T%1%: empty toolhead")), int(i)));
                grid->Add(empty, 0, wxALIGN_CENTER_VERTICAL);
                grid->Add(new wxStaticText(this, wxID_ANY, _L("Keep current project slot")), 0, wxALIGN_CENTER_VERTICAL);
                continue;
            }
            const wxString origin = slot.from_tag ? _L("RFID tag") : _L("Printer setting");
            const wxString color = slot.color_invalid ? _L("invalid color; current project color will be kept") : from_u8(slot.color);
            const std::string reported_material = canonical_material(slot.type, slot.sub_type);
            const wxString material_identity = reported_material.empty()
                                                   ? _L("material identity unresolved; choose a compatible preset explicitly")
                                                   : from_u8(slot.type + (slot.sub_type.empty() ? std::string{} : " " + slot.sub_type));
            const wxString report_identity = format_wxstr(_L("%1%: %2% %3% (%4%)"), origin, from_u8(slot.vendor),
                                                          material_identity, color);
            grid->Add(new wxStaticText(this, wxID_ANY, report_identity), 0, wxALIGN_CENTER_VERTICAL);
            MappingRow& row = m_rows[i];
            row.control     = new ::ComboBox(this, wxID_ANY, "", wxDefaultPosition, wxSize(34 * em, -1), 0, nullptr,
                                              wxCB_READONLY);
            row.control->SetName(format_wxstr((s_lane_sync ? _L("Filament preset for slot T%1%. Printer report: %2%") : _L("Filament preset for toolhead T%1%. Printer report: %2%")), int(i), report_identity));
            row.control->Append(_L("Select a matching filament preset"));
            row.choices.push_back({});
            const Preset* current = i < bundle.filament_presets.size() ? preset_named(bundle, bundle.filament_presets[i]) : nullptr;
            if (current != nullptr && u1_current_preset_matches_reported_material(*current, slot)) {
                row.control->Append(_L("Keep current project slot"));
                row.choices.push_back({{}, std::nullopt, true});
            }
            int exact = wxNOT_FOUND;
            std::uint64_t exact_rank = 0;
            for (const Preset& preset : bundle.filaments.get_presets()) {
                if (!preset.is_compatible || preset.is_default ||
                    (!reported_material.empty() &&
                     !boost::iequals(canonical_material(preset.config.opt_string("filament_type", 0u)), reported_material)))
                    continue;
                const wxString preset_label = from_u8(preset.label(false));
                row.control->Append(reported_material.empty()
                                        ? format_wxstr(_L("Manual override — %1%"), preset_label)
                                        : preset_label);
                row.choices.push_back({preset.name, std::nullopt, false});
                if (!reported_material.empty() && u1_preset_matches_reported_identity(preset, slot)) {
                    const std::uint64_t rank = u1_filament_preset_rank(preset, slot.vendor, slot.sub_type);
                    if (exact == wxNOT_FOUND || rank > exact_rank) {
                        exact      = int(row.choices.size() - 1);
                        exact_rank = rank;
                    }
                }
            }
            // A project slot that already fits the reported material is the starting point; a toolhead
            // with nothing fitting waits for a choice.
            row.control->SetSelection(row.choices.size() > 1 && row.choices[1].keep_current ? 1 : 0);
            row.control->Bind(wxEVT_COMBOBOX, [this](wxCommandEvent&) { update_apply(); });
            grid->Add(row.control, 1, wxEXPAND);
        }
        root->Add(grid, 1, wxLEFT | wxRIGHT | wxEXPAND, em);

        auto* actions = new wxBoxSizer(wxHORIZONTAL);
        auto* key     = new Button(this, _L("Configure API key"));
        auto* search  = new Button(this, _L("Find RFID products in 3D Filament Profiles"));
        m_search_button = search;
        search->SetStyle(ButtonStyle::Confirm, ButtonType::Choice);
        actions->Add(key, 0, wxRIGHT, em / 2);
        actions->Add(search, 0);
        root->Add(actions, 0, wxALL, em);
        m_status = new wxStaticText(this, wxID_ANY, (s_lane_sync ? _L("Choose a preset for each slot, or look the products up in 3D Filament Profiles.") : _L("Choose a preset for each toolhead, or look the RFID products up in 3D Filament Profiles.")));
        m_status->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(m_status, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);

        auto* buttons = new DialogButtons(this, {"Cancel", "Apply mappings"}, _L("Apply mappings"));
        m_apply = buttons->GetLAST();
        m_apply->SetId(wxID_OK);
        root->Add(buttons, 0, wxALL | wxALIGN_RIGHT, em);
        key->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { FilamentProfilesKeyDialog(this).ShowModal(); });
        search->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { search_catalog(); });
        Bind(
            wxEVT_BUTTON,
            [this](wxCommandEvent&) {
                if (!all_mapped())
                    return;
                const bool unresolved_override = std::any_of(m_rows.begin(), m_rows.end(), [this](const MappingRow& row) {
                    if (row.control == nullptr)
                        return false;
                    const size_t index = static_cast<size_t>(&row - m_rows.data());
                    const int selected = row.control->GetSelection();
                    if (index >= m_slots.size() || !m_slots[index].present ||
                        !canonical_material(m_slots[index].type, m_slots[index].sub_type).empty() || selected <= 0 ||
                        size_t(selected) >= row.choices.size())
                        return false;
                    const MappingChoice& choice = row.choices[size_t(selected)];
                    return !choice.catalog.has_value() && !choice.keep_current;
                });
                if (unresolved_override &&
                    MessageDialog(this,
                                  (s_lane_sync ? _L("At least one loaded slot has no material family in the printer report. Apply the selected presets as explicit manual overrides for those unresolved slots?") : _L("At least one loaded toolhead has no material family in the printer report. Apply the selected presets as explicit manual overrides for those unresolved toolheads?")),
                                  _L("Confirm unresolved material overrides"), wxYES_NO | wxNO_DEFAULT | wxICON_WARNING)
                            .ShowModal() != wxID_YES)
                    return;
                const bool catalog_override = std::any_of(m_rows.begin(), m_rows.end(), [](const MappingRow& row) {
                    if (row.control == nullptr)
                        return false;
                    const int selected = row.control->GetSelection();
                    return selected > 0 && size_t(selected) < row.choices.size() && row.choices[size_t(selected)].catalog.has_value();
                });
                if (catalog_override &&
                    MessageDialog(this,
                                  _L("3D Filament Profiles results are catalog candidates, not proof of the RFID identity. Apply the selected catalog products as explicit overrides for the corresponding U1 reports?"),
                                  _L("Confirm catalog overrides"), wxYES_NO | wxNO_DEFAULT | wxICON_QUESTION)
                            .ShowModal() != wxID_YES)
                    return;
                EndModal(wxID_OK);
            },
            wxID_OK);
        Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { EndModal(wxID_CANCEL); }, wxID_CANCEL);
        Bind(wxEVT_CLOSE_WINDOW, [this](wxCloseEvent&) { EndModal(wxID_CANCEL); });
        SetSizerAndFit(root);
        SetMinSize(wxSize(72 * em, GetSize().y));
        wxGetApp().UpdateDlgDarkUI(this);
        update_apply();
    }

    ~U1FilamentMappingDialog() override
    {
        m_alive->store(false);
        if (m_catalog_request)
            m_catalog_request->cancel();
        wipe_catalog_api_key();
    }

    std::optional<MappingChoice> selection(size_t index) const
    {
        if (index >= m_rows.size())
            return std::nullopt;
        const MappingRow& row = m_rows[index];
        if (row.control == nullptr)
            return std::nullopt;
        const int selected = row.control->GetSelection();
        if (selected <= 0 || size_t(selected) >= row.choices.size())
            return std::nullopt;
        const MappingChoice& choice = row.choices[size_t(selected)];
        if (choice.preset_name.empty() && !choice.catalog && !choice.keep_current)
            return std::nullopt;
        return choice;
    }

private:
    void on_dpi_changed(const wxRect&) override
    {
        for (MappingRow& row : m_rows)
            if (row.control != nullptr)
                row.control->Rescale();
        if (m_search_button != nullptr)
            m_search_button->Rescale();
        if (m_apply != nullptr)
            m_apply->Rescale();
        if (GetSizer() != nullptr)
            GetSizer()->SetSizeHints(this);
        Fit();
        Refresh();
    }

    bool all_mapped() const
    {
        std::vector<bool> mapped(m_slots.size(), false);
        for (size_t i = 0; i < m_slots.size(); ++i)
            mapped[i] = m_rows[i].control != nullptr && m_rows[i].control->GetSelection() > 0;
        return all_loaded_slots_mapped(m_slots, mapped);
    }

    void update_apply() { m_apply->Enable(!m_searching && all_mapped()); }

    void clear_catalog_choices()
    {
        for (MappingRow& row : m_rows) {
            if (row.control == nullptr)
                continue;
            const int selected = row.control->GetSelection();
            const bool selected_catalog = selected > 0 && size_t(selected) < row.choices.size() &&
                                          row.choices[size_t(selected)].catalog.has_value();
            while (!row.choices.empty() && row.choices.back().catalog) {
                row.choices.pop_back();
                row.control->Delete(row.control->GetCount() - 1);
            }
            if (selected_catalog)
                row.control->SetSelection(0);
        }
    }

    void wipe_catalog_api_key()
    {
        std::fill(m_catalog_api_key.begin(), m_catalog_api_key.end(), '\0');
        m_catalog_api_key.clear();
    }

    void search_catalog()
    {
        std::string api_key = load_filament_profiles_api_key();
        if (api_key.empty()) {
            FilamentProfilesKeyDialog dialog(this);
            if (dialog.ShowModal() != wxID_OK)
                return;
            api_key = load_filament_profiles_api_key();
            if (api_key.empty())
                return;
        }
        if (m_catalog_request)
            m_catalog_request->cancel();
        ++m_catalog_generation;
        clear_catalog_choices();
        wipe_catalog_api_key();
        m_catalog_api_key = std::move(api_key);
        m_catalog_found   = 0;
        m_searching       = true;
        m_search_button->Disable();
        m_status->SetLabel(_L("Searching 3D Filament Profiles…"));
        update_apply();
        search_catalog_slot(0);
    }

    void search_catalog_slot(size_t index)
    {
        while (index < m_slots.size() && !m_slots[index].present)
            ++index;
        if (index == m_slots.size()) {
            finish_catalog_search();
            return;
        }

        const Slot& slot = m_slots[index];
        std::string query;
        for (const std::string* part : {&slot.vendor, &slot.type, &slot.sub_type}) {
            if (part->empty())
                continue;
            if (!query.empty())
                query += ' ';
            query += *part;
        }
        auto alive              = m_alive;
        auto* dialog            = this;
        const size_t generation = m_catalog_generation;
        if (m_catalog_api_key.empty()) {
            m_catalog_request.reset();
            m_status->SetLabel(_L("The 3D Filament Profiles API key is unavailable."));
            finish_catalog_search(false);
            return;
        }
        m_catalog_request       = search_filament_profiles_async(
            query, m_catalog_api_key, [dialog, alive, index, generation](FilamentProfilesSearchReply reply) mutable {
                dialog->CallAfter([dialog, alive, index, generation, reply = std::move(reply)]() mutable {
                    if (!alive->load() || generation != dialog->m_catalog_generation)
                        return;
                    dialog->on_catalog_reply(index, std::move(reply));
                });
            });
        if (!m_catalog_request) {
            // Empty or invalid requests currently invoke the callback before
            // returning null. Invalidate that queued callback and recover the
            // dialog here too, so a future early-return path cannot leave
            // Apply permanently disabled.
            ++m_catalog_generation;
            m_status->SetLabel(format_wxstr((s_lane_sync ? _L("Catalog lookup could not start for slot T%1%.") : _L("Catalog lookup could not start for toolhead T%1%.")), int(index)));
            finish_catalog_search(false);
        }
    }

    void on_catalog_reply(size_t index, FilamentProfilesSearchReply reply)
    {
        m_catalog_request.reset();
        if (!reply.ok) {
            m_status->SetLabel(format_wxstr((s_lane_sync ? _L("Catalog lookup failed for slot T%1%: %2%") : _L("Catalog lookup failed for toolhead T%1%: %2%")), int(index), from_u8(reply.error)));
            finish_catalog_search(false);
            return;
        }

        MappingRow& row = m_rows[index];
        for (const FilamentProfileRecord& record : reply.records) {
            const std::string name = filament_profile_product_name(record);
            if (!filament_profile_record_is_usable(record))
                continue;
            const bool duplicate = std::any_of(row.choices.begin(), row.choices.end(), [&](const MappingChoice& choice) {
                if (!choice.catalog)
                    return false;
                if (!record.id.empty() && !choice.catalog->id.empty())
                    return record.id == choice.catalog->id;
                return boost::iequals(filament_profile_product_name(*choice.catalog), name) &&
                       boost::iequals(choice.catalog->rgb, record.rgb) &&
                       boost::iequals(choice.catalog->color, record.color);
            });
            if (duplicate)
                continue;
            const wxString identity = record.id.empty() ? from_u8(name) : format_wxstr(_L("%1% (record %2%)"), from_u8(name), from_u8(record.id));
            row.control->Append(format_wxstr(_L("%1% — 3D Filament Profiles"), identity));
            row.choices.push_back({{}, record, false});
            ++m_catalog_found;
        }
        // Catalog search results are candidates, not RFID identity proof.  The
        // user must explicitly choose a product; never select the first result.
        search_catalog_slot(index + 1);
    }

    void finish_catalog_search(bool completed = true)
    {
        if (!completed) {
            clear_catalog_choices();
            m_catalog_found = 0;
        }
        wipe_catalog_api_key();
        m_searching = false;
        m_search_button->Enable();
        if (completed) {
            wxString unmapped;
            for (size_t i = 0; i < m_slots.size(); ++i) {
                if (!m_slots[i].present || m_rows[i].control == nullptr || m_rows[i].control->GetSelection() > 0)
                    continue;
                if (!unmapped.empty())
                    unmapped += ", ";
                unmapped += wxString::Format("T%d", int(i));
            }
            if (unmapped.empty())
                m_status->SetLabel(format_wxstr(_L("Found %1% matching catalog products. Review each selection before applying."),
                                                 int(m_catalog_found)));
            else
                m_status->SetLabel(format_wxstr(
                    s_lane_sync ? _L("Found %1% catalog products. Slots %2% are still unmapped; choose a matching preset in those rows.") :
                                  _L("Found %1% catalog products. Toolheads %2% are still unmapped; choose a matching preset in those rows."),
                    int(m_catalog_found), unmapped));
        }
        update_apply();
        Fit();
    }

    const std::vector<Slot>& m_slots;
    std::vector<MappingRow> m_rows;
    std::shared_ptr<std::atomic_bool> m_alive{std::make_shared<std::atomic_bool>(true)};
    Http::Ptr m_catalog_request;
    std::string m_catalog_api_key;
    size_t m_catalog_generation{0};
    size_t m_catalog_found{0};
    bool m_searching{false};
    wxStaticText* m_status{nullptr};
    Button* m_search_button{nullptr};
    Button* m_apply{nullptr};
};

} // namespace

std::vector<U1ReportedFilament> parse_u1_filament_report(const nlohmann::json& report) { return parse_report_impl(report); }
std::vector<U1ReportedFilament> parse_filament_lane_data(const nlohmann::json& value) { return parse_lane_data_impl(value); }
bool filament_lane_data_is_recognised(const nlohmann::json& value) { return lane_data_is_recognised(value); }

bool u1_catalog_preset_matches_record(const Preset& preset, const FilamentProfileRecord& record)
{
    const std::string material = filament_profile_base_material(record);
    const std::string vendor   = record.brand.empty() ? "Generic" : record.brand;
    FilamentProfileRecord preset_record;
    preset_record.material = preset.config.opt_string("filament_type", 0u);
    return !material.empty() && boost::iequals(preset.config.opt_string("filament_vendor", 0u), vendor) &&
           boost::iequals(filament_profile_base_material(preset_record), material);
}

std::optional<std::size_t> u1_virtual_tray_toolhead_index(const std::string& tray_id)
{
    return virtual_tray_toolhead_index_impl(tray_id);
}

std::optional<std::size_t> u1_unambiguous_rack_toolhead_index(int extruder_id, std::size_t loaded_trays,
                                                              std::size_t toolhead_capacity)
{
    if (extruder_id < 0 || static_cast<std::size_t>(extruder_id) >= toolhead_capacity || loaded_trays != 1)
        return std::nullopt;
    return static_cast<std::size_t>(extruder_id);
}

bool u1_printer_agent_identity_matches(const std::string& model_id, const std::string& display_name,
                                       const std::string& product_name, const std::vector<std::string>& tray_ids)
{
    const auto names_u1 = [](const std::string& raw) {
        const std::string value = boost::trim_copy(raw);
        if (boost::iequals(value, "U1"))
            return true;
        constexpr size_t prefix_size = sizeof("Snapmaker U1") - 1;
        return value.size() >= prefix_size && boost::istarts_with(value, "Snapmaker U1") &&
               (value.size() == prefix_size || !std::isalnum(static_cast<unsigned char>(value[prefix_size])));
    };
    return names_u1(model_id) || names_u1(display_name) || names_u1(product_name) ||
           std::any_of(tray_ids.begin(), tray_ids.end(), [](const std::string& tray_id) {
               return virtual_tray_toolhead_index_impl(tray_id).has_value();
           });
}

bool u1_all_loaded_slots_mapped(const std::vector<U1ReportedFilament>& slots, const std::vector<bool>& mapped)
{
    return all_loaded_slots_mapped(slots, mapped);
}

U1ReportSource u1_authoritative_report_source(const std::vector<U1ReportedFilament>& direct,
                                              const std::vector<U1ReportedFilament>& printer_agent,
                                              bool reports_same_printer)
{
    const auto any_loaded = [](const std::vector<U1ReportedFilament>& report) {
        return std::any_of(report.begin(), report.end(), [](const U1ReportedFilament& slot) { return slot.present; });
    };
    const auto any_rfid = [](const std::vector<U1ReportedFilament>& report) {
        return std::any_of(report.begin(), report.end(), [](const U1ReportedFilament& slot) {
            return slot.present && slot.from_tag;
        });
    };
    const auto reports_conflict = [](const std::vector<U1ReportedFilament>& left,
                                     const std::vector<U1ReportedFilament>& right) {
        const size_t count = std::max(left.size(), right.size());
        for (size_t i = 0; i < count; ++i) {
            const U1ReportedFilament empty;
            const U1ReportedFilament& lhs = i < left.size() ? left[i] : empty;
            const U1ReportedFilament& rhs = i < right.size() ? right[i] : empty;
            if (lhs.present != rhs.present)
                return true;
            if (!lhs.present)
                continue;
            if (lhs.color_invalid != rhs.color_invalid)
                return true;
            const auto differs = [](const std::string& a, const std::string& b) {
                return !a.empty() && !b.empty() && !boost::iequals(a, b);
            };
            if (differs(lhs.vendor, rhs.vendor) || differs(lhs.type, rhs.type) ||
                differs(lhs.sub_type, rhs.sub_type) || differs(lhs.color, rhs.color))
                return true;
        }
        return false;
    };
    if (reports_same_printer && !direct.empty() && !printer_agent.empty() && reports_conflict(direct, printer_agent))
        return U1ReportSource::Conflict;
    if (reports_same_printer && any_loaded(printer_agent) && any_rfid(printer_agent))
        return U1ReportSource::PrinterAgent;
    if (any_loaded(direct))
        return U1ReportSource::DirectLan;
    if (reports_same_printer && any_loaded(printer_agent))
        return U1ReportSource::PrinterAgent;
    return U1ReportSource::None;
}

namespace {
// Set while a printer without lane data is handed to its printer agent's own sync, so that the sidebar does
// not send it back here. Main thread only.
bool s_lane_fallback = false;
} // namespace

bool lane_filament_sync_available()
{
    PresetBundle* bundle = wxGetApp().preset_bundle;
    if (bundle == nullptr || bundle->is_bbl_vendor())
        return false;
    const DynamicPrintConfig& printer = bundle->printers.get_edited_preset().config;
    if (u1_physical_filament_capacity(printer) != 0 || resolve_printer_lan_host().empty())
        return false;
    // Only printers reached the Moonraker way can have lane data. Whether this one has is known after asking it.
    const auto* host_type = printer.option<ConfigOptionEnum<PrintHostType>>("host_type");
    if (host_type != nullptr && host_type->value != htMoonraker && host_type->value != htOctoPrint)
        return false;
    // Another brand's printer agent that syncs filaments itself keeps doing so. OrcaSlicer's Moonraker agent
    // reads the same lane data; for its printers the lanes are read here first, so that they go through the
    // review, and its own sync is used when the printer has no lane data (s_lane_fallback, set in
    // sync_filaments_from_lanes()): what the button did for such a printer before the lane sync existed.
    if (s_lane_fallback)
        return false;
    const auto agent = wxGetApp().getAgent();
    if (!agent || agent->get_filament_sync_mode() == FilamentSyncMode::none)
        return true;
    const auto printer_agent = agent->get_printer_agent();
    return printer_agent && printer_agent->get_agent_info().id == MOONRAKER_PRINTER_AGENT_ID;
}

namespace {

bool sync_from_slots(wxWindow* parent, std::vector<Slot> slots, const wxString& report_source);

// Reads the lanes a filament changer on one nozzle (AFC: a CANVAS, a Box Turtle; recent Happy Hare) writes to
// Moonraker's database, one read request, then runs the same review and apply as for a U1.
bool sync_filaments_from_lanes(wxWindow* parent)
{
    // The wait below lets the application's events run. A second sync asked for meanwhile is not started,
    // and a window that went away meanwhile is not used.
    static bool running = false;
    if (running)
        return false;
    running = true;
    struct Done { ~Done() { running = false; } } done;
    const wxWeakRef<wxWindow> still_there(parent);
    const std::string host = resolve_printer_lan_host();
    // The read runs beside the GUI thread: a printer that does not answer costs up to ten seconds, and the
    // window stays alive meanwhile. When no answer has come within half a second a small window with Cancel
    // shows; cancelling gives the read up and changes nothing.
    struct LaneRead
    {
        std::optional<nlohmann::json> value;
        bool        answered = false;
        std::string error;
        std::vector<std::string> brands; ///< per tool number, where the printer knows the spool's brand
        std::vector<std::string> tags;   ///< per tool number, the tag last read for the spool, where the printer keeps it
    };
    const auto stop    = std::make_shared<std::atomic<bool>>(false);
    auto       pending = std::async(std::launch::async, [host, stop] {
        LaneRead read;
        // The address as given, then Moonraker's own port when no port was given and nothing answered there.
        std::vector<std::string> bases{"http://" + host};
        if (host.find(':') == std::string::npos)
            bases.push_back("http://" + host + ":7125");
        for (const std::string& base : bases) {
            Http::get(base + "/server/database/item?namespace=lane_data")
                .timeout_connect(5)
                .timeout_max(5)
                .on_progress([stop](Http::Progress, bool& cancel) { cancel = stop->load(); })
                .on_complete([&read](std::string body, unsigned) {
                    read.answered  = true;
                    const auto doc = nlohmann::json::parse(body, nullptr, false);
                    if (!doc.is_discarded() && doc.is_object() && doc.contains("result") && doc["result"].is_object() &&
                        doc["result"].contains("value") && doc["result"]["value"].is_object())
                        read.value = doc["result"]["value"];
                })
                // Moonraker answers 404 when nothing ever wrote lane data: an answer, and no lanes.
                .on_error([&read](std::string, std::string err, unsigned status) {
                    read.answered = read.answered || status != 0;
                    if (status == 0)
                        read.error = err;
                })
                .perform_sync();
            if (read.value && !stop->load())
                read.brands = U1LanPrintHost::query_afc_brands(base, &read.tags);
            if (read.answered || stop->load())
                break;
        }
        return read;
    });
    if (pending.wait_for(std::chrono::milliseconds(500)) != std::future_status::ready) {
        // TRN %1% is a network address.
        ProgressDialog waiting(_L("Sync filaments"), format_wxstr(_L("Reading the filament lanes of the printer at %1%…"), from_u8(host)), 100, parent,
                               wxPD_APP_MODAL | wxPD_AUTO_HIDE | wxPD_CAN_ABORT);
        while (pending.wait_for(std::chrono::milliseconds(100)) != std::future_status::ready)
            if (!waiting.Pulse())
                stop->store(true);
    }
    const LaneRead read = pending.get();
    if (stop->load() || (parent != nullptr && !still_there) || wxGetApp().is_closing())
        return false; // cancelled, or the window the sync was asked from is gone
    const std::optional<nlohmann::json>& value    = read.value;
    const bool                           answered = read.answered;
    const wxString                       error    = from_u8(read.error);
    std::vector<Slot> slots = value ? parse_lane_data_impl(*value) : std::vector<Slot>();
    for (size_t i = 0; i < slots.size() && i < read.brands.size(); ++i)
        if (slots[i].present && slots[i].vendor.empty())
            slots[i].vendor = read.brands[i];
    for (size_t i = 0; i < slots.size() && i < read.tags.size(); ++i)
        if (slots[i].present)
            slots[i].tag_id = SpoolTags::normalized(read.tags[i]);
    if (!std::any_of(slots.begin(), slots.end(), [](const Slot& slot) { return slot.present; })) {
        // No lane data, but a printer agent that syncs filaments its own way (Happy Hare, a Creality CFS): its sync runs.
        // Not for a printer that did not answer at all: its sync would only wait for the same printer again.
        if (const auto agent = wxGetApp().getAgent(); answered && !value && agent && agent->get_filament_sync_mode() != FilamentSyncMode::none) {
            s_lane_fallback = true;
            struct Reset { ~Reset() { s_lane_fallback = false; } } reset;
            wxGetApp().sidebar().sync_ams_list();
            return false;
        }
        wxString detail;
        if (!answered)
            // TRN %1% is a network address, %2% the error.
            detail = format_wxstr(_L("The printer at %1% did not answer: %2%"), from_u8(host), error);
        else if (!value)
            detail = _L("This printer reports no filament lanes. The sync reads the lane data a filament changer such as a CANVAS on COSMOS (AFC) writes to the printer; this printer has none.");
        else if (!lane_data_is_recognised(*value))
            detail = _L("This printer's filament lane data is in a form this version does not read, so nothing was changed. The sync reads lane data as a CANVAS on COSMOS (AFC) writes it: one entry per lane, each naming its tool, material and colour.");
        else
            detail = _L("The printer's filament lanes are all empty, or none is mapped to a tool.");
        MessageDialog(parent, detail, _L("Sync filaments"), wxOK | wxICON_INFORMATION).ShowModal();
        return false;
    }
    s_lane_sync = true;
    struct Reset { ~Reset() { s_lane_sync = false; } } reset;
    return sync_from_slots(parent, std::move(slots), _L("the printer's filament lanes (AFC lane data)"));
}

} // namespace

bool u1_filament_sync_available()
{
    PresetBundle* bundle = wxGetApp().preset_bundle;
    if (bundle == nullptr)
        return false;
    const DynamicPrintConfig& printer = bundle->printers.get_edited_preset().config;
    if (u1_physical_filament_capacity(printer) == 0)
        return lane_filament_sync_available();
    if (!resolve_printer_lan_host().empty())
        return true;
    DeviceManager* manager = wxGetApp().getDeviceManager();
    MachineObject* machine = manager != nullptr ? manager->get_selected_machine() : nullptr;
    if (machine == nullptr)
        return false;
    std::vector<std::string> tray_ids;
    tray_ids.reserve(machine->vt_slot.size());
    for (const DevAmsTray& tray : machine->vt_slot)
        tray_ids.push_back(tray.id);
    return u1_printer_agent_identity_matches(machine->printer_type,
                                             std::string(machine->get_printer_type_display_str().utf8_str()),
                                             machine->dev_product_name, tray_ids);
}

bool sync_filaments_from_u1(wxWindow* parent)
{
    if (lane_filament_sync_available())
        return sync_filaments_from_lanes(parent);
    const std::string host = resolve_printer_lan_host();
    DeviceManager* manager = wxGetApp().getDeviceManager();
    MachineObject* selected_machine = manager != nullptr ? manager->get_selected_machine() : nullptr;
    const bool selected_machine_available = selected_machine != nullptr;
    wxString error;
    std::vector<Slot> direct_slots;
    if (!host.empty()) {
        std::optional<nlohmann::json> report;
        wxBusyCursor wait;
        report = U1LanPrintHost::query_filament_report(host, error);
        if (report)
            direct_slots = parse_u1_filament_report(*report);
    }

    PresetBundle& bundle = *wxGetApp().preset_bundle;
    bool agent_ambiguous = false;
    std::vector<Slot> agent_slots;
    bool selected_machine_is_u1 = false;
    if (selected_machine != nullptr) {
        // Refresh pull-mode agents before inspecting U1-specific identity and
        // tray records. This changes only the selected MachineObject view.
        wxGetApp().sidebar().build_filament_ams_list(selected_machine);
        std::vector<std::string> tray_ids;
        tray_ids.reserve(selected_machine->vt_slot.size());
        for (const DevAmsTray& tray : selected_machine->vt_slot)
            tray_ids.push_back(tray.id);
        selected_machine_is_u1 = u1_printer_agent_identity_matches(
            selected_machine->printer_type, std::string(selected_machine->get_printer_type_display_str().utf8_str()),
            selected_machine->dev_product_name, tray_ids);
    }
    const bool reports_same_printer = selected_machine_is_u1 &&
        (host.empty() || selected_machine->get_dev_ip() == host ||
         (!selected_machine->get_dev_ip().empty() && host.rfind(selected_machine->get_dev_ip() + ":", 0) == 0));
    if (reports_same_printer) {
        wxBusyCursor wait;
        agent_slots = selected_machine_report(bundle, *selected_machine, agent_ambiguous);
    }
    std::vector<Slot> slots;
    wxString report_source;
    const bool direct_has_loaded = std::any_of(direct_slots.begin(), direct_slots.end(), [](const Slot& slot) { return slot.present; });
    switch (u1_authoritative_report_source(direct_slots, agent_slots, reports_same_printer)) {
    case U1ReportSource::PrinterAgent:
        slots = std::move(agent_slots);
        report_source = _L("selected printer RFID / device report");
        break;
    case U1ReportSource::DirectLan:
        slots = std::move(direct_slots);
        report_source = _L("direct Snapmaker U1 LAN report");
        break;
    case U1ReportSource::None:
        break;
    case U1ReportSource::Conflict:
        MessageDialog(parent,
                      _L("The direct Snapmaker U1 report and the selected printer report disagree about loaded toolheads or filament identity. Refresh the printer on the Printer tab and review its RFID data before synchronizing."),
                      _L("Sync filaments"), wxOK | wxICON_WARNING)
            .ShowModal();
        return false;
    }
    if (slots.size() > 4)
        slots.resize(4);
    const bool any_loaded = std::any_of(slots.begin(), slots.end(), [](const Slot& slot) { return slot.present; });
    if (!any_loaded) {
        wxString detail;
        if (agent_ambiguous)
            detail = _L("The printer reported loaded filament trays, but their physical U1 toolhead assignment is ambiguous. Refresh the printer on the Printer tab and try again.");
        else if (!host.empty() && selected_machine_available && !reports_same_printer && !direct_has_loaded)
            detail = _L("The configured U1 LAN address and the selected printer refer to different devices. Select the matching U1 or correct its LAN address before synchronizing filaments.");
        else if (host.empty() && !selected_machine_available)
            detail = _L("No Snapmaker U1 connection is available. Configure the printer LAN address or select the printer on the Printer tab, then try again.");
        else if (host.empty() && !selected_machine_is_u1)
            detail = _L("The selected printer could not be verified as a Snapmaker U1. Select the U1 on the Printer tab or configure its LAN address before synchronizing filaments.");
        else if (!host.empty() && !error.empty())
            detail = format_wxstr(_L("The printer reports no loaded filaments.\n\nThe direct U1 query also reported: %s"), error);
        else
            detail = _L("The printer reports no loaded filaments.");
        MessageDialog(parent, detail, _L("Sync filaments"), wxOK | wxICON_INFORMATION).ShowModal();
        return false;
    }

    return sync_from_slots(parent, std::move(slots), report_source);
}

namespace {

// The review and the apply, the same for a U1's toolheads and for the lanes of a filament changer.
bool sync_from_slots(wxWindow* parent, std::vector<Slot> slots, const wxString& report_source)
{
    PresetBundle& bundle = *wxGetApp().preset_bundle;
    // Counted in the project's own filaments: on a tool changer the preset list always has one entry per
    // toolhead, whatever the project uses.
    if (bundle.num_physical_filaments() > 4) {
        MessageDialog(parent,
                      s_lane_sync ? _L("This project has more filaments than the four slots this sync handles. Synchronization was not applied because removing the additional project filaments would lose data.") :
                                    _L("This project has more than the Snapmaker U1's four physical filament slots. Synchronization was not applied because removing the additional project slots would lose data."),
                      _L("Sync filaments"), wxOK | wxICON_WARNING)
            .ShowModal();
        return false;
    }

    try {
        bundle.update_compatible(PresetSelectCompatibleType::Always);
    } catch (const std::exception& exception) {
        MessageDialog(parent,
                      format_wxstr(_L("The compatible filament presets could not be refreshed: %1%"), from_u8(exception.what())),
                      _L("Sync filaments"), wxOK | wxICON_ERROR)
            .ShowModal();
        return false;
    }
    // Applies one choice per loaded toolhead as a single change: either all of it lands or the project is
    // put back as it was and the reason is shown. `target` names, per project filament, the toolhead whose
    // preset and color it takes (-1 leaves the filament as it is); the project ends up with `count` physical
    // filaments, the missing ones added the way the sidebar adds one.
    const auto apply = [&](const std::function<std::optional<MappingChoice>(size_t)>& choice_for, const std::vector<int>& target,
                           const size_t count) -> bool {
    Plater* plater = wxGetApp().plater();
    std::vector<bool> used(slots.size(), false);
    for (const int toolhead : target)
        if (toolhead >= 0 && size_t(toolhead) < slots.size() && slots[size_t(toolhead)].present)
            used[size_t(toolhead)] = true;
    const size_t original_physical = bundle.num_physical_filaments();
    const std::optional<PresetBundle::Selection> original_selection = bundle.selection();
    const std::vector<std::string> original_presets = bundle.filament_presets;
    const DynamicPrintConfig original_project_config = bundle.project_config;
    const std::string original_filament_tab_preset = bundle.filaments.get_selected_preset().name;

    // Resolve or create every selected preset before the first project mutation. Keep preset creation
    // inside the transaction too: saving a catalog-derived user preset may throw after writing a file.
    std::vector<std::string> selected(slots.size());
    std::vector<bool> keep_current(slots.size(), false);
    std::vector<std::string> created_presets;
    try {
        for (size_t i = 0; i < slots.size(); ++i) {
            if (!used[i])
                continue;
            const std::optional<MappingChoice> choice = choice_for(i);
            if (!choice)
                throw std::runtime_error((boost::format("The selected mapping for toolhead T%1% is invalid.") % i).str());
            if (choice->keep_current) {
                keep_current[i] = true;
                continue;
            }
            if (!choice->preset_name.empty()) {
                selected[i] = choice->preset_name;
                continue;
            }
            std::string create_error;
            const CatalogPresetResult result = choice->catalog ? catalog_preset(bundle, *choice->catalog, slots[i], created_presets, create_error)
                                                                : CatalogPresetResult{};
            if (result.preset_name.empty())
                throw std::runtime_error((boost::format("The filament preset for toolhead T%1% could not be prepared. %2%") %
                                          i % create_error)
                                             .str());
            selected[i] = result.preset_name;
        }

        bundle.update_compatible(PresetSelectCompatibleType::Always);
        // Validate every resolved name after compatibility has been recalculated and before the first project mutation.
        for (size_t i = 0; i < slots.size(); ++i)
            if (used[i] && !keep_current[i]) {
                const Preset* preset = preset_named(bundle, selected[i]);
                if (preset == nullptr || !preset->is_compatible)
                    throw std::runtime_error((boost::format("The selected preset for toolhead T%1% is no longer compatible.") % i).str());
            }

        while (bundle.num_physical_filaments() < count) {
            const size_t before = bundle.num_physical_filaments();
            plater->sidebar().add_custom_filament(wxColour(0xCE, 0xCE, 0xCE));
            if (bundle.num_physical_filaments() == before)
                break;
        }
        if (bundle.num_physical_filaments() != count || bundle.filament_presets.size() < count)
            throw std::runtime_error("The project did not take the expected number of filaments.");
        bundle.update_compatible(PresetSelectCompatibleType::Always);
        for (size_t f = 0; f < target.size() && f < bundle.filament_presets.size(); ++f) {
            if (target[f] < 0 || !used[size_t(target[f])])
                continue;
            const size_t       i           = size_t(target[f]);
            const std::string& preset_name = keep_current[i] ? bundle.filament_presets[f] : selected[i];
            const Preset* preset = preset_named(bundle, preset_name);
            if (preset == nullptr || !preset->is_compatible)
                throw std::runtime_error((boost::format("The selected preset for toolhead T%1% became incompatible after exposing the U1 slots.") % i).str());
            if (keep_current[i] && !u1_current_preset_matches_reported_material(*preset, slots[i]))
                throw std::runtime_error((boost::format("The current project preset for toolhead T%1% no longer matches the reported material.") % i).str());
        }
        for (size_t f = 0; f < target.size() && f < bundle.filament_presets.size(); ++f) {
            if (target[f] < 0 || !used[size_t(target[f])])
                continue;
            const size_t i = size_t(target[f]);
            if (!keep_current[i])
                bundle.set_filament_preset(f, selected[i]);
            if (!slots[i].color.empty())
                set_u1_filament_color(bundle.project_config, f, slots[i].color);
        }

        plater->update_filament_colors_in_full_config();
        plater->sidebar().update_all_preset_comboboxes();
        if (Tab* tab = wxGetApp().get_tab(Preset::TYPE_FILAMENT))
            tab->select_preset(bundle.filament_presets[0]);
        bundle.export_selections(*wxGetApp().app_config);
    } catch (const std::exception& exception) {
        wxString recovery_error;
        auto append_recovery_error = [&](const wxString& operation, const std::exception& rollback_exception) {
            recovery_error += format_wxstr(_L("\n\n%1% failed: %2%"), operation, from_u8(rollback_exception.what()));
        };

        // Filaments added above go first, last one first, through the sidebar's own removal.
        try {
            while (bundle.num_physical_filaments() > original_physical) {
                const size_t before = bundle.num_physical_filaments();
                plater->sidebar().delete_filament(before - 1);
                if (bundle.num_physical_filaments() == before)
                    break;
            }
        } catch (const std::exception& rollback_exception) {
            append_recovery_error(_L("Removing the filaments added for the toolheads"), rollback_exception);
        }

        // Preset cleanup is independent of project restoration: attempt every deletion even if another rollback step fails.
        bool removed_all_created_presets = true;
        for (auto it = created_presets.rbegin(); it != created_presets.rend(); ++it) {
            try {
                bundle.filaments.delete_preset(*it, true);
            } catch (const std::exception& rollback_exception) {
                removed_all_created_presets = false;
                append_recovery_error(format_wxstr(_L("Removing catalog-created preset %1%"), from_u8(*it)), rollback_exception);
            }
        }

        if (original_selection && removed_all_created_presets) {
            try {
                bundle.restore_selection(*original_selection);
            } catch (const std::exception& rollback_exception) {
                append_recovery_error(_L("Restoring the previous complete preset selection"), rollback_exception);
            }
        }
        try {
            plater->on_filament_count_change(original_presets.size());
        } catch (const std::exception& rollback_exception) {
            append_recovery_error(_L("Restoring the previous project filament count"), rollback_exception);
        }
        try {
            // Selection restoration is not proof that the project vectors
            // were restored. Reapply both authoritative snapshots after the
            // count callback on every rollback path, including embedded
            // presets and partial catalog-cleanup failures.
            bundle.filament_presets = original_presets;
            bundle.project_config   = original_project_config;
            bundle.update_compatible(PresetSelectCompatibleType::Always);
        } catch (const std::exception& rollback_exception) {
            append_recovery_error(_L("Restoring the previous project filament state"), rollback_exception);
        }
        try {
            if (bundle.filament_presets != original_presets || bundle.project_config != original_project_config)
                throw std::runtime_error("The previous project filament slots and configuration could not be restored exactly.");
            plater->update_filament_colors_in_full_config();
            plater->sidebar().update_all_preset_comboboxes();
            if (Tab* tab = wxGetApp().get_tab(Preset::TYPE_FILAMENT);
                tab != nullptr && !tab->select_preset(original_filament_tab_preset, false, "", true))
                throw std::runtime_error("The previous Filament tab selection could not be restored.");
        } catch (const std::exception& rollback_exception) {
            append_recovery_error(_L("Refreshing the restored project controls"), rollback_exception);
        }
        try {
            // Compensate for any successful export performed before a later UI operation threw.
            bundle.export_selections(*wxGetApp().app_config);
        } catch (const std::exception& rollback_exception) {
            append_recovery_error(_L("Persisting the restored preset selections"), rollback_exception);
        }
        MessageDialog(parent,
                      format_wxstr(_L("The filament mappings could not be applied: %1%%2%"), from_u8(exception.what()), recovery_error),
                      _L("Sync filaments"), wxOK | wxICON_ERROR)
            .ShowModal();
        return false;
    }
    return true;
    };

    // The preset each loaded toolhead gets without asking; empty where none could be picked. A spool whose tag
    // the person chose a preset for before, on this printer model, gets that preset again, as long as it is
    // still there, still fits this printer and is still of the material the printer reports.
    const std::string        printer_model = bundle.printers.get_edited_preset().config.opt_string("printer_model");
    SpoolTags::Memory        tag_memory    = SpoolTags::Memory::load(SpoolTags::memory_path());
    std::vector<bool>        from_memory(slots.size(), false);
    std::vector<std::string> automatic(slots.size());
    for (size_t i = 0; i < slots.size(); ++i)
        if (slots[i].present) {
            automatic[i] = automatic_preset(bundle, slots[i]);
            const std::string remembered = slots[i].tag_id.empty() ? std::string() : tag_memory.preset_for(slots[i].tag_id, printer_model);
            if (const Preset* preset = remembered.empty() ? nullptr : preset_named(bundle, remembered);
                preset != nullptr && preset->is_compatible &&
                boost::iequals(canonical_material(preset->config.opt_string("filament_type", 0u)), canonical_material(slots[i].type, slots[i].sub_type))) {
                automatic[i]   = preset->name;
                from_memory[i] = true;
                BOOST_LOG_TRIVIAL(warning) << "Sync filaments: slot " << i + 1 << " has a tag chosen for before; its preset is offered again: " << preset->name;
            }
        }

    // Nothing changes before the user has seen what would change and said so.
    if (Plater* plater = wxGetApp().plater())
        plater->update_all_plate_thumbnails(true); // the plate pictures the dialog recolors
    // A slot of a filament changer whose brand the printer reports, with no preset of that brand for the material:
    // one is made from the generic preset of the material when the sync is applied, so that the project names the
    // filament by its brand. (A toolhead's brand without a preset goes through the mapping dialog as before.)
    std::vector<std::optional<FilamentProfileRecord>> brand_preset(slots.size());
    std::vector<std::string>                          to_make(slots.size());
    for (size_t i = 0; s_lane_sync && i < slots.size(); ++i) {
        const std::string material = canonical_material(slots[i].type, slots[i].sub_type);
        if (!slots[i].present || slots[i].vendor.empty() || boost::iequals(slots[i].vendor, "Generic") || material.empty() || automatic[i].empty() || from_memory[i])
            continue; // nothing to make a brand preset from, or the person already chose a preset for this spool's tag
        const Preset* chosen = preset_named(bundle, automatic[i]);
        if (chosen != nullptr && boost::iequals(chosen->config.opt_string("filament_vendor", 0u), slots[i].vendor))
            continue;
        FilamentProfileRecord record;
        record.brand    = slots[i].vendor;
        record.material = material;
        // The made preset is a copy of the material's generic one (catalog_preset); without that one there is nothing
        // to copy, and the slot keeps the preset picked for it.
        const bool has_generic = std::any_of(bundle.filaments.get_presets().begin(), bundle.filaments.get_presets().end(), [&material](const Preset& preset) {
            return preset.is_system && preset.is_compatible && boost::iequals(preset_base_name(preset.name), "Generic " + material);
        });
        if (!filament_profile_record_is_usable(record) || !has_generic)
            continue;
        brand_preset[i] = record;
        to_make[i]      = filament_profile_product_name(record);
    }
    U1SyncDialog ask(parent, slots, automatic, bundle, to_make);
    if (ask.ShowModal() != wxID_OK)
        return false;
    const SyncChoice choice = ask.choice();

    // Per project filament, the toolhead it takes its preset and color from.
    // It is indexed by project slot; a mixed filament's slot stays -1.
    const std::vector<size_t> own_slots = physical_slots(bundle);
    const bool mixed_last = own_slots.empty() || own_slots.back() + 1 == own_slots.size();
    std::vector<int> target;
    size_t           count = 4; // overwriting: the U1's four toolheads, the k-th project filament being toolhead k
    if (choice.mapping) {
        count = own_slots.size();
        target.assign(own_slots.empty() ? size_t(0) : own_slots.back() + 1, -1);
        for (size_t k = 0; k < own_slots.size() && k < choice.toolhead_for_filament.size(); ++k)
            target[own_slots[k]] = choice.toolhead_for_filament[k];
        if (!choice.add_unused && std::all_of(target.begin(), target.end(), [](int toolhead) { return toolhead < 0; }))
            return false; // every filament was left as it is
    } else if (mixed_last) {
        for (size_t i = 0; i < slots.size(); ++i)
            target.push_back(slots[i].present ? int(i) : -1);
    } else if (own_slots.size() >= count) {
        target.assign(own_slots[count - 1] + 1, -1);
        for (size_t k = 0; k < count; ++k)
            target[own_slots[k]] = slots[k].present ? int(k) : -1;
        count = own_slots.size();
    } else {
        // A filament would have to be added, and one is always added behind the last ordinary filament.
        MessageDialog(parent,
                      s_lane_sync ? _L("This project lists a mixed filament before one of its ordinary filaments, and it has fewer filaments than the printer has slots. "
                                       "Remove the mixed filaments or use Mapping, then synchronize again.") :
                                    _L("This project lists a mixed filament before one of its ordinary filaments, and it has fewer filaments than the printer has toolheads. "
                                       "Remove the mixed filaments or use Mapping, then synchronize again."),
                      _L("Sync filaments"), wxOK | wxICON_WARNING)
            .ShowModal();
        return false;
    }

    // The presets are picked as OrcaSlicer picks them; the mapping dialog is for choosing them by hand,
    // and for a toolhead no preset could be picked for.
    bool needs_review = choice.choose_presets;
    for (const int toolhead : target)
        needs_review = needs_review || (toolhead >= 0 && automatic[size_t(toolhead)].empty());
    // The preset a toolhead's filament has once applied, for the filaments added below.
    std::vector<std::string> applied = automatic;
    if (needs_review) {
        U1FilamentMappingDialog mapping(parent, slots, report_source, bundle);
        if (mapping.ShowModal() != wxID_OK || !apply([&mapping](size_t i) { return mapping.selection(i); }, target, count))
            return false;
        for (size_t i = 0; i < slots.size(); ++i)
            if (const std::optional<MappingChoice> chosen = mapping.selection(i); chosen && !chosen->preset_name.empty())
                applied[i] = chosen->preset_name;
        // What the person chose by hand for a spool with a tag is remembered for that tag on this printer model.
        bool remembered_any = false;
        for (size_t i = 0; i < slots.size(); ++i)
            if (const std::optional<MappingChoice> chosen = mapping.selection(i);
                chosen && !chosen->keep_current && !chosen->preset_name.empty() && slots[i].present && !slots[i].tag_id.empty())
                remembered_any = tag_memory.remember(slots[i].tag_id, printer_model, chosen->preset_name, U1Jobs::now_ms()) || remembered_any;
        if (remembered_any && !tag_memory.save(SpoolTags::memory_path()))
            BOOST_LOG_TRIVIAL(warning) << "Sync filaments: the choice for a spool's tag could not be kept";
    } else {
        if (!apply([&](size_t i) { return std::optional<MappingChoice>(MappingChoice{brand_preset[i] ? std::string() : automatic[i], brand_preset[i], false}); },
                   target, count))
            return false;
        // The presets made, or found already made, under their brands.
        for (size_t i = 0; i < slots.size(); ++i)
            if (brand_preset[i])
                for (const Preset& preset : bundle.filaments.get_presets())
                    if (preset.is_compatible && boost::iequals(preset_base_name(preset.name), to_make[i]) && u1_catalog_preset_matches_record(preset, *brand_preset[i])) {
                        applied[i] = preset.name;
                        break;
                    }
    }

    // Project filaments that now are the same toolhead's filament become one, last to first so that the
    // indices still to be merged stay valid.
    if (choice.mapping && choice.merge_same && bundle.num_mixed_filaments() == 0) {
        for (size_t from = target.size(); from-- > 1;) {
            if (target[from] < 0)
                continue;
            const auto first = std::find(target.begin(), target.begin() + long(from), target[from]);
            if (first == target.begin() + long(from))
                continue;
            const size_t to = size_t(first - target.begin());
            wxGetApp().sidebar().change_filament(from, to);
        }
    }

    // Loaded toolheads no project filament was mapped to join the list as new filaments, through the
    // same call as the sidebar's "Add one filament". A toolhead without a known preset is not added.
    if (choice.mapping && choice.add_unused && bundle.num_mixed_filaments() == 0) {
        // A slot about to be added whose brand has no preset yet gets one now (the slots mapped above got theirs
        // when the mapping was applied). Where that fails the slot is added with the preset picked for it.
        for (size_t i = 0; i < slots.size(); ++i)
            if (slots[i].present && brand_preset[i] && !needs_review && std::find(target.begin(), target.end(), int(i)) == target.end()) {
                std::vector<std::string> made;
                std::string              make_error;
                if (const CatalogPresetResult result = catalog_preset(bundle, *brand_preset[i], slots[i], made, make_error); !result.preset_name.empty())
                    applied[i] = result.preset_name;
                else
                    BOOST_LOG_TRIVIAL(warning) << "Sync filaments: no preset made for slot " << i << ": " << make_error;
            }
        bool added = false;
        for (size_t i = 0; i < slots.size(); ++i)
            if (slots[i].present && !applied[i].empty() && std::find(target.begin(), target.end(), int(i)) == target.end()) {
                wxGetApp().sidebar().add_custom_filament(colour_from_hex(slots[i].color, wxColour(0xCE, 0xCE, 0xCE)), applied[i]);
                added = true;
            }
        // The new filament's selector is created before its preset is set; without this it keeps showing the
        // preset the filament was created with.
        if (added)
            wxGetApp().sidebar().update_all_preset_comboboxes();
    }

    // What the project holds now, for the record of a sync.
    {
        std::string now;
        for (const std::string& name : bundle.filament_presets)
            now += (now.empty() ? "" : " | ") + name;
        BOOST_LOG_TRIVIAL(warning) << "Sync filaments: the project's filament presets are now: " << now;
    }

    wxString invalid_colors;
    for (size_t i = 0; i < slots.size(); ++i) {
        if (!slots[i].present || !slots[i].color_invalid || std::find(target.begin(), target.end(), int(i)) == target.end())
            continue;
        if (!invalid_colors.empty())
            invalid_colors += ", ";
        invalid_colors += wxString::Format("T%d", int(i));
    }
    if (!invalid_colors.empty())
        MessageDialog(parent,
                      format_wxstr(_L("The printer reported no usable color for %1%. The project colors of the filaments mapped there were kept."),
                                   invalid_colors),
                      _L("Sync filaments"), wxOK | wxICON_WARNING)
            .ShowModal();
    return true;
}

} // namespace

bool find_filament_in_catalog(wxWindow* parent)
{
    FilamentCatalogSearchDialog dialog(parent);
    dialog.ShowModal();
    return !dialog.added().empty();
}

} // namespace Slic3r::GUI
