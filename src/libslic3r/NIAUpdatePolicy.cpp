#include "NIAUpdatePolicy.hpp"

#include <algorithm>
#include <cctype>
#include <optional>
#include <string>
#include <vector>

namespace Slic3r::NIAUpdatePolicy {
namespace {

bool is_hex(const std::string& value)
{
    return !value.empty() && std::all_of(value.begin(), value.end(), [](unsigned char ch) { return std::isxdigit(ch) != 0; });
}

const char* channel_name(Channel channel) { return channel == Channel::Stable ? "stable" : "beta"; }

} // namespace

bool channel_available() { return false; }

bool is_nia_release(const ReleaseMetadata& release, Channel requested_channel)
{
    return release.product == "nozzle-it-all-desktop" && release.channel == channel_name(requested_channel) && !release.version.empty() &&
           release.source_commit.size() >= 7 && is_hex(release.source_commit) && !release.build_time.empty() && !release.platform.empty() &&
           !release.architecture.empty() && !release.artifact_url.empty() && release.artifact_sha256.size() == 64 &&
           is_hex(release.artifact_sha256) && !release.release_notes.empty() && !release.settings_requirements.empty();
}

std::optional<ReleaseMetadata> select_release(const std::vector<ReleaseMetadata>& releases, Channel requested_channel)
{
    if (!channel_available())
        return std::nullopt;

    const auto release = std::find_if(releases.begin(), releases.end(),
                                      [&](const ReleaseMetadata& candidate) { return is_nia_release(candidate, requested_channel); });
    return release == releases.end() ? std::nullopt : std::optional<ReleaseMetadata>(*release);
}

} // namespace Slic3r::NIAUpdatePolicy
