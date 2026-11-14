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

} // namespace U1Controls
} // namespace Slic3r
