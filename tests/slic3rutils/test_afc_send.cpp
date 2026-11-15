#include <catch2/catch_all.hpp>

#include "libslic3r/AfcLanes.hpp"
#include "libslic3r/U1PrintTask.hpp"
#include "slic3r/Utils/U1JobJournal.hpp"

#include <nlohmann/json.hpp>

#include <string>
#include <vector>

using namespace Slic3r;

namespace {

// What a Centauri Carbon on COSMOS with a four-lane CANVAS reported when idle (AFC 1.2.7), reduced to the
// fields the send reads.
nlohmann::json lane(int number, const char *tool, const char *material, const char *color, bool ready = true)
{
    return {{"name", "CANVAS_" + std::to_string(number)}, {"lane", number}, {"map", nlohmann::json::array({tool})}, {"current_map", tool},
            {"prep", ready}, {"load", ready}, {"tool_loaded", false}, {"material", material}, {"color", color}, {"status", ready ? "Loaded" : "None"}};
}

nlohmann::json idle_carbon()
{
    return {{"print_stats", {{"state", "standby"}, {"filename", ""}}},
            {"extruder", {{"nozzle_diameter", 0.4}, {"temperature", 30.0}}},
            {"AFC", {{"current_state", "Idle"}, {"error_state", false}, {"lanes", {"CANVAS_1", "CANVAS_2", "CANVAS_3", "CANVAS_4"}}, {"maps", {"T0", "T1", "T2", "T3"}}}},
            {"AFC_lane CANVAS_1", lane(1, "T0", "PLA", "#FFCBAC")},
            {"AFC_lane CANVAS_2", lane(2, "T1", "pla", "#544144")},
            {"AFC_lane CANVAS_3", lane(3, "T2", "PETG", "ffffff")},
            {"AFC_lane CANVAS_4", lane(4, "T3", "PLA", "#080A0D")},
            // COSMOS answers two commands of the stock firmware's start G-code with an emergency stop; a macro that
            // stops only on a fault of its own is an ordinary macro.
            {"configfile",
             {{"settings",
               {{"gcode_macro m729", {{"gcode", "\n{ action_emergency_stop(\"Use COSMOS OrcaSlicer profile (or COSMOS start/end machine gcode).\") }"}}},
                {"gcode_macro m8213", {{"gcode", "\n{ action_emergency_stop(\"Use COSMOS OrcaSlicer profile (or COSMOS start/end machine gcode).\") }"}}},
                {"gcode_macro print_start", {{"gcode", "\n{% if params.TOOL is not defined %}\n{ action_emergency_stop(\"no tool\") }\n{% endif %}\nG28"}}},
                {"extruder", {{"nozzle_diameter", 0.4}}}}}}}};
}

} // namespace

TEST_CASE("AFC lanes are read as the send's sources by tool number", "[AfcLanes]")
{
    const auto state = AfcLanes::parse_printer_state(idle_carbon());
    REQUIRE(state);
    REQUIRE(state->toolheads.size() == size_t(U1PrintTask::PHYSICAL_EXTRUDER_COUNT));
    CHECK(state->toolheads[0].present);
    CHECK(state->toolheads[0].type == "PLA");
    CHECK(state->toolheads[0].color == "#FFCBAC");
    CHECK(state->toolheads[1].type == "PLA");
    CHECK(state->toolheads[2].type == "PETG");
    CHECK(state->toolheads[2].color == "#FFFFFF");
    CHECK(AfcLanes::lane_names(idle_carbon()) == std::vector<std::string>{"CANVAS_1", "CANVAS_2", "CANVAS_3", "CANVAS_4"});

    SECTION("a printer without AFC is not such a printer")
    {
        CHECK_FALSE(AfcLanes::parse_printer_state(nlohmann::json()));
        CHECK_FALSE(AfcLanes::parse_printer_state({{"print_stats", {{"state", "standby"}}}}));
        CHECK(AfcLanes::lane_names({{"print_stats", {{"state", "standby"}}}}).empty());
    }
    SECTION("the tool number comes from the lane's map, not from its position")
    {
        auto status = idle_carbon();
        status["AFC_lane CANVAS_1"]["map"] = nlohmann::json::array({"T3"});
        status["AFC_lane CANVAS_4"]["map"] = "T0"; // earlier AFC versions report one name
        const auto swapped = AfcLanes::parse_printer_state(status);
        REQUIRE(swapped);
        CHECK(swapped->toolheads[0].color == "#080A0D");
        CHECK(swapped->toolheads[3].color == "#FFCBAC");
        CHECK(AfcLanes::lane_for_tool(status, 3) == &status["AFC_lane CANVAS_1"]);
    }
    SECTION("a lane without filament at both sensors, or a tool no lane answers to, is not present")
    {
        auto status = idle_carbon();
        status["AFC_lane CANVAS_2"]["load"] = false;
        status["AFC_lane CANVAS_3"]["map"]  = nlohmann::json::array({"T7"});
        status["AFC_lane CANVAS_4"]["map"]  = nlohmann::json::array({"spare"});
        const auto partial = AfcLanes::parse_printer_state(status);
        REQUIRE(partial);
        CHECK(partial->toolheads[0].present);
        CHECK_FALSE(partial->toolheads[1].present);
        CHECK(partial->toolheads[1].type == "PLA"); // still shown as what it is
        CHECK_FALSE(partial->toolheads[2].present);
        CHECK(partial->toolheads[2].type.empty());
        CHECK_FALSE(partial->toolheads[3].present);
        CHECK(AfcLanes::lane_for_tool(status, 2) == nullptr);
    }
    SECTION("a lane object that is missing is not invented")
    {
        auto status = idle_carbon();
        status.erase("AFC_lane CANVAS_1");
        const auto partial = AfcLanes::parse_printer_state(status);
        REQUIRE(partial);
        CHECK_FALSE(partial->toolheads[0].present);
    }
}

