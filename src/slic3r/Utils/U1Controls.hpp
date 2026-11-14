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

} // namespace U1Controls
} // namespace Slic3r

#endif // slic3r_U1Controls_hpp_
