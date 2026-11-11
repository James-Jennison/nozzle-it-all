#include <catch2/catch_test_macros.hpp>

#include <string>

#include "libslic3r/NIAUpdatePolicy.hpp"

using namespace Slic3r::NIAUpdatePolicy;

namespace {

ReleaseMetadata nia_release(const std::string& channel)
{
    return {"nozzle-it-all-desktop",
            "0.1.0",
            channel,
            "0123456789abcdef",
            "2026-11-04T00:00:00Z",
            "linux",
            "x86_64",
            "https://updates.nozzle-it-all.invalid/nozzle-it-all-desktop.AppImage",
            std::string(64, 'a'),
            "Initial candidate",
            "settings-schema=1",
            ""};
}

} // namespace

TEST_CASE("NIA application updates remain disabled until release infrastructure is ready", "[nia_update_policy]")
{
    CHECK_FALSE(channel_available());
    CHECK_FALSE(select_release({nia_release("stable")}, Channel::Stable).has_value());
    CHECK_FALSE(select_release({nia_release("beta")}, Channel::Beta).has_value());
}

TEST_CASE("Upstream Orca releases cannot satisfy either NIA channel", "[nia_update_policy]")
{
    ReleaseMetadata stable = nia_release("stable");
    stable.product         = "orcaslicer";
    ReleaseMetadata beta   = nia_release("beta");
    beta.product           = "orcaslicer";

    CHECK_FALSE(is_nia_release(stable, Channel::Stable));
    CHECK_FALSE(is_nia_release(beta, Channel::Beta));
}

TEST_CASE("Snapmaker releases cannot satisfy either NIA channel", "[nia_update_policy]")
{
    ReleaseMetadata stable = nia_release("stable");
    stable.product         = "snapmaker-orca";
    ReleaseMetadata beta   = nia_release("beta");
    beta.product           = "snapmaker-orca";

    CHECK_FALSE(is_nia_release(stable, Channel::Stable));
    CHECK_FALSE(is_nia_release(beta, Channel::Beta));
}

TEST_CASE("NIA metadata is channel-bound and requires source and artifact identity", "[nia_update_policy]")
{
    ReleaseMetadata stable = nia_release("stable");
    CHECK(is_nia_release(stable, Channel::Stable));
    CHECK_FALSE(is_nia_release(stable, Channel::Beta));

    stable.source_commit.clear();
    CHECK_FALSE(is_nia_release(stable, Channel::Stable));
    stable                 = nia_release("stable");
    stable.artifact_sha256 = "not-a-sha256";
    CHECK_FALSE(is_nia_release(stable, Channel::Stable));
}
