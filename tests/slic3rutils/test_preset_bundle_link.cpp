#include <catch2/catch_test_macros.hpp>

#include <map>
#include <string>

#include "libslic3r/PresetBundle.hpp"
#include "slic3r/Utils/OrcaCloudServiceAgent.hpp"
#include "slic3r/Utils/PresetBundleLink.hpp"

using Slic3r::PresetBundleLink;
using Slic3r::resolve_preset_bundle_link;

TEST_CASE("A bundle share link resolves to the public bundle address", "[PresetBundleLink]")
{
    for (const char* text : {"https://cloud.orcaslicer.com/b/3fad3c38f25f", "  https://cloud.orcaslicer.com/b/3fad3c38f25f/  ",
                             "HTTPS://Cloud.OrcaSlicer.com/b/3fad3c38f25f?ref=forum#comments", "https://cloud.orcaslicer.com:443/b/3fad3c38f25f",
                             "https://api.orcaslicer.com/api/v1/bundles/share/3fad3c38f25f"}) {
        INFO(text);
        const PresetBundleLink link = resolve_preset_bundle_link(text);
        CHECK(link.kind == PresetBundleLink::Kind::SharedBundle);
        CHECK(link.token == "3fad3c38f25f");
        CHECK(link.request_url == "https://api.orcaslicer.com/api/v1/bundles/share/3fad3c38f25f");
        CHECK(link.host == "api.orcaslicer.com");
    }
}

TEST_CASE("The host shown to the user is the host that is asked, and a file name is never a reserved name", "[PresetBundleLink]")
{
    PresetBundleLink link = resolve_preset_bundle_link("https://Files.Example.org:8443/a/CON.json?x=1#frag");
    CHECK(link.kind == PresetBundleLink::Kind::File);
    CHECK(link.host == "files.example.org");
    CHECK(link.request_url == "https://Files.Example.org:8443/a/CON.json?x=1");
    CHECK(link.file_name == "link-CON.json");
    // An encoded separator does not make a share token.
    CHECK(resolve_preset_bundle_link("https://cloud.orcaslicer.com/b/abcd%2Fefgh").kind == PresetBundleLink::Kind::Invalid);
    CHECK(resolve_preset_bundle_link("https://cloud.orcaslicer.com?b=abcd1234").kind == PresetBundleLink::Kind::Invalid);
}

TEST_CASE("A link to a bundle file is downloaded as it is and stored under a safe name", "[PresetBundleLink]")
{
    PresetBundleLink link = resolve_preset_bundle_link("https://example.org/files/My%20Printer.ORCA_BUNDLE?download=1#top");
    CHECK(link.kind == PresetBundleLink::Kind::File);
    CHECK(link.request_url == "https://example.org/files/My%20Printer.ORCA_BUNDLE?download=1");
    CHECK(link.file_name == "link-My_20Printer.orca_bundle");

    link = resolve_preset_bundle_link("https://example.org/..%2F..%2Fetc/.json");
    CHECK(link.kind == PresetBundleLink::Kind::File);
    CHECK(link.file_name == "link-bundle.json");

    // A file on OrcaSlicer's own hosts that is not a share link is still only a file.
    link = resolve_preset_bundle_link("https://cloud.orcaslicer.com/downloads/set.zip");
    CHECK(link.kind == PresetBundleLink::Kind::File);
    CHECK(link.file_name == "link-set.zip");
}

TEST_CASE("Anything else is not taken as a bundle link", "[PresetBundleLink]")
{
    for (const char* text : {"", "3fad3c38f25f", "http://cloud.orcaslicer.com/b/3fad3c38f25f", "ftp://example.org/a.zip", "file:///home/user/a.orca_bundle",
                             "https://cloud.orcaslicer.com/b/", "https://cloud.orcaslicer.com/b/ab", "https://cloud.orcaslicer.com/b/3fad/3c38",
                             "https://cloud.orcaslicer.com/b/3fad3c38f25f%2F..", "https://cloud.orcaslicer.com/app/plugins",
                             "https://user@cloud.orcaslicer.com/b/3fad3c38f25f", "https://cloud.orcaslicer.com@example.org/b/3fad3c38f25f",
                             "https://example.org\\@cloud.orcaslicer.com/b/3fad3c38f25f", "https://[::1]/a.zip", "https:///a.zip",
                             "https://example.org/page.html", "https://example.org/a.zip b", "https://example.org/?file=a.zip"}) {
        INFO(text);
        CHECK(resolve_preset_bundle_link(text).kind == PresetBundleLink::Kind::Invalid);
    }
}

TEST_CASE("A shared bundle answer is read into presets and metadata without any request", "[PresetBundleLink][OrcaCloudServiceAgent]")
{
    const std::string answer = R"({"id":"3fa56ae8-2e73-410d-b595-b6c5c90cce57","name":"Example bundle","version":"0.07","description":"d",
        "author":"7175ca0e","shared_profiles":[
          {"id":"p1","name":"Printer A - Variant","content":{"name":"Printer A - Variant","type":"printer","inherits":"Printer A","machine_start_gcode":"START"}},
          {"id":"p2","name":"","content":{"type":"printer"}},
          {"id":"p3","name":"Filament B","content":{"name":"Filament B","type":"filament","inherits":"Generic PLA"}}]})";
    std::map<std::string, std::map<std::string, std::string>> presets;
    Slic3r::BundleMetadata                                     metadata;
    REQUIRE(Slic3r::OrcaCloudServiceAgent::parse_shared_bundle(answer, "3fad3c38f25f", &presets, &metadata) == 0);
    CHECK(metadata.id == "3fa56ae8-2e73-410d-b595-b6c5c90cce57");
    CHECK(metadata.name == "Example bundle");
    CHECK(metadata.version == "0.07");
    REQUIRE(presets.size() == 2); // the nameless entry is skipped
    CHECK(presets.at("Printer A - Variant").at("inherits") == "Printer A");
    CHECK(presets.at("Printer A - Variant").at("machine_start_gcode") == "START");
    CHECK(presets.at("Filament B").at("type") == "filament");

    presets.clear();
    CHECK(Slic3r::OrcaCloudServiceAgent::parse_shared_bundle("<html>not json</html>", "x", &presets, &metadata) != 0);
    CHECK(Slic3r::OrcaCloudServiceAgent::parse_shared_bundle(R"({"id":"a","name":"b"})", "x", &presets, &metadata) != 0);
    CHECK(presets.empty());
}
