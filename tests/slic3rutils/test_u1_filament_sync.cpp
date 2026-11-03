#include <catch2/catch_test_macros.hpp>

#include "libslic3r/Preset.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "slic3r/GUI/U1FilamentSync.hpp"

using namespace Slic3r;
using namespace Slic3r::GUI;

namespace {

Preset filament_preset(const std::string &name, const std::string &vendor, bool visible)
{
    Preset preset(Preset::TYPE_FILAMENT, name);
    preset.config = DynamicPrintConfig::full_print_config();
    preset.config.option<ConfigOptionStrings>("filament_vendor")->values = {vendor};
    preset.is_system  = true;
    preset.is_visible = visible;
    return preset;
}

} // namespace

TEST_CASE("U1 physical filament capacity is limited to its four toolheads", "[U1FilamentSync]")
{
    DynamicPrintConfig unspecified = DynamicPrintConfig::full_print_config();
    REQUIRE(u1_physical_filament_capacity(unspecified) == 0);

    DynamicPrintConfig u1 = DynamicPrintConfig::full_print_config();
    u1.set_key_value("printer_model", new ConfigOptionString("Snapmaker U1"));
    REQUIRE(u1_physical_filament_capacity(u1) == 4);

    DynamicPrintConfig other = DynamicPrintConfig::full_print_config();
    other.set_key_value("printer_model", new ConfigOptionString("Snapmaker Artisan"));
    REQUIRE(u1_physical_filament_capacity(other) == 0);

    DynamicPrintConfig similarly_named = DynamicPrintConfig::full_print_config();
    similarly_named.set_key_value("printer_model", new ConfigOptionString("Example U1"));
    REQUIRE(u1_physical_filament_capacity(similarly_named) == 0);

    DynamicPrintConfig future_model = DynamicPrintConfig::full_print_config();
    future_model.set_key_value("printer_model", new ConfigOptionString("Snapmaker U10"));
    REQUIRE(u1_physical_filament_capacity(future_model) == 0);
}

TEST_CASE("U1 filament matching ranks identity ahead of visibility", "[U1FilamentSync]")
{
    const Preset exact_hidden      = filament_preset("Snapmaker PLA @U1", "Snapmaker", false);
    const Preset unrelated_visible = filament_preset("COEX PLA+Silk @System", "COEX", true);
    const Preset generic_hidden    = filament_preset("Generic PLA @U1", "Generic", false);
    const Preset branded_subtype   = filament_preset("Overture ABS Basic @System", "Overture", true);
    const Preset generic_abs       = filament_preset("Generic ABS @U1", "Generic", false);
    const Preset substring_vendor  = filament_preset("SUNLU3D PLA @System", "SUNLU3D", true);

    REQUIRE(u1_filament_preset_rank(exact_hidden, "Snapmaker", "PLA") >
            u1_filament_preset_rank(unrelated_visible, "Snapmaker", "PLA"));
    REQUIRE(u1_filament_preset_rank(generic_hidden, "Unknown Brand", "PLA") >
            u1_filament_preset_rank(unrelated_visible, "Unknown Brand", "PLA"));
    REQUIRE(u1_filament_preset_rank(generic_abs, "SUNLU", "ABS Basic") >
            u1_filament_preset_rank(branded_subtype, "SUNLU", "ABS Basic"));
    REQUIRE(u1_filament_preset_rank(generic_hidden, "SUNLU", "PLA") >
            u1_filament_preset_rank(substring_vendor, "SUNLU", "PLA"));
}

TEST_CASE("U1 solid colors replace every rendered project color representation", "[U1FilamentSync]")
{
    DynamicPrintConfig config = DynamicPrintConfig::full_print_config();
    config.set_key_value("filament_colour", new ConfigOptionStrings{"#00FFFF"});
    config.set_key_value("filament_multi_colour", new ConfigOptionStrings{"#800080 #FFFFFF"});
    config.set_key_value("filament_colour_type", new ConfigOptionStrings{"0"});

    set_u1_filament_color(config, 0, "#DD0000");

    REQUIRE(config.option<ConfigOptionStrings>("filament_colour")->values[0] == "#DD0000");
    REQUIRE(config.option<ConfigOptionStrings>("filament_multi_colour")->values[0] == "#DD0000");
    REQUIRE(config.option<ConfigOptionStrings>("filament_colour_type")->values[0] == "1");

    set_u1_filament_color(config, 3, "#112233");
    REQUIRE(config.option<ConfigOptionStrings>("filament_colour")->values.size() == 4);
    REQUIRE(config.option<ConfigOptionStrings>("filament_multi_colour")->values.size() == 4);
    REQUIRE(config.option<ConfigOptionStrings>("filament_colour_type")->values.size() == 4);
    REQUIRE(config.option<ConfigOptionStrings>("filament_colour")->values[3] == "#112233");
}
