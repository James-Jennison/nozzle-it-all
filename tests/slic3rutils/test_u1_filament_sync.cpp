#include <catch2/catch_test_macros.hpp>
#include <nlohmann/json.hpp>

#include <string>
#include <utility>
#include <vector>

#include "libslic3r/Preset.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "slic3r/GUI/U1FilamentSync.hpp"
#include "slic3r/Utils/FilamentProfilesClient.hpp"

using namespace Slic3r;
using namespace Slic3r::GUI;

namespace {

Preset filament_preset(const std::string& name, const std::string& vendor, bool visible)
{
    Preset preset(Preset::TYPE_FILAMENT, name);
    preset.config                                                        = DynamicPrintConfig::full_print_config();
    preset.config.option<ConfigOptionStrings>("filament_vendor")->values = {vendor};
    preset.is_system                                                     = true;
    preset.is_visible                                                    = visible;
    return preset;
}

Preset legacy_filament_preset(const std::string& name, bool visible)
{
    Preset preset = filament_preset(name, "", visible);
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
    const Preset embedded_vendor   = legacy_filament_preset("eSun PLA @System", true);
    const Preset legacy_snapmaker  = legacy_filament_preset("Snapmaker PLA Legacy @U1", false);

    REQUIRE(u1_filament_preset_rank(exact_hidden, "Snapmaker", "PLA") > u1_filament_preset_rank(unrelated_visible, "Snapmaker", "PLA"));
    REQUIRE(u1_filament_preset_rank(generic_hidden, "Unknown Brand", "PLA") >
            u1_filament_preset_rank(unrelated_visible, "Unknown Brand", "PLA"));
    REQUIRE(u1_filament_preset_rank(generic_abs, "SUNLU", "ABS Basic") > u1_filament_preset_rank(branded_subtype, "SUNLU", "ABS Basic"));
    REQUIRE(u1_filament_preset_rank(generic_hidden, "SUNLU", "PLA") > u1_filament_preset_rank(substring_vendor, "SUNLU", "PLA"));
    REQUIRE(u1_filament_preset_rank(generic_hidden, "Sun", "PLA") > u1_filament_preset_rank(embedded_vendor, "Sun", "PLA"));
    REQUIRE(u1_filament_preset_rank(legacy_snapmaker, "Snapmaker", "PLA") > u1_filament_preset_rank(unrelated_visible, "Snapmaker", "PLA"));
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

TEST_CASE("U1 reported colors accept RGB or RGBA and reject malformed or fully transparent values", "[U1FilamentSync]")
{
    CHECK(normalize_u1_filament_color("#dd0000ff") == "#DD0000");
    CHECK(normalize_u1_filament_color("112233") == "#112233");
    CHECK(normalize_u1_filament_color("112233FF") == "#112233");
    CHECK(normalize_u1_filament_color("11223344") == "#112233");
    CHECK(normalize_u1_filament_color("08ABFBE3") == "#08ABFB");
    CHECK(normalize_u1_filament_color("11223300").empty());
    CHECK(normalize_u1_filament_color("#12GG56").empty());
    CHECK(normalize_u1_filament_color("#12345").empty());
    CHECK(normalize_u1_filament_color("#123456789").empty());
}

TEST_CASE("U1 report preserves RFID origin and physical slot identity", "[U1FilamentSync]")
{
    const nlohmann::json report = {
        {"filament_vendor", {"Polymaker", "Generic", "Snapmaker", ""}},
        {"filament_type", {"TPU", "PLA", "PLA", "NONE"}},
        {"filament_sub_type", {"TPU 95A", "Matte", "Silk", ""}},
        {"filament_color_rgba", {"#DD0000FF", "#000000FF", "#D81B60FF", ""}},
        {"filament_official", {true, false, 1, false}},
        {"filament_exist", {true, true, true, false}},
    };

    const std::vector<U1ReportedFilament> slots = parse_u1_filament_report(report);
    REQUIRE(slots.size() == 4);
    CHECK(slots[0].present);
    CHECK(slots[0].from_tag);
    CHECK(slots[0].vendor == "Polymaker");
    CHECK(slots[0].color == "#DD0000");
    CHECK_FALSE(slots[1].from_tag);
    CHECK(slots[2].from_tag);
    CHECK_FALSE(slots[3].present);
}

TEST_CASE("U1 report selection prefers same-printer RFID identity without combining sources", "[U1FilamentSync]")
{
    std::vector<U1ReportedFilament> direct(4);
    direct[0].present = true;
    direct[0].type = "PLA";
    std::vector<U1ReportedFilament> agent(4);
    agent[0].present = true;
    agent[0].from_tag = true;
    agent[0].vendor = "Polymaker";

    CHECK(u1_authoritative_report_source(direct, agent, true) == U1ReportSource::PrinterAgent);
    CHECK(u1_authoritative_report_source(direct, agent, false) == U1ReportSource::DirectLan);
    agent[0].from_tag = false;
    agent[0].type = "PETG";
    CHECK(u1_authoritative_report_source(direct, agent, true) == U1ReportSource::Conflict);
    agent[0].type = "PLA";
    agent[0].vendor.clear();
    CHECK(u1_authoritative_report_source(direct, agent, true) == U1ReportSource::DirectLan);
    agent[0].color_invalid = true;
    CHECK(u1_authoritative_report_source(direct, agent, true) == U1ReportSource::Conflict);
    agent[0].color_invalid = false;
    direct[0].present = false;
    CHECK(u1_authoritative_report_source(direct, agent, true) == U1ReportSource::Conflict);
    direct.clear();
    CHECK(u1_authoritative_report_source(direct, agent, true) == U1ReportSource::PrinterAgent);
    CHECK(u1_authoritative_report_source(direct, agent, false) == U1ReportSource::None);
}

TEST_CASE("U1 printer-agent identity rejects unrelated selected devices", "[U1FilamentSync]")
{
    CHECK(u1_printer_agent_identity_matches("Snapmaker U1", "", "", {}));
    CHECK(u1_printer_agent_identity_matches("", "Snapmaker U1 (0.4 nozzle)", "", {}));
    CHECK(u1_printer_agent_identity_matches("", "", "U1", {}));
    CHECK(u1_printer_agent_identity_matches("unknown", "Unknown", "", {"255"}));

    CHECK_FALSE(u1_printer_agent_identity_matches("Snapmaker U10", "", "", {}));
    CHECK_FALSE(u1_printer_agent_identity_matches("X1C", "Bambu Lab X1 Carbon", "", {"0", "1"}));
    CHECK_FALSE(u1_printer_agent_identity_matches("unknown", "Unknown", "", {"251", "256", "rack-0"}));
}

TEST_CASE("U1 report never treats missing or malformed presence data as loaded", "[U1FilamentSync]")
{
    const nlohmann::json missing_presence = {
        {"filament_type", {"PLA"}},
        {"filament_color_rgba", {"#123456FF"}},
    };
    const auto missing_slots = parse_u1_filament_report(missing_presence);
    REQUIRE(missing_slots.size() == 4);
    CHECK_FALSE(missing_slots[0].present);

    const nlohmann::json malformed_presence = {
        {"filament_type", {"PLA"}},
        {"filament_color_rgba", {"#123456FF"}},
        {"filament_exist", {"yes"}},
    };
    const auto malformed_slots = parse_u1_filament_report(malformed_presence);
    REQUIRE(malformed_slots.size() == 4);
    CHECK_FALSE(malformed_slots[0].present);
}

TEST_CASE("U1 report preserves a loaded physical slot when its type entry is missing", "[U1FilamentSync]")
{
    const nlohmann::json report = {
        {"filament_type", nlohmann::json::array()},
        {"filament_vendor", {"Polymaker"}},
        {"filament_exist", {true, false, false, false}},
    };

    const auto slots = parse_u1_filament_report(report);
    REQUIRE(slots.size() == 4);
    CHECK(slots[0].present);
    CHECK(slots[0].type.empty());
    CHECK(slots[0].vendor == "Polymaker");
    CHECK_FALSE(slots[1].present);
    CHECK_FALSE(slots[2].present);
    CHECK_FALSE(slots[3].present);
}

TEST_CASE("U1 report treats an explicit NONE type as an empty physical slot", "[U1FilamentSync]")
{
    const nlohmann::json report = {
        {"filament_type", {"NONE"}},
        {"filament_exist", {true}},
    };

    const auto slots = parse_u1_filament_report(report);
    REQUIRE(slots.size() == 4);
    CHECK_FALSE(slots[0].present);
}

TEST_CASE("U1 report preserves loaded identity while rejecting a malformed color", "[U1FilamentSync]")
{
    const nlohmann::json report = {
        {"filament_type", {"PLA"}},
        {"filament_color_rgba", {"#12GG56FF"}},
        {"filament_exist", {true}},
    };
    const auto slots = parse_u1_filament_report(report);
    REQUIRE(slots.size() == 4);
    CHECK(slots[0].present);
    CHECK(slots[0].color.empty());
    CHECK(slots[0].color_invalid);
}

TEST_CASE("U1 RFID reports never auto-select a Generic identity", "[U1FilamentSync]")
{
    Preset generic = filament_preset("Generic PLA @U1", "Generic", true);
    Preset snapmaker = filament_preset("Snapmaker PLA @U1", "Snapmaker", true);
    generic.config.option<ConfigOptionStrings>("filament_type")->values = {"PLA"};
    snapmaker.config.option<ConfigOptionStrings>("filament_type")->values = {"PLA"};
    U1ReportedFilament tagged;
    tagged.present = true;
    tagged.from_tag = true;
    tagged.vendor = "Generic";
    tagged.type = "PLA";

    CHECK_FALSE(u1_preset_matches_reported_identity(generic, tagged));
    tagged.from_tag = false;
    CHECK(u1_preset_matches_reported_identity(generic, tagged));
    tagged.vendor = "Snapmaker";
    CHECK(u1_preset_matches_reported_identity(snapmaker, tagged));
    tagged.type.clear();
    CHECK_FALSE(u1_preset_matches_reported_identity(snapmaker, tagged));
}

TEST_CASE("U1 keep-current requires compatible matching reported material", "[U1FilamentSync]")
{
    Preset pla = filament_preset("Current PLA @U1", "Example", true);
    pla.config.option<ConfigOptionStrings>("filament_type")->values = {"PLA"};
    pla.is_compatible = true;

    U1ReportedFilament reported;
    reported.present = true;
    reported.type = "PLA";
    CHECK(u1_current_preset_matches_reported_material(pla, reported));

    reported.type = "PETG";
    CHECK_FALSE(u1_current_preset_matches_reported_material(pla, reported));
    reported.type.clear();
    CHECK_FALSE(u1_current_preset_matches_reported_material(pla, reported));

    reported.type = "PLA";
    pla.is_compatible = false;
    CHECK_FALSE(u1_current_preset_matches_reported_material(pla, reported));
}

TEST_CASE("U1 printer-agent virtual tray ids map only to physical toolheads", "[U1FilamentSync]")
{
    REQUIRE(u1_virtual_tray_toolhead_index("255") == 0);
    REQUIRE(u1_virtual_tray_toolhead_index("254") == 1);
    REQUIRE(u1_virtual_tray_toolhead_index("253") == 2);
    REQUIRE(u1_virtual_tray_toolhead_index("252") == 3);

    CHECK_FALSE(u1_virtual_tray_toolhead_index("251"));
    CHECK_FALSE(u1_virtual_tray_toolhead_index("256"));
    CHECK_FALSE(u1_virtual_tray_toolhead_index("-1"));
    CHECK_FALSE(u1_virtual_tray_toolhead_index("254x"));
    CHECK_FALSE(u1_virtual_tray_toolhead_index(""));
}

TEST_CASE("U1 mapping requires every loaded physical slot and ignores empty slots", "[U1FilamentSync]")
{
    std::vector<U1ReportedFilament> slots(4);
    slots[0].present = true;
    slots[2].present = true;

    CHECK_FALSE(u1_all_loaded_slots_mapped(slots, {}));
    CHECK_FALSE(u1_all_loaded_slots_mapped(slots, {true, false, false, false}));
    CHECK(u1_all_loaded_slots_mapped(slots, {true, false, true, false}));
}

TEST_CASE("3D Filament Profiles responses retain product identity without multiplying presets by color", "[U1FilamentSync]")
{
    const nlohmann::json response = {{"results",
                                      {{{"id", "abc123"},
                                        {"brand_name", "Polymaker"},
                                        {"material", "PolyTerra PLA"},
                                        {"material_type", "Matte"},
                                        {"color", "Charcoal Black"},
                                        {"rgb", "#202020"},
                                        {"website", "https://3dfilamentprofiles.com/filaments/example"}}}}};

    const std::vector<FilamentProfileRecord> records = parse_filament_profiles_records(response);
    REQUIRE(records.size() == 1);
    CHECK(records[0].brand == "Polymaker");
    CHECK(records[0].color == "Charcoal Black");
    CHECK(filament_profile_product_name(records[0]) == "Polymaker PolyTerra PLA Matte");
    CHECK(filament_profile_base_material(records[0]) == "PLA");

    FilamentProfileRecord panchroma;
    panchroma.brand    = "Polymaker";
    panchroma.material = "Panchroma PLA";
    CHECK(filament_profile_base_material(panchroma) == "PLA");

    for (const auto& [name, expected] : std::vector<std::pair<std::string, std::string>>{
             {"PLA-CF", "PLA"}, {"PETG-CF", "PETG"}, {"PLA+ Pro", "PLA"}, {"Polycarbonate", "PC"},
             {"Nylon PA12", "PA"}, {"PA6-CF", "PA"}, {"PA12", "PA"}, {"PVA", "PVA"}, {"HIPS", "HIPS"}}) {
        FilamentProfileRecord product;
        product.material = name;
        CHECK(filament_profile_base_material(product) == expected);
    }

    FilamentProfileRecord ambiguous;
    ambiguous.material = "ABS PLA";
    CHECK(filament_profile_base_material(ambiguous).empty());
    ambiguous.material = "PC PLA";
    CHECK(filament_profile_base_material(ambiguous).empty());

    FilamentProfileRecord declared_abs;
    declared_abs.material      = "ABS";
    declared_abs.material_type = "PLA-like finish";
    CHECK(filament_profile_base_material(declared_abs) == "ABS");
}

TEST_CASE("3D Filament Profiles preset reuse requires matching vendor and material identity", "[U1FilamentSync]")
{
    Preset preset(Preset::TYPE_FILAMENT, "Acme PLA");
    preset.config.set_key_value("filament_vendor", new ConfigOptionStrings({"Acme"}));
    preset.config.set_key_value("filament_type", new ConfigOptionStrings({"PLA"}));

    FilamentProfileRecord record;
    record.brand    = "Acme";
    record.material = "PLA Pro";
    CHECK(u1_catalog_preset_matches_record(preset, record));

    record.brand = "Other";
    CHECK_FALSE(u1_catalog_preset_matches_record(preset, record));
    record.brand    = "Acme";
    record.material = "PETG";
    CHECK_FALSE(u1_catalog_preset_matches_record(preset, record));
}

TEST_CASE("U1 rack fallback accepts only one loaded tray for one physical toolhead", "[U1FilamentSync]")
{
    CHECK(u1_unambiguous_rack_toolhead_index(0, 1) == 0);
    CHECK(u1_unambiguous_rack_toolhead_index(3, 1) == 3);
    CHECK_FALSE(u1_unambiguous_rack_toolhead_index(-1, 1));
    CHECK_FALSE(u1_unambiguous_rack_toolhead_index(4, 1));
    CHECK_FALSE(u1_unambiguous_rack_toolhead_index(0, 0));
    CHECK_FALSE(u1_unambiguous_rack_toolhead_index(0, 2));
}

TEST_CASE("3D Filament Profiles credentials are excluded from the bounded search URL", "[U1FilamentSync]")
{
    const std::string url = filament_profiles_search_url("Polymaker PLA black", 500);
    CHECK(url == "https://3dfilamentprofiles.com/api/v1/filaments?q=Polymaker%20PLA%20black&limit=50");
    CHECK(url.find("key") == std::string::npos);
}

TEST_CASE("3D Filament Profiles rejects invalid searches before creating a request", "[U1FilamentSync]")
{
    bool called = false;
    FilamentProfilesSearchReply observed;
    const Http::Ptr request = search_filament_profiles_async(
        "", "sentinel-key-that-must-not-leave-memory", [&](FilamentProfilesSearchReply reply) {
            called   = true;
            observed = std::move(reply);
        });

    CHECK(request == nullptr);
    CHECK(called);
    CHECK_FALSE(observed.ok);
    CHECK(observed.error == "Enter a filament brand or product to search.");
    CHECK(observed.error.find("sentinel-key") == std::string::npos);
}

TEST_CASE("3D Filament Profiles response parsing distinguishes empty and malformed results", "[U1FilamentSync]")
{
    const FilamentProfilesSearchReply empty = parse_filament_profiles_search_response(R"({"results":[]})");
    CHECK(empty.ok);
    CHECK(empty.records.empty());
    CHECK(empty.error.empty());

    const FilamentProfilesSearchReply malformed = parse_filament_profiles_search_response("{not-json");
    CHECK_FALSE(malformed.ok);
    CHECK(malformed.records.empty());
    CHECK(malformed.error == "3D Filament Profiles returned an unreadable response.");
}

TEST_CASE("3D Filament Profiles response parsing omits unusable catalog records", "[U1FilamentSync]")
{
    const FilamentProfilesSearchReply reply = parse_filament_profiles_search_response(
        R"({"results":[{"id":"missing-name"},{"brand":"Acme","material":"ABS PLA"},{"brand":"Acme","material":"PLA Pro"}]})");

    REQUIRE(reply.ok);
    REQUIRE(reply.records.size() == 1);
    CHECK(reply.records.front().brand == "Acme");
    CHECK(reply.records.front().material == "PLA Pro");
    CHECK(filament_profile_record_is_usable(reply.records.front()));
}
