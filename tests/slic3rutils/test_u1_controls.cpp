#include <catch2/catch_all.hpp>

#include "slic3r/Utils/U1Controls.hpp"

#include <nlohmann/json.hpp>

using namespace Slic3r;
using U1Controls::ResumeRefusal;

namespace {

nlohmann::json stats(const char *state, const char *filename) { return {{"print_stats", {{"state", state}, {"filename", filename}}}}; }

} // namespace

TEST_CASE("A Resume is sent only to a printer that says it is paused on the print the button was shown for", "[U1Controls]")
{
    const std::string shown = "bracket-0123-abcd.gcode";

    const auto paused = U1Controls::check_resume(stats("paused", "bracket-0123-abcd.gcode"), shown);
    CHECK(paused);
    CHECK(paused.refusal == ResumeRefusal::None);
    CHECK(paused.printer_state == "paused");

    // The same name in another folder is another print.
    CHECK(U1Controls::check_resume(stats("paused", "jobs/bracket-0123-abcd.gcode"), shown).refusal == ResumeRefusal::OtherFile);
    CHECK(U1Controls::check_resume(stats("paused", "bracket-0123-abcd.gcode"), "jobs/" + shown).refusal == ResumeRefusal::OtherFile);
    CHECK(U1Controls::check_resume(stats("paused", "jobs/bracket-0123-abcd.gcode"), "jobs/" + shown));

    for (const char *state : {"printing", "standby", "complete", "cancelled", "error", ""}) {
        const auto check = U1Controls::check_resume(stats(state, "bracket-0123-abcd.gcode"), shown);
        CHECK_FALSE(check);
        CHECK(check.refusal == ResumeRefusal::NotPaused);
        CHECK(check.printer_state == state);
    }

    const auto other = U1Controls::check_resume(stats("paused", "another.gcode"), shown);
    CHECK(other.refusal == ResumeRefusal::OtherFile);
    CHECK(other.filename == "another.gcode");
    // Nothing to compare with is not a match.
    CHECK(U1Controls::check_resume(stats("paused", ""), shown).refusal == ResumeRefusal::OtherFile);
    CHECK(U1Controls::check_resume(stats("paused", "bracket-0123-abcd.gcode"), "").refusal == ResumeRefusal::OtherFile);
}

TEST_CASE("What the printer does not report refuses a Resume", "[U1Controls]")
{
    const std::string shown = "bracket.gcode";
    CHECK(U1Controls::check_resume(nlohmann::json(), shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume(nlohmann::json::object(), shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume({{"print_stats", "paused"}}, shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume({{"print_stats", {{"filename", "bracket.gcode"}}}}, shown).refusal == ResumeRefusal::Unreachable);
    CHECK(U1Controls::check_resume({{"print_stats", {{"state", 3}, {"filename", "bracket.gcode"}}}}, shown).refusal == ResumeRefusal::Unreachable);
    // A state without a file name cannot be tied to the shown print.
    CHECK(U1Controls::check_resume({{"print_stats", {{"state", "paused"}}}}, shown).refusal == ResumeRefusal::OtherFile);
}

TEST_CASE("A control is offered only where it cannot harm a print, and asks first when it moves, heats or extrudes", "[U1Controls]")
{
    using namespace U1Controls;
    const std::initializer_list<Action> all = {Action::HomeAll, Action::MotorsOff, Action::SetNozzleTemperature, Action::SetBedTemperature,
                                               Action::HeatersOff, Action::LoadFilament, Action::UnloadFilament};
    PrinterFacts idle;
    idle.connected = idle.ready = idle.heated_bed = idle.load_macro = idle.unload_macro = true;

    // Nothing is offered to a printer that is not connected or not ready, whatever else is known.
    for (Action action : all) {
        PrinterFacts off = idle;
        off.connected    = false;
        CHECK(judge(action, off).why == Unavailable::NotConnected);
        PrinterFacts fault = idle;
        fault.ready        = false;
        CHECK(judge(action, fault).why == Unavailable::NotReady);
        CHECK(judge(action, PrinterFacts{}).why == Unavailable::NotConnected);
    }

    // An idle printer: everything that moves, heats or extrudes asks once; cooling and switching off do not.
    for (Action action : {Action::HomeAll, Action::MotorsOff, Action::LoadFilament, Action::UnloadFilament}) {
        CHECK(judge(action, idle));
        CHECK(judge(action, idle).risk == Risk::Confirm);
    }
    CHECK(judge(Action::SetNozzleTemperature, idle, 210).risk == Risk::Confirm);
    CHECK(judge(Action::SetBedTemperature, idle, 60).risk == Risk::Confirm);
    CHECK(judge(Action::SetNozzleTemperature, idle, 0).risk == Risk::Light);
    CHECK(judge(Action::SetBedTemperature, idle, 0).risk == Risk::Light);
    CHECK(judge(Action::HeatersOff, idle));
    CHECK(judge(Action::HeatersOff, idle).risk == Risk::Light);

    PrinterFacts printing = idle, paused = idle;
    printing.printing = true;
    paused.paused     = true;
    // A paused print is still a print: no homing, no motors off, no heaters off.
    for (const PrinterFacts &facts : {printing, paused})
        for (Action action : {Action::HomeAll, Action::MotorsOff, Action::HeatersOff})
            CHECK(judge(action, facts).why == Unavailable::PrintActive);
    // A temperature may be changed during a print, even to 0, but never without a question that names the print.
    for (const PrinterFacts &facts : {printing, paused})
        for (int target : {0, 215}) {
            CHECK(judge(Action::SetNozzleTemperature, facts, target));
            CHECK(judge(Action::SetNozzleTemperature, facts, target).risk == Risk::ConfirmDuringPrint);
            CHECK(judge(Action::SetBedTemperature, facts, target).risk == Risk::ConfirmDuringPrint);
        }
    // Filament: not while the print runs; while it is paused, with the question that names the print.
    for (Action action : {Action::LoadFilament, Action::UnloadFilament}) {
        CHECK(judge(action, printing).why == Unavailable::PrintRunning);
        CHECK(judge(action, paused));
        CHECK(judge(action, paused).risk == Risk::ConfirmDuringPrint);
    }

    // What the printer does not have is unavailable for that reason, in every state.
    PrinterFacts bare = idle;
    bare.heated_bed = bare.load_macro = bare.unload_macro = false;
    CHECK(judge(Action::SetBedTemperature, bare, 60).why == Unavailable::NoHeatedBed);
    CHECK(judge(Action::LoadFilament, bare).why == Unavailable::NoMacro);
    CHECK(judge(Action::UnloadFilament, bare).why == Unavailable::NoMacro);
    PrinterFacts only_load = bare;
    only_load.load_macro   = true;
    CHECK(judge(Action::LoadFilament, only_load));
    CHECK(judge(Action::UnloadFilament, only_load).why == Unavailable::NoMacro);
    bare.paused = true;
    CHECK(judge(Action::LoadFilament, bare).why == Unavailable::NoMacro);

    CHECK(group_of(Action::HomeAll) == Group::Motion);
    CHECK(group_of(Action::MotorsOff) == Group::Motion);
    CHECK(group_of(Action::SetNozzleTemperature) == Group::Temperature);
    CHECK(group_of(Action::SetBedTemperature) == Group::Temperature);
    CHECK(group_of(Action::HeatersOff) == Group::Temperature);
    CHECK(group_of(Action::LoadFilament) == Group::Filament);
    CHECK(group_of(Action::UnloadFilament) == Group::Filament);
}
