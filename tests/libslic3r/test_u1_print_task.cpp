#include <catch2/catch_test_macros.hpp>

#include "libslic3r/U1PrintTask.hpp"

#include <nlohmann/json.hpp>
#include <fstream>

#include <string>
#include <vector>

using json = nlohmann::json;
using namespace Slic3r;

TEST_CASE("U1 map table matches the firmware's MAP_TABLE literal", "[U1PrintTask]")
{
    CHECK(U1PrintTask::format_map_table({}) == "[]");
    CHECK(U1PrintTask::format_map_table({2, 0}) == "[[0,2],[1,0]]");
    // Unused filaments are left at the firmware default rather than sent.
    CHECK(U1PrintTask::format_map_table({U1PrintTask::UNMAPPED, 3, U1PrintTask::UNMAPPED, 1}) == "[[1,3],[3,1]]");
    CHECK(U1PrintTask::format_index_list({0, 3}) == "[0,3]");
}

TEST_CASE("U1 print options are rejected where the firmware would reject them", "[U1PrintTask]")
{
    std::string error;

    U1PrintTask::Options ok;
    ok.filament_to_toolhead = {3, 2, 1, 0, U1PrintTask::UNMAPPED};
    CHECK(U1PrintTask::validate(ok, error));

    U1PrintTask::Options bad_toolhead;
    bad_toolhead.filament_to_toolhead = {0, 4};
    CHECK_FALSE(U1PrintTask::validate(bad_toolhead, error));
    CHECK(error.find("Filament 2") != std::string::npos);

    U1PrintTask::Options too_many;
    too_many.filament_to_toolhead.assign(U1PrintTask::LOGICAL_EXTRUDER_COUNT + 1, 0);
    CHECK_FALSE(U1PrintTask::validate(too_many, error));

    U1PrintTask::Options bad_calibration;
    bad_calibration.flow_calibrate           = true;
    bad_calibration.flow_calibrate_toolheads = {1, 1};
    CHECK_FALSE(U1PrintTask::validate(bad_calibration, error));

    // Calibration toolheads are ignored when calibration is off.
    bad_calibration.flow_calibrate = false;
    CHECK(U1PrintTask::validate(bad_calibration, error));
}

TEST_CASE("U1 start_local_print body only carries options that were set", "[U1PrintTask]")
{
    const json plain = U1PrintTask::build_start_local_print_body("Cube.gcode", 0, {});
    CHECK(plain == json{{"path", "Cube.gcode"}, {"print_plate", 1}});

    U1PrintTask::Options options;
    options.filament_to_toolhead     = {2, 0};
    options.bed_level                = true;
    options.time_lapse_camera        = false;
    options.flow_calibrate           = true;
    options.flow_calibrate_toolheads = {0, 2};

    const json body = U1PrintTask::build_start_local_print_body("jobs/Bear.gcode", 1, options);
    CHECK(body["path"] == "jobs/Bear.gcode");
    CHECK(body["print_plate"] == 1);
    CHECK(body["options"] == json{{"map_table", "[[0,2],[1,0]]"},
                                  {"bed_level", 1},
                                  {"time_lapse_camera", 0},
                                  {"flow_calibrate", 1},
                                  {"flow_calibrate_extruders", "[0,2]"}});
    // Booleans must be integers: the firmware reads them with get_int.
    CHECK(body["options"]["bed_level"].is_number_integer());
    CHECK_FALSE(body["options"].contains("shaper_calibrate"));
}

