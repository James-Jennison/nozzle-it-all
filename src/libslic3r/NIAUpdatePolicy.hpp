#pragma once

#include <optional>
#include <string>
#include <vector>

namespace Slic3r::NIAUpdatePolicy {

enum class Channel { Stable, Beta };

struct ReleaseMetadata
{
    std::string product;
    std::string version;
    std::string channel;
    std::string source_commit;
    std::string build_time;
    std::string platform;
    std::string architecture;
    std::string artifact_url;
    std::string artifact_sha256;
    std::string release_notes;
    std::string settings_requirements;
    std::string signature;
};

// P6 intentionally keeps the product update channel closed until NIA release
// infrastructure and its signature policy are implemented and reviewed.
bool channel_available();
bool is_nia_release(const ReleaseMetadata& release, Channel requested_channel);
std::optional<ReleaseMetadata> select_release(const std::vector<ReleaseMetadata>& releases, Channel requested_channel);

} // namespace Slic3r::NIAUpdatePolicy