TEST_CASE("A Start on a printer fed from AFC lanes is refused unless printer and changer are ready for this file", "[U1Jobs][AfcLanes]")
{
    using Kind = U1Jobs::Blocker::Kind;
    const std::string         model = "Elegoo Centauri Carbon";
    const U1Jobs::GcodeFacts  carbon{model, {0.4}};
    const std::vector<int>    map{2, 0, -1, 3};

    CHECK(U1Jobs::afc_start_blocker(carbon, idle_carbon(), map, true, model).kind == Kind::None);

    SECTION("a file sliced for another model is refused even for upload only; no model on either side is not compared")
    {
        const auto blocker = U1Jobs::afc_start_blocker({"Snapmaker U1", {0.4}}, idle_carbon(), map, false, model);
        CHECK(blocker.kind == Kind::WrongModel);
        CHECK(blocker.detail == "Snapmaker U1");
        CHECK(U1Jobs::afc_start_blocker({"elegoo centauri carbon", {0.4}}, idle_carbon(), map, false, model).kind == Kind::None);
        CHECK(U1Jobs::afc_start_blocker({"", {}}, idle_carbon(), map, false, model).kind == Kind::None);
        CHECK(U1Jobs::afc_start_blocker({"Snapmaker U1", {0.4}}, idle_carbon(), map, false, "").kind == Kind::None);
    }
    SECTION("a file that runs a command the printer answers with an emergency stop is not sent, started or not")
    {
        U1Jobs::GcodeFacts stock = carbon;
        stock.commands           = {"G1", "G28", "M729", "T0"};
        for (const bool start : {true, false}) {
            const auto blocker = U1Jobs::afc_start_blocker(stock, idle_carbon(), map, start, model);
            CHECK(blocker.kind == Kind::RefusedCommand);
            CHECK(blocker.detail == "M729\nUse COSMOS OrcaSlicer profile (or COSMOS start/end machine gcode).");
        }
        // The printer's own start macro stops only when it is called wrongly: calling it is no refusal.
        U1Jobs::GcodeFacts cosmos = carbon;
        cosmos.commands           = {"G1", "PRINT_START", "T0", "T3"};
        CHECK(U1Jobs::afc_start_blocker(cosmos, idle_carbon(), map, true, model).kind == Kind::None);
        // A stop every run of the macro reaches is a refusal however the macro is written; one inside a condition is not.
        auto longer = idle_carbon();
        longer["configfile"]["settings"]["gcode_macro old_clean"] = {{"gcode", "\nRESPOND MSG=\"no\"\n{% if printer.idle_timeout.state == \"Printing\" %}\nG4 P1\n{% endif %}\n{ action_emergency_stop('not on this firmware') }\n"}};
        longer["configfile"]["settings"]["gcode_macro old_stop"]  = {{"gcode", "\nG4 P1\n  M112\n"}};
        longer["configfile"]["settings"]["gcode_macro guarded"]   = {{"gcode", "\n{%- if params.X is not defined -%}\n{ action_emergency_stop(\"no X\") }\nM112\n{%- endif -%}\nG28"}};
        U1Jobs::GcodeFacts old = carbon;
        old.commands           = {"G1", "OLD_CLEAN"};
        const auto refused = U1Jobs::afc_start_blocker(old, longer, map, true, model);
        CHECK(refused.kind == Kind::RefusedCommand);
        CHECK(refused.detail == "OLD_CLEAN\nnot on this firmware");
        old.commands = {"G1", "OLD_STOP"};
        CHECK(U1Jobs::afc_start_blocker(old, longer, map, true, model).detail == "OLD_STOP\n");
        old.commands = {"G1", "GUARDED"};
        CHECK(U1Jobs::afc_start_blocker(old, longer, map, true, model).kind == Kind::None);
        // A file whose commands could not be listed in full was not checked, and is not sent.
        U1Jobs::GcodeFacts endless = cosmos;
        endless.commands_complete  = false;
        CHECK(U1Jobs::afc_start_blocker(endless, idle_carbon(), map, false, model).kind == Kind::FileNotChecked);
        // A Start without the printer's configuration was not checked for this, and is refused.
        auto unread = idle_carbon();
        unread.erase("configfile");
        CHECK(U1Jobs::afc_start_blocker(cosmos, unread, map, true, model).kind == Kind::PrinterUnreachable);
        CHECK(U1Jobs::afc_start_blocker(cosmos, unread, map, false, model).kind == Kind::None);
    }
    SECTION("a changer in bypass, or one whose lanes were not read, starts nothing")
    {
        auto status = idle_carbon();
        status["AFC"]["bypass_state"] = true;
        const auto bypass = U1Jobs::afc_start_blocker(carbon, status, map, true, model);
        CHECK(bypass.kind == Kind::ChangerNotReady);
        CHECK(bypass.detail == "bypass");
        auto unread = idle_carbon();
        unread.erase("AFC_lane CANVAS_2"); // a lane no filament of this file prints from
        CHECK(U1Jobs::afc_start_blocker(carbon, unread, map, true, model).kind == Kind::PrinterUnreachable);
        CHECK(U1Jobs::afc_start_blocker(carbon, unread, map, false, model).kind == Kind::None);
    }
    SECTION("upload only needs no printer state")
    {
        CHECK(U1Jobs::afc_start_blocker(carbon, nlohmann::json(), map, false, model).kind == Kind::None);
    }
    SECTION("no status, no print state, or no word from the changer is a refusal")
    {
        CHECK(U1Jobs::afc_start_blocker(carbon, nlohmann::json(), map, true, model).kind == Kind::PrinterUnreachable);
        auto status = idle_carbon();
        status.erase("AFC");
        CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::PrinterUnreachable);
        status = idle_carbon();
        status["AFC"].erase("error_state");
        CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::PrinterUnreachable);
        status = idle_carbon();
        status["print_stats"].erase("state");
        CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::PrinterUnreachable);
    }
    SECTION("only an idle printer with an idle, error-free changer is started")
    {
        for (const char *state : {"printing", "paused", "error", "unknown"}) {
            auto status = idle_carbon();
            status["print_stats"]["state"] = state;
            const auto blocker = U1Jobs::afc_start_blocker(carbon, status, map, true, model);
            CHECK(blocker.kind == Kind::PrinterNotIdle);
            CHECK(blocker.detail == state);
        }
        for (const char *state : {"complete", "cancelled"}) {
            auto status = idle_carbon();
            status["print_stats"]["state"] = state;
            CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::None);
        }
        auto status = idle_carbon();
        status["AFC"]["error_state"] = true;
        CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::ChangerNotReady);
        status = idle_carbon();
        status["AFC"]["current_state"] = "Loading";
        const auto busy = U1Jobs::afc_start_blocker(carbon, status, map, true, model);
        CHECK(busy.kind == Kind::ChangerNotReady);
        CHECK(busy.detail == "Loading");
    }
    SECTION("every filament needs a lane that answers to its tool and has filament ready")
    {
        auto status = idle_carbon();
        status["AFC_lane CANVAS_3"]["map"] = nlohmann::json::array({"T9"});
        auto blocker = U1Jobs::afc_start_blocker(carbon, status, map, true, model);
        CHECK(blocker.kind == Kind::LaneUnknown);
        CHECK(blocker.filament == 0);
        CHECK(blocker.toolhead == 2);

        status = idle_carbon();
        status["AFC_lane CANVAS_4"]["prep"] = false;
        blocker = U1Jobs::afc_start_blocker(carbon, status, map, true, model);
        CHECK(blocker.kind == Kind::LaneEmpty);
        CHECK(blocker.filament == 3);
        CHECK(blocker.toolhead == 3);
        // A lane the file does not print from may be empty.
        status = idle_carbon();
        status["AFC_lane CANVAS_2"]["load"] = false;
        CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::None);
    }
    SECTION("the one nozzle must be the one the file was sliced for")
    {
        auto status = idle_carbon();
        status["extruder"].erase("nozzle_diameter");
        CHECK(U1Jobs::afc_start_blocker(carbon, status, map, true, model).kind == Kind::NozzleUnknown);
        status = idle_carbon();
        status["extruder"]["nozzle_diameter"] = 0.6;
        const auto blocker = U1Jobs::afc_start_blocker(carbon, status, map, true, model);
        CHECK(blocker.kind == Kind::NozzleMismatch);
        CHECK(blocker.sliced == 0.4);
        CHECK(blocker.fitted == 0.6);
        // A file that names no nozzle is not compared.
        CHECK(U1Jobs::afc_start_blocker({model, {}}, status, map, true, model).kind == Kind::None);
    }
}

