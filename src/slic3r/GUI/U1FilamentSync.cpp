#include "U1FilamentSync.hpp"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <functional>
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
#include <nlohmann/json.hpp>
#include <wx/sizer.h>
#include <wx/stattext.h>
#include <wx/utils.h>

#include "CreatePresetsDialog.hpp"
#include "DeviceManager.hpp"
#include "DeviceCore/DevFilaSystem.h"
#include "DeviceCore/DevManager.h"
#include "GUI_App.hpp"
#include "GUI_Utils.hpp"
#include "I18N.hpp"
#include "MsgDialog.hpp"
#include "NIATheme.hpp"
#include "NativePrinterPanel.hpp"
#include "Plater.hpp"
#include "format.hpp"
#include "GUI.hpp"
#include "Tab.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/ComboBox.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/TextInput.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "slic3r/Utils/FilamentProfilesClient.hpp"
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
    if (color.size() == 8 && !boost::iequals(color.substr(6), "FF"))
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

CatalogPresetResult catalog_preset(PresetBundle& bundle, const FilamentProfileRecord& record, const Slot& reported,
                                   std::vector<std::string>& transaction_created_presets, std::string& error)
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
    const auto name_exists = [&](const std::string& candidate) {
        return std::any_of(bundle.filaments.get_presets().begin(), bundle.filaments.get_presets().end(), [&](const Preset& preset) {
            return boost::iequals(preset_base_name(preset.name), candidate);
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
    notes += "; Snapmaker U1 reported " + (reported.from_tag ? std::string("RFID") : std::string("printer setting"));
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
    for (const Preset& preset : bundle.filaments.get_presets()) {
        if (!preset.is_compatible || preset.is_default ||
            !boost::iequals(canonical_material(preset.config.opt_string("filament_type", 0u)), reported_material))
            continue;
        const std::uint64_t rank = u1_filament_preset_rank(preset, slot.vendor, slot.sub_type);
        if (best == nullptr || rank > best_rank) {
            best      = &preset;
            best_rank = rank;
        }
    }
    return best != nullptr ? best->name : std::string();
}

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
        auto* heading = new wxStaticText(this, wxID_ANY, _L("Map the material in each toolhead"));
        heading->SetFont(Label::Head_18);
        heading->SetForegroundColour(NIATheme::text(dark));
        root->Add(heading, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, em);
        auto* explanation = new wxStaticText(
            this, wxID_ANY,
            _L("Review every loaded toolhead. Applying expands the project to the U1's fixed T0-T3 slots; empty toolheads keep their current project slot."));
        explanation->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(explanation, 0, wxALL | wxEXPAND, em);
        auto* source = new wxStaticText(this, wxID_ANY, wxString::Format(_L("Report source: %s"), report_source));
        source->SetForegroundColour(NIATheme::text_muted(dark));
        root->Add(source, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, em);
        auto* grid = new wxFlexGridSizer(3, em / 2, em);
        grid->AddGrowableCol(2, 1);
        for (const wxString& label : {_L("Toolhead"), _L("RFID / printer report"), _L("Filament preset")}) {
            auto* column = new wxStaticText(this, wxID_ANY, label);
            column->SetFont(Label::Head_13);
            column->SetForegroundColour(NIATheme::text_muted(dark));
            grid->Add(column, 0, wxBOTTOM, em / 2);
        }
        for (size_t i = 0; i < slots.size(); ++i) {
            const Slot& slot = slots[i];
            grid->Add(new wxStaticText(this, wxID_ANY, wxString::Format("T%d", int(i))), 0, wxALIGN_CENTER_VERTICAL);
            if (!slot.present) {
                auto* empty = new wxStaticText(this, wxID_ANY, _L("Empty toolhead"));
                empty->SetName(format_wxstr(_L("Toolhead T%1%: empty toolhead"), int(i)));
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
            row.control->SetName(format_wxstr(_L("Filament preset for toolhead T%1%. Printer report: %2%"), int(i), report_identity));
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
        m_status = new wxStaticText(this, wxID_ANY, _L("Choose a preset for each toolhead, or look the RFID products up in 3D Filament Profiles."));
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
                                  _L("At least one loaded toolhead has no material family in the printer report. Apply the selected presets as explicit manual overrides for those unresolved toolheads?"),
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
            m_status->SetLabel(format_wxstr(_L("Catalog lookup could not start for toolhead T%1%."), int(index)));
            finish_catalog_search(false);
        }
    }

    void on_catalog_reply(size_t index, FilamentProfilesSearchReply reply)
    {
        m_catalog_request.reset();
        if (!reply.ok) {
            m_status->SetLabel(format_wxstr(_L("Catalog lookup failed for toolhead T%1%: %2%"), int(index), from_u8(reply.error)));
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

bool u1_filament_sync_available()
{
    PresetBundle* bundle = wxGetApp().preset_bundle;
    if (bundle == nullptr)
        return false;
    const DynamicPrintConfig& printer = bundle->printers.get_edited_preset().config;
    if (u1_physical_filament_capacity(printer) == 0)
        return false;
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

    if (bundle.filament_presets.size() > 4) {
        MessageDialog(parent,
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
    // put back as it was and the reason is shown.
    const auto apply = [&](const std::function<std::optional<MappingChoice>(size_t)>& choice_for) -> bool {
    Plater* plater = wxGetApp().plater();
    // The U1 has four fixed physical toolheads. Project slots intentionally
    // retain the same physical indices even when occupancy is sparse.
    constexpr size_t count = 4;
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
            if (!slots[i].present)
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
            if (slots[i].present && !keep_current[i]) {
                const Preset* preset = preset_named(bundle, selected[i]);
                if (preset == nullptr || !preset->is_compatible)
                    throw std::runtime_error((boost::format("The selected preset for toolhead T%1% is no longer compatible.") % i).str());
            }

        if (original_presets.size() != count)
            plater->on_filament_count_change(count);
        if (bundle.filament_presets.size() != count)
            throw std::runtime_error("The project did not expose four physical filament slots.");
        bundle.update_compatible(PresetSelectCompatibleType::Always);
        for (size_t i = 0; i < slots.size(); ++i) {
            if (!slots[i].present)
                continue;
            const std::string& preset_name = keep_current[i] ? bundle.filament_presets[i] : selected[i];
            const Preset* preset = preset_named(bundle, preset_name);
            if (preset == nullptr || !preset->is_compatible)
                throw std::runtime_error((boost::format("The selected preset for toolhead T%1% became incompatible after exposing the U1 slots.") % i).str());
            if (keep_current[i] && !u1_current_preset_matches_reported_material(*preset, slots[i]))
                throw std::runtime_error((boost::format("The current project preset for toolhead T%1% no longer matches the reported material.") % i).str());
        }
        for (size_t i = 0; i < slots.size() && i < bundle.filament_presets.size(); ++i) {
            if (!slots[i].present)
                continue;
            if (!keep_current[i])
                bundle.set_filament_preset(i, selected[i]);
            if (!slots[i].color.empty())
                set_u1_filament_color(bundle.project_config, i, slots[i].color);
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

    // As in OrcaSlicer, synchronising picks the presets itself and says what it picked. The mapping
    // dialog is for changing that, and for a toolhead no preset could be picked for.
    std::vector<std::string> automatic(slots.size());
    bool all_automatic = true;
    for (size_t i = 0; i < slots.size(); ++i)
        if (slots[i].present) {
            automatic[i] = automatic_preset(bundle, slots[i]);
            all_automatic = all_automatic && !automatic[i].empty();
        }
    const auto review = [&]() {
        U1FilamentMappingDialog mapping(parent, slots, report_source, bundle);
        return mapping.ShowModal() == wxID_OK && apply([&mapping](size_t i) { return mapping.selection(i); });
    };
    bool reviewed = !all_automatic;
    if (all_automatic) {
        if (!apply([&automatic](size_t i) { return std::optional<MappingChoice>(MappingChoice{automatic[i], std::nullopt, false}); }))
            return false;
    } else if (!review())
        return false;

    wxString invalid_colors;
    for (size_t i = 0; i < slots.size(); ++i) {
        if (!slots[i].present || !slots[i].color_invalid)
            continue;
        if (!invalid_colors.empty())
            invalid_colors += ", ";
        invalid_colors += wxString::Format("T%d", int(i));
    }
    const auto summary = [&]() {
        wxString text = _L("Filaments synchronized from the printer:") + "\n\n";
        for (size_t i = 0; i < slots.size(); ++i) {
            if (!slots[i].present) {
                text += format_wxstr(_L("T%1%: empty (unchanged)"), int(i)) + "\n";
                continue;
            }
            const Preset* preset = i < bundle.filament_presets.size() ? preset_named(bundle, bundle.filament_presets[i]) : nullptr;
            // TRN toolhead number, then what the printer reports (vendor, material, colour), then the preset chosen for it
            text += format_wxstr(_L("T%1%: %2% %3% %4% -> %5%"), int(i), from_u8(slots[i].vendor), from_u8(slots[i].type),
                                 from_u8(slots[i].color), preset != nullptr ? from_u8(preset->label(false)) : wxString()) + "\n";
        }
        if (!invalid_colors.empty())
            text += "\n" + format_wxstr(_L("The printer reported an invalid color for toolheads %1%; their existing project colors were kept."),
                                        invalid_colors);
        return text;
    };
    MessageDialog done(parent, summary(), _L("Sync filaments"), wxOK | wxICON_INFORMATION);
    if (!reviewed)
        done.AddButton(wxID_EDIT, _L("Change…"));
    if (done.ShowModal() == wxID_EDIT && review())
        MessageDialog(parent, summary(), _L("Sync filaments"), wxOK | wxICON_INFORMATION).ShowModal();
    return true;
}

} // namespace Slic3r::GUI
