#include "U1Controls.hpp"

namespace Slic3r {
namespace U1Controls {

namespace {

std::string leaf(const std::string &path)
{
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

} // namespace

bool same_file(const std::string &a, const std::string &b) { return !a.empty() && !b.empty() && leaf(a) == leaf(b); }

ResumeCheck check_resume(const nlohmann::json &status, const std::string &shown_file)
{
    ResumeCheck check;
    if (!status.is_object() || !status.contains("print_stats") || !status["print_stats"].is_object())
        return check;
    const nlohmann::json &stats = status["print_stats"];
    if (!stats.contains("state") || !stats["state"].is_string())
        return check;
    check.printer_state = stats["state"].get<std::string>();
    if (stats.contains("filename") && stats["filename"].is_string())
        check.filename = stats["filename"].get<std::string>();
    if (check.printer_state != "paused")
        check.refusal = ResumeRefusal::NotPaused;
    else if (!same_file(check.filename, shown_file))
        check.refusal = ResumeRefusal::OtherFile;
    else
        check.refusal = ResumeRefusal::None;
    return check;
}

Group group_of(Action action)
{
    switch (action) {
    case Action::HomeAll:
    case Action::MotorsOff: return Group::Motion;
    case Action::SetNozzleTemperature:
    case Action::SetBedTemperature:
    case Action::HeatersOff: return Group::Temperature;
    case Action::LoadFilament:
    case Action::UnloadFilament: break;
    }
    return Group::Filament;
}

Judgement judge(Action action, const PrinterFacts &facts, int target)
{
    const auto no = [](Unavailable why) { return Judgement{why, Risk::Confirm}; };
    if (!facts.connected)
        return no(Unavailable::NotConnected);
    if (!facts.ready)
        return no(Unavailable::NotReady);
    const bool in_print = facts.printing || facts.paused;
    switch (action) {
    case Action::HomeAll:
    case Action::MotorsOff:
        // Either would ruin a print, and a paused print is still a print.
        return in_print ? no(Unavailable::PrintActive) : Judgement{Unavailable::None, Risk::Confirm};
    case Action::SetBedTemperature:
        if (!facts.heated_bed)
            return no(Unavailable::NoHeatedBed);
        [[fallthrough]];
    case Action::SetNozzleTemperature:
        if (in_print)
            return {Unavailable::None, Risk::ConfirmDuringPrint};
        return {Unavailable::None, target > 0 ? Risk::Confirm : Risk::Light};
    case Action::HeatersOff:
        // During a print this would end it without saying so; Stop print is the control for that.
        return in_print ? no(Unavailable::PrintActive) : Judgement{Unavailable::None, Risk::Light};
    case Action::LoadFilament:
    case Action::UnloadFilament:
        if (!(action == Action::LoadFilament ? facts.load_macro : facts.unload_macro))
            return no(Unavailable::NoMacro);
        if (facts.printing)
            return no(Unavailable::PrintRunning);
        return {Unavailable::None, facts.paused ? Risk::ConfirmDuringPrint : Risk::Confirm};
    }
    return no(Unavailable::NotConnected);
}

} // namespace U1Controls
} // namespace Slic3r
