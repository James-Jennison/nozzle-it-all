#ifndef slic3r_U1Controls_hpp_
#define slic3r_U1Controls_hpp_

#include <nlohmann/json.hpp>

#include <string>

namespace Slic3r {

/// Rules for the commands sent to a Snapmaker U1 while it prints. No wxWidgets or network dependency, so
/// they are unit-tested directly.
///
/// Resume is guarded the way Start is: it moves the toolhead and extrudes, so it is sent only when the
/// printer itself says it is paused on the print the button was shown for, it is sent once, and it is never
/// repeated on its own. Pause and Stop stay light: sending either twice is harmless, and nothing here may
/// ever stand in the way of a Stop.
namespace U1Controls {

enum class ResumeRefusal {
    None,
    Unreachable, ///< the printer gave no usable print state
    NotPaused,   ///< the printer is not paused; Resume would do nothing or act on another state
    OtherFile    ///< the printer is paused on a print other than the one the button was shown for
};

struct ResumeCheck
{
    ResumeRefusal refusal = ResumeRefusal::Unreachable;
    std::string   printer_state; ///< print_stats.state as reported: "paused", "printing", "standby", ...
    std::string   filename;      ///< print_stats.filename as reported
    explicit operator bool() const { return refusal == ResumeRefusal::None; }
};

/// Judges *status* (the "status" object of Moonraker's printer/objects/query?print_stats, read just before
/// the Resume would be sent) against *shown_file*, the print the Resume button was shown for. Anything the
/// decision depends on that the printer does not report is a refusal, never a pass.
ResumeCheck check_resume(const nlohmann::json &status, const std::string &shown_file);

/// True when both name the same file; Moonraker may report a path with or without its folder.
bool same_file(const std::string &a, const std::string &b);

/// The controls a person can send besides Pause, Resume and Stop, in the three groups the Printer tab shows.
enum class Action {
    HomeAll,              // Motion
    MotorsOff,
    SetNozzleTemperature, // Temperature
    SetBedTemperature,
    HeatersOff,
    LoadFilament,         // Filament
    UnloadFilament
};
enum class Group { Motion, Temperature, Filament };
Group group_of(Action action);

/// How much is asked before a control is sent.
enum class Risk {
    Light,             ///< sent at once: it cools or switches off an idle printer, and sending it twice is harmless
    Confirm,           ///< one question first: it moves, heats or extrudes
    ConfirmDuringPrint ///< one question that names the print it changes
};
/// Why a control is shown disabled. Every value but None has a sentence for the user.
enum class Unavailable {
    None,
    NotConnected, ///< no live connection to the printer
    NotReady,     ///< the printer says its firmware is not ready
    PrintActive,  ///< not while a print is running or paused
    PrintRunning, ///< not while a print is running; possible while it is paused
    NoHeatedBed,
    NoMacro       ///< the printer has no macro for it
};
/// What the printer last reported, as far as the decision needs it. Anything unknown is false.
struct PrinterFacts
{
    bool connected    = false;
    bool ready        = false; ///< Klippy ready
    bool printing     = false;
    bool paused       = false;
    bool heated_bed   = false;
    bool load_macro   = false; ///< gcode_macro LOAD_FILAMENT exists
    bool unload_macro = false; ///< gcode_macro UNLOAD_FILAMENT exists
};
struct Judgement
{
    Unavailable why  = Unavailable::NotConnected;
    Risk        risk = Risk::Confirm;
    explicit operator bool() const { return why == Unavailable::None; }
};
/// Whether *action* may be sent to a printer in this state and how much to ask first. *target* is the
/// temperature for the two Set actions (0 switches that heater off), ignored otherwise.
Judgement judge(Action action, const PrinterFacts &facts, int target = 0);

} // namespace U1Controls
} // namespace Slic3r

#endif // slic3r_U1Controls_hpp_