TEST_CASE("U1 printer state is read from print_task_config", "[U1PrintTask]")
{
    const json cfg = {{"filament_exist", {true, true, false, true}},
                      {"filament_vendor", {"Polymaker", "NONE", "", "Snapmaker"}},
                      {"filament_type", {"PLA", "petg", "NONE", "PLA"}},
                      {"filament_sub_type", {"Matte", "NONE", "", "Basic"}},
                      {"filament_color_rgba", {"FF0000FF", "00ff00ff", "FFFFFFFF", "bogus"}},
                      {"auto_bed_leveling", true},
                      {"time_lapse_camera", false}};
    const U1PrintTask::PrinterState state = U1PrintTask::parse_printer_state(cfg);
    REQUIRE(state.toolheads.size() == 4);
    CHECK(state.toolheads[0].present);
    CHECK(state.toolheads[0].vendor == "Polymaker");
    CHECK(state.toolheads[0].sub_type == "Matte");
    CHECK(state.toolheads[0].color == "#FF0000");
    CHECK(state.toolheads[1].type == "PETG");
    CHECK(state.toolheads[1].color == "#00FF00");
    CHECK(state.toolheads[1].vendor.empty());
    CHECK(state.toolheads[1].sub_type.empty());
    CHECK_FALSE(state.toolheads[2].present);
    CHECK(state.toolheads[2].type.empty());
    CHECK(state.toolheads[3].color.empty());
    CHECK(state.bed_level);
    CHECK_FALSE(state.flow_calibrate);

    // Garbage never throws; the dialog still opens with empty toolheads.
    const U1PrintTask::PrinterState empty = U1PrintTask::parse_printer_state(json{{"filament_exist", "yes"}});
    CHECK(empty.toolheads.size() == 4);
    CHECK_FALSE(empty.toolheads[0].present);
    CHECK(U1PrintTask::parse_printer_state(json()).toolheads.size() == 4);
}

TEST_CASE("U1 suggested mapping prefers matching type, then closest color", "[U1PrintTask]")
{
    using F = U1PrintTask::Filament;
    const std::vector<F> toolheads{{true, "", "", "PLA", "", "#FF0000"},
                                   {true, "", "", "PETG", "", "#FFFFFF"},
                                   {true, "", "", "PLA", "", "#FFFFFF"},
                                   {false, "", "", "", "", ""}};

    const std::vector<F> plate{{true, "", "", "PLA", "", "#FAFAFA"},  // white PLA -> T2, not the white PETG in T1
                               {false, "", "", "PLA", "", "#000000"}, // unused
                               {true, "", "", "PETG", "", "#101010"}, // only PETG -> T1 despite color
                               {true, "", "", "TPU", "", "#EE0000"}}; // no TPU loaded -> closest color, T0
    CHECK(U1PrintTask::suggest_mapping(plate, toolheads) == std::vector<int>{2, U1PrintTask::UNMAPPED, 1, 0});

    // Filaments and toolheads that all look alike: each filament keeps its own number.
    const std::vector<F> alike(4, F{true, "", "", "PLA", "", "#26A69A"});
    CHECK(U1PrintTask::suggest_mapping(alike, alike) == std::vector<int>{0, 1, 2, 3});
    // A tie between two toolheads goes to the filament's own number; a closer color still wins outright.
    const std::vector<F> pairs = {{true, "", "", "PLA", "", "#FFFFFF"}, {true, "", "", "PLA", "", "#FFFFFF"},
                                  {true, "", "", "PLA", "", "#000000"}, {false, "", "", "", "", ""}};
    CHECK(U1PrintTask::suggest_mapping({{true, "", "", "PLA", "", "#FFFFFF"}, {true, "", "", "PLA", "", "#FFFFFF"},
                                        {true, "", "", "PLA", "", "#FFFFFF"}}, pairs) == std::vector<int>{0, 1, 0});

    // The own-number preference never picks an empty toolhead or one with another material over a match.
    std::vector<F> second_empty = alike;
    second_empty[1].present = false;
    CHECK(U1PrintTask::suggest_mapping(alike, second_empty) == std::vector<int>{0, 0, 2, 3});
    std::vector<F> second_petg = alike;
    second_petg[1].type = "PETG";
    CHECK(U1PrintTask::suggest_mapping(alike, second_petg) == std::vector<int>{0, 0, 2, 3});
    // More filaments than toolheads: the own number wraps around.
    CHECK(U1PrintTask::suggest_mapping(std::vector<F>(5, F{true, "", "", "PLA", "", "#26A69A"}), alike) == std::vector<int>{0, 1, 2, 3, 0});

    // Two filaments that look alike but match no toolhead exactly (the halves of a color mix) get a toolhead
    // each, the closer one going to the filament with that number, instead of both taking the nearest color.
    const std::vector<F> near_teal = {{true, "", "", "PLA", "", "#1F9E90"}, {true, "", "", "PLA", "", "#2AAE9F"},
                                      {true, "", "", "PLA", "", "#000000"}, {true, "", "", "PLA", "", "#FFFFFF"}};
    CHECK(U1PrintTask::suggest_mapping(std::vector<F>(2, F{true, "", "", "PLA", "", "#26A69A"}), near_teal) == std::vector<int>{0, 1});
    CHECK(U1PrintTask::suggest_mapping(alike, near_teal) == std::vector<int>{0, 1, 2, 3});
    // A toolhead whose color is exactly the filament's is shared before a wrong color is used.
    const std::vector<F> one_teal = {{true, "", "", "PLA", "", "#26A69A"}, {true, "", "", "PLA", "", "#000000"}, F{}, F{}};
    CHECK(U1PrintTask::suggest_mapping(std::vector<F>(2, F{true, "", "", "PLA", "", "#26A69A"}), one_teal) == std::vector<int>{0, 0});
    // More like filaments than toolheads of their material: the extra one shares the closest.
    std::vector<F> two_pla = near_teal;
    two_pla[2].type = two_pla[3].type = "PETG";
    CHECK(U1PrintTask::suggest_mapping(std::vector<F>(3, F{true, "", "", "PLA", "", "#26A69A"}), two_pla) == std::vector<int>{0, 1, 1});

    // Nothing loaded: fall back to the same index.
    const std::vector<F> none(4);
    CHECK(U1PrintTask::suggest_mapping({{true, "", "", "PLA", "", "#FFFFFF"}, {true, "", "", "PLA", "", "#000000"}}, none) ==
          std::vector<int>{0, 1});
}

