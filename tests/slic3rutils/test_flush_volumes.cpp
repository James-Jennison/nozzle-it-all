#include <catch2/catch_test_macros.hpp>

#include "libslic3r/Config.hpp"
#include "slic3r/GUI/Plater.hpp"

#include <limits>

using namespace Slic3r;

TEST_CASE("Minimum flush volumes tolerate empty and non-finite vectors", "[Config][CLI][FlushVolume]")
{
    DynamicPrintConfig config;
    config.set_key_value("filament_diameter", new ConfigOptionFloats{1.75, 1.75});
    config.set_key_value("nozzle_volume", new ConfigOptionFloatsNullable{});
    config.set_key_value("enable_long_retraction_when_cut", new ConfigOptionInt{LongRectrationLevel::EnableFilament});
    config.set_key_value("long_retractions_when_cut", new ConfigOptionBools{});
    config.set_key_value("retraction_distances_when_cut", new ConfigOptionFloats{});
    config.set_key_value("filament_retraction_distances_when_cut", new ConfigOptionFloats{});
    config.set_key_value("filament_long_retractions_when_cut", new ConfigOptionBools{});

    CHECK(GUI::get_min_flush_volumes(config, 3) == std::vector<int>{0, 0});

    config.set_key_value("nozzle_volume", new ConfigOptionFloatsNullable{std::numeric_limits<double>::quiet_NaN()});
    config.set_key_value("long_retractions_when_cut", new ConfigOptionBools{true});
    config.set_key_value("retraction_distances_when_cut", new ConfigOptionFloats{std::numeric_limits<double>::infinity()});
    config.set_key_value("filament_retraction_distances_when_cut", new ConfigOptionFloats{std::numeric_limits<double>::quiet_NaN()});
    config.set_key_value("filament_long_retractions_when_cut", new ConfigOptionBools{true});

    const int fallback_volume = int(-PI * 1.75 * 1.75 / 4 * 18.0);
    CHECK(GUI::get_min_flush_volumes(config, 3) == std::vector<int>{fallback_volume, fallback_volume});
}

TEST_CASE("Minimum flush volumes broadcast short vectors without indexing past them", "[Config][CLI][FlushVolume]")
{
    DynamicPrintConfig config;
    config.set_key_value("filament_diameter", new ConfigOptionFloats{1.75, 1.75, 1.75});
    config.set_key_value("nozzle_volume", new ConfigOptionFloatsNullable{100.0});
    config.set_key_value("enable_long_retraction_when_cut", new ConfigOptionInt{LongRectrationLevel::EnableFilament});
    config.set_key_value("long_retractions_when_cut", new ConfigOptionBools{true});
    config.set_key_value("retraction_distances_when_cut", new ConfigOptionFloats{12.0});
    config.set_key_value("filament_retraction_distances_when_cut", new ConfigOptionFloats{10.0});
    config.set_key_value("filament_long_retractions_when_cut", new ConfigOptionBools{true});

    const int expected = int(100.0 - PI * 1.75 * 1.75 / 4 * 10.0);
    CHECK(GUI::get_min_flush_volumes(config, 4) == std::vector<int>{expected, expected, expected});
}
