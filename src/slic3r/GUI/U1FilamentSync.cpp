#include "U1FilamentSync.hpp"

#include <algorithm>
#include <cstdint>
#include <optional>
#include <vector>

#include <boost/algorithm/string/case_conv.hpp>
#include <boost/algorithm/string/predicate.hpp>
#include <nlohmann/json.hpp>
#include <wx/utils.h>

#include "GUI_App.hpp"
#include "I18N.hpp"
#include "MsgDialog.hpp"
#include "NativePrinterPanel.hpp"
#include "Plater.hpp"
#include "format.hpp"
#include "GUI.hpp"
#include "Tab.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "slic3r/Utils/U1LanPrintHost.hpp"

namespace Slic3r::GUI {

std::size_t u1_physical_filament_capacity(const DynamicPrintConfig &printer_config)
{
    const std::string model = printer_config.has("printer_model") ? printer_config.opt_string("printer_model") : std::string();
    return boost::iequals(model, "Snapmaker U1") ? 4u : 0u;
}

std::uint64_t u1_filament_preset_rank(const Preset &preset, const std::string &reported_vendor, const std::string &reported_sub_type)
{
    const std::string vendor        = boost::to_lower_copy(reported_vendor);
    const std::string sub_type      = boost::to_lower_copy(reported_sub_type);
    const std::string name          = boost::to_lower_copy(preset.name);
    const std::string preset_vendor = boost::to_lower_copy(preset.config.opt_string("filament_vendor", 0u));
    const bool vendor_known         = !vendor.empty() && vendor != "none";
    const bool vendor_match = vendor_known && preset_vendor == vendor;
    const bool subtype_match = !sub_type.empty() && sub_type != "none" && boost::contains(name, sub_type);
    const bool generic_match = boost::contains(name, "generic");

    std::uint64_t rank = vendor_match ? 1 : 0;
    rank               = rank * 2 + (generic_match ? 1 : 0);
    rank               = rank * 2 + (subtype_match ? 1 : 0);
    rank               = rank * 2 + (preset.is_system ? 1 : 0);
    rank               = rank * 2 + (preset.is_visible ? 1 : 0);
    return rank * 1000 + (999 - std::min<std::size_t>(name.size(), 999));
}

void set_u1_filament_color(DynamicPrintConfig &config, std::size_t index, const std::string &color)
{
    auto set_value = [index](ConfigOptionStrings *option, const std::string &value, const std::string &fill) {
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

namespace {

struct Slot
{
    bool        present{false};
    std::string vendor;
    std::string type;
    std::string sub_type;
    std::string color; // "#RRGGBB"
};

std::string json_string(const nlohmann::json &arr, size_t i)
{
    if (!arr.is_array() || i >= arr.size() || !arr[i].is_string())
        return {};
    return arr[i].get<std::string>();
}

std::string normalize_color(const nlohmann::json &report, size_t i)
{
    std::string c = json_string(report.value("filament_color_rgba", nlohmann::json::array()), i);
    if (c.empty())
        c = json_string(report.value("filament_color", nlohmann::json::array()), i);
    if (!c.empty() && c[0] == '#')
        c.erase(0, 1);
    if (c.size() < 6)
        return {};
    c = c.substr(0, 6);
    for (char &ch : c)
        ch = char(std::toupper(static_cast<unsigned char>(ch)));
    return "#" + c;
}

std::vector<Slot> parse_report(const nlohmann::json &report)
{
    std::vector<Slot> slots;
    const auto        types = report.value("filament_type", nlohmann::json::array());
    if (!types.is_array())
        return slots;
    const auto exist = report.value("filament_exist", nlohmann::json::array());
    for (size_t i = 0; i < types.size(); ++i) {
        Slot s;
        s.type     = json_string(types, i);
        s.vendor   = json_string(report.value("filament_vendor", nlohmann::json::array()), i);
        s.sub_type = json_string(report.value("filament_sub_type", nlohmann::json::array()), i);
        s.color    = normalize_color(report, i);
        const bool flagged = exist.is_array() && i < exist.size() && exist[i].is_boolean() ? exist[i].get<bool>() : true;
        s.present          = flagged && !s.type.empty() && s.type != "NONE" && !s.color.empty();
        slots.push_back(std::move(s));
    }
    return slots;
}

// Best compatible filament preset of the given type; prefers the vendor named by the printer,
// then a Generic fallback, then a plain name match on the subtype.
const Preset *pick_preset(PresetBundle &bundle, const Slot &slot)
{
    const Preset *best      = nullptr;
    std::uint64_t best_rank = 0;
    for (const Preset &p : bundle.filaments.get_presets()) {
        if (!p.is_compatible || p.is_default)
            continue;
        if (p.config.opt_string("filament_type", 0u) != slot.type)
            continue;

        // Rank identity before presentation state. Visibility only means the user enabled a preset;
        // it must never make an unrelated brand beat the printer-reported vendor or a generic fallback.
        const std::uint64_t rank = u1_filament_preset_rank(p, slot.vendor, slot.sub_type);
        // Equal semantic ranks are resolved by the stable preset name instead of collection order.
        if (best == nullptr || rank > best_rank || (rank == best_rank && p.name < best->name)) {
            best_rank = rank;
            best      = &p;
        }
    }
    return best;
}

} // namespace

bool u1_filament_sync_available()
{
    PresetBundle *bundle = wxGetApp().preset_bundle;
    if (bundle == nullptr)
        return false;
    const DynamicPrintConfig &printer = bundle->printers.get_edited_preset().config;
    return u1_physical_filament_capacity(printer) != 0 && !resolve_printer_lan_host().empty();
}

bool sync_filaments_from_u1(wxWindow *parent)
{
    const std::string host = resolve_printer_lan_host();
    if (host.empty())
        return false;

    wxString                      error;
    std::optional<nlohmann::json> report;
    {
        wxBusyCursor wait;
        report = U1LanPrintHost::query_filament_report(host, error);
    }
    if (!report) {
        MessageDialog(parent, format_wxstr(_L("Could not read the filaments from the Snapmaker U1 at %s.\n\n%s"), from_u8(host), error),
                      _L("Sync filaments"), wxOK | wxICON_WARNING)
            .ShowModal();
        return false;
    }

    const std::vector<Slot> slots = parse_report(*report);
    size_t                  count = 0;
    for (size_t i = 0; i < slots.size(); ++i)
        if (slots[i].present)
            count = i + 1;
    if (count == 0) {
        MessageDialog(parent, _L("The printer reports no loaded filaments."), _L("Sync filaments"), wxOK | wxICON_INFORMATION).ShowModal();
        return false;
    }

    PresetBundle &bundle = *wxGetApp().preset_bundle;
    Plater       *plater = wxGetApp().plater();
    if (bundle.filament_presets.size() != count)
        plater->on_filament_count_change(count);

    wxString summary;
    size_t   applied = 0;
    for (size_t i = 0; i < count && i < bundle.filament_presets.size(); ++i) {
        const Slot &slot = slots[i];
        if (!slot.present) {
            summary += wxString::Format("T%d: %s\n", int(i), _L("empty (unchanged)"));
            continue;
        }
        const Preset *preset = pick_preset(bundle, slot);
        if (preset != nullptr)
            bundle.set_filament_preset(i, preset->name);
        // Solid filament colors have three parallel project-config representations. The sidebar
        // renders filament_multi_colour when it is present, so updating only filament_colour leaves
        // the visible swatch (and mixed-color consumers) on the previous value.
        set_u1_filament_color(bundle.project_config, i, slot.color);
        ++applied;
        summary += wxString::Format("T%d: %s %s %s -> %s\n", int(i), from_u8(slot.vendor), from_u8(slot.type), from_u8(slot.color),
                                    preset ? from_u8(preset->name) : _L("no matching preset (colour only)"));
    }

    plater->update_filament_colors_in_full_config();
    plater->sidebar().update_all_preset_comboboxes();
    if (Tab *tab = wxGetApp().get_tab(Preset::TYPE_FILAMENT))
        tab->select_preset(bundle.filament_presets[0]);
    bundle.export_selections(*wxGetApp().app_config);

    MessageDialog(parent, _L("Filaments synchronized from the printer:") + "\n\n" + summary, _L("Sync filaments"), wxOK | wxICON_INFORMATION)
        .ShowModal();
    return applied > 0;
}

} // namespace Slic3r::GUI