TEST_CASE("U1 filament report for Sync Filament is built from Moonraker status", "[U1PrintTask]")
{
    std::ifstream in(std::string(TEST_DATA_DIR) + "/moonraker/u1_snapshot.json");
    REQUIRE(in.good());
    const json status = json::parse(in)["status"];

    const json report = U1PrintTask::build_filament_report(status);
    REQUIRE(report.is_object());
    CHECK(report["filament_type"] == json({"PLA", "PLA", "TPU", "TPU"}));
    CHECK(report["filament_vendor"].size() == 4);
    CHECK(report["filament_color_rgba"][2] == "DD0000FF");
    CHECK(report["filament_official"].size() == 4);
    CHECK(report["extruder_map_table"].is_array());
    CHECK(report["nozzle_diameters"] == json({"0.4", "0.4", "0.4", "0.4"}));
    CHECK(report["nozzle_volume_types"] == json({"standard", "standard", "standard", "standard"}));

    CHECK(U1PrintTask::build_filament_report(json::object()).is_null());
}

TEST_CASE("U1 manual filament edits use SET_PRINT_FILAMENT_CONFIG", "[U1PrintTask]")
{
    CHECK(U1PrintTask::set_filament_command(2, "amazon basics", "TPU", "95A", "#dd0000") ==
          "SET_PRINT_FILAMENT_CONFIG CONFIG_EXTRUDER=2 VENDOR=\"amazon basics\" FILAMENT_TYPE=\"TPU\" "
          "FILAMENT_SUBTYPE=\"95A\" FILAMENT_COLOR_RGBA=DD0000FF");
    // Quotes/newlines cannot break out of the parameter; blanks become NONE.
    CHECK(U1PrintTask::set_filament_command(0, "Bad\"Vendor\nX", " PLA ", "", "#FFFFFF") ==
          "SET_PRINT_FILAMENT_CONFIG CONFIG_EXTRUDER=0 VENDOR=\"BadVendorX\" FILAMENT_TYPE=\"PLA\" "
          "FILAMENT_SUBTYPE=\"NONE\" FILAMENT_COLOR_RGBA=FFFFFFFF");
    // Overriding tag data needs FORCE=1 (stock rejects it otherwise; PAXX's UI does the same).
    CHECK(U1PrintTask::set_filament_command(1, "Generic", "PLA", "Matte", "#000000", true) ==
          "SET_PRINT_FILAMENT_CONFIG CONFIG_EXTRUDER=1 VENDOR=\"Generic\" FILAMENT_TYPE=\"PLA\" "
          "FILAMENT_SUBTYPE=\"Matte\" FILAMENT_COLOR_RGBA=000000FF FORCE=1");
    CHECK(U1PrintTask::reread_tag_command(3) == "FILAMENT_DT_CLEAR CHANNEL=3\nFILAMENT_DT_UPDATE CHANNEL=3");
    CHECK(U1PrintTask::reread_tag_command(4).empty());
    CHECK(U1PrintTask::set_filament_command(4, "Generic", "PLA", "Basic", "#FFFFFF").empty());
    CHECK(U1PrintTask::set_filament_command(0, "Generic", "PLA", "Basic", "red").empty());
}