TEST_CASE("A lane's brand is the one the printer keeps for the spool still in it", "[AfcLanes]")
{
    // As the Owner's Carbon keeps them in Klipper's saved variables: one entry per lane, written when a tag is
    // scanned or the brand is told; an entry describes the spool that was in the lane then.
    const auto tag = [](const char *maker, const char *type, const char *color, const char *source) {
        return nlohmann::json{{"manufacturer", maker}, {"type", type}, {"color", color}, {"uid", ""}, {"source", source}, {"tag", "046FFEA68D2681"}};
    };
    auto status = idle_carbon();
    status["save_variables"] = {{"variables",
                                 {{"canvas_tag_canvas_1", tag("ABCWavePrint", "PLA", "FFCBAC", "told")},
                                  {"canvas_tag_canvas_2", tag("Elegoo", "PLA", "544144", "elegoo")},
                                  {"canvas_tag_canvas_3", tag("Gryddle", "PLA", "FFFFFF", "openspool")},
                                  {"canvas_tag_canvas_4", nlohmann::json::object()}}}};

    const std::vector<std::string> brands = AfcLanes::tool_brands(status);
    REQUIRE(brands.size() == size_t(U1PrintTask::PHYSICAL_EXTRUDER_COUNT));
    CHECK(brands[0] == "ABCWavePrint"); // told, material and colour as the lane reports them
    CHECK(brands[1].empty());           // a tag in Elegoo's format names no maker
    CHECK(brands[2].empty());           // the entry is for PLA; the lane now reports PETG
    CHECK(brands[3].empty());           // loaded without a scan

    const auto state = AfcLanes::parse_printer_state(status);
    REQUIRE(state);
    CHECK(state->toolheads[0].vendor == "ABCWavePrint");
    CHECK(state->toolheads[1].vendor.empty());

    // Another colour in the lane: the entry is of an earlier spool.
    status["AFC_lane CANVAS_1"]["color"] = "#000000";
    CHECK(AfcLanes::tool_brands(status)[0].empty());
    status["AFC_lane CANVAS_1"]["color"] = "#ffcbac";
    CHECK(AfcLanes::tool_brands(status)[0] == "ABCWavePrint");
    // A lane with no filament ready has no spool to name.
    status["AFC_lane CANVAS_1"]["prep"] = false;
    CHECK(AfcLanes::tool_brands(status)[0].empty());
    status["AFC_lane CANVAS_1"]["prep"] = true;
    // AFC's own brand (from Spoolman) comes first.
    status["AFC_lane CANVAS_1"]["spool_vendor"] = "Polymaker";
    CHECK(AfcLanes::tool_brands(status)[0] == "Polymaker");
    // No saved variables at all: no brand, and nothing else changes.
    CHECK(AfcLanes::tool_brands(idle_carbon()) == std::vector<std::string>(size_t(U1PrintTask::PHYSICAL_EXTRUDER_COUNT)));
}
