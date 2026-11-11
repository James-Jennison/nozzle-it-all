#include <catch2/catch_test_macros.hpp>
#include <catch2/catch_message.hpp>

#include "libslic3r/NIASettingsMigration.hpp"

#include <boost/filesystem/directory.hpp>
#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <iomanip>
#include <iterator>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace fs = boost::filesystem;
using namespace Slic3r::NIASettingsMigration;

namespace {
struct TempTree {
    fs::path root = fs::temp_directory_path() / fs::unique_path("nia-migration-%%%%-%%%%");
    TempTree() { fs::create_directories(root); }
    ~TempTree() { boost::system::error_code ignored; fs::remove_all(root, ignored); }
};

void write_file(const fs::path &path, const std::string &contents = "test")
{
    fs::create_directories(path.parent_path());
    boost::nowide::ofstream stream(path.string());
    stream << contents;
}

std::string read_file(const fs::path &path)
{
    boost::nowide::ifstream stream(path.string());
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}

std::string tree_sha256(const fs::path &root)
{
    std::vector<fs::path> entries;
    for (fs::recursive_directory_iterator it(root), end; it != end; ++it)
        entries.push_back(it->path());
    std::sort(entries.begin(), entries.end(), [&](const fs::path &left, const fs::path &right) {
        return fs::relative(left, root).generic_string() < fs::relative(right, root).generic_string();
    });

    EVP_MD_CTX *context = EVP_MD_CTX_new();
    REQUIRE(context != nullptr);
    REQUIRE(EVP_DigestInit_ex(context, EVP_sha256(), nullptr) == 1);
    auto update = [&](const void *data, size_t size) { REQUIRE(EVP_DigestUpdate(context, data, size) == 1); };
    auto field = [&](const std::string &value) {
        const std::uint64_t size = value.size();
        update(&size, sizeof(size));
        update(value.data(), value.size());
    };
    for (const fs::path &entry : entries) {
        const std::string relative = fs::relative(entry, root).generic_string();
        if (fs::is_directory(entry)) {
            field("directory");
            field(relative);
            continue;
        }
        field("file");
        field(relative);
        const std::uint64_t file_size = fs::file_size(entry);
        update(&file_size, sizeof(file_size));
        boost::nowide::ifstream input(entry.string(), std::ios::binary);
        REQUIRE(input.good());
        char buffer[64 * 1024];
        while (input) {
            input.read(buffer, sizeof(buffer));
            if (input.gcount() > 0)
                update(buffer, static_cast<size_t>(input.gcount()));
        }
        REQUIRE(input.eof());
    }
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned int size = 0;
    REQUIRE(EVP_DigestFinal_ex(context, digest, &size) == 1);
    EVP_MD_CTX_free(context);
    REQUIRE(size == 32);
    std::ostringstream output;
    output << std::hex << std::setfill('0');
    for (unsigned int i = 0; i < size; ++i)
        output << std::setw(2) << static_cast<unsigned>(digest[i]);
    return output.str();
}

void write_recovery_state(const fs::path &state, const fs::path &target, const fs::path &staging,
                          const fs::path &backup, bool destination_existed)
{
    write_file(state, nlohmann::json({
        {"schema", 4},
        {"destination", fs::absolute(target).lexically_normal().string()},
        {"staging", fs::absolute(staging).lexically_normal().string()},
        {"backup", destination_existed ? fs::absolute(backup).lexically_normal().string() : std::string()},
        {"destination_existed", destination_existed},
        {"completion_marker", "nia-settings-migration-test.txt"},
        {"staging_tree_sha256", tree_sha256(fs::exists(staging) ? staging : target)},
        {"destination_tree_sha256", destination_existed ? tree_sha256(fs::exists(backup) ? backup : target) : std::string()}
    }).dump());
}
} // namespace

TEST_CASE("NIA settings discovery preserves required source order", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path nia = tree.root / "nia-legacy";
    const fs::path hybrid = tree.root / "hybrid";
    const fs::path snapmaker = tree.root / "snapmaker";
    const fs::path orca = tree.root / "orca";
    write_file(nia / "nozzle-it-all.conf");
    write_file(hybrid / "OrcaSlicer.conf");
    write_file(snapmaker / "Snapmaker_Orca.conf");
    write_file(orca / "OrcaSlicer.conf");

    const auto found = discover({
        {SourceKind::NiaDesktop, "NIA", nia.string(), {"nozzle-it-all.conf"}},
        {SourceKind::HybridOrca, "Hybrid", hybrid.string(), {"OrcaSlicer.conf"}},
        {SourceKind::SnapmakerOrca, "Snapmaker", snapmaker.string(), {"Snapmaker_Orca.conf"}},
        {SourceKind::OrcaSlicer, "Orca", orca.string(), {"OrcaSlicer.conf"}}
    }, target.string());

    REQUIRE(found.size() == 4);
    CHECK(found[0].source.kind == SourceKind::NiaDesktop);
    CHECK(found[1].source.kind == SourceKind::HybridOrca);
    CHECK(found[2].source.kind == SourceKind::SnapmakerOrca);
    CHECK(found[3].source.kind == SourceKind::OrcaSlicer);
}

TEST_CASE("NIA settings migration preserves Snapmaker U1 custom presets and excludes state", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf",
               R"({"app":{"language":"en"},"local_machines":{"u1":{"dev_ip":"192.0.2.1","access_code":"do-not-copy","Token":"do-not-copy"}},"access_code":{"u1":"do-not-copy"}})");
    write_file(source / "user" / "default" / "filament" / "Custom.json",
               R"({"name":"Custom TPU","filament_type":["TPU"],"profile_note":"Basic printing profile","basic_word":"Basic settings","basic_hyphen":"Basic printing-profile","basic_underscore":"Basic printing_profile","aiza_note":"Aizawanote-pla","dotted_note":"abcdefgh-ijklmnop.qrstuvwx-12345678.abcdefg","reference_url":"https://example.invalid/?mode=lan&label=Bearer%20placeholder-value&ok=1"})");
    write_file(source / "user" / "default" / "printer" / "Snapmaker U1.json",
               R"({"name":"Snapmaker U1","print_host":"https://user:password@printer.invalid/path?mode=lan&access%5Ftoken=do-not-copy&ok=1&#session-token","print_host_no_path":"https://user:password@printer.invalid","print_host_user_only":"https://user@printer.invalid","path_at_sign":"https://printer.invalid/path@segment","printhost_user":"user","printhost_password":"do-not-copy","printhost_apikey":"do-not-copy","http_auth_blob":"do-not-copy","vendor_auth":"do-not-copy","private_key":"do-not-copy","secret_key":"do-not-copy","api_secret":"do-not-copy","passwd":"do-not-copy","pwd":"do-not-copy","pass":"do-not-copy","oauth":"do-not-copy","accessToken":"do-not-copy","refreshToken":"do-not-copy","secretKey":"do-not-copy","material_note":"token-like-but-not-classified","unrelated_bearer":"Bearer sentinel-value-only-for-test","unrelated_basic":"Basic dXNlcjpwYXNzd29yZA==","unrelated_aws":"AWS4-HMAC-SHA256 Credential=test","unrelated_slack":"xoxb-123456789-abcdefgh","unrelated_google_key":"AIzaAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","unrelated_google_oauth":"ya29.example-oauth-credential","unrelated_jwt":"eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJl","nested":{"Session_Token":"do-not-copy","device_session_secret":"do-not-copy"}})");
    write_file(source / "Cache" / "stale.bin");
    write_file(source / "ota" / "update.json");
    write_file(source / "orca_refresh_token.sec", "secret");
    write_file(source / "session.lock");
    write_file(source / "opaque.pem", "credential-material");
    write_file(source / "unknown.db", "credential-material");

    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}}, "OrcaSlicer.conf"};
    const Result first = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    REQUIRE(first.success);
    CHECK(first.partial);
    CHECK(fs::is_regular_file(source / "OrcaSlicer.conf"));
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    CHECK(fs::is_regular_file(target / "user" / "default" / "filament" / "Custom.json"));
    CHECK(fs::is_regular_file(target / "user" / "default" / "printer" / "Snapmaker U1.json"));
    CHECK_FALSE(fs::exists(target / "Cache" / "stale.bin"));
    CHECK_FALSE(fs::exists(target / "ota" / "update.json"));
    CHECK_FALSE(fs::exists(target / "orca_refresh_token.sec"));
    CHECK_FALSE(fs::exists(target / "session.lock"));
    CHECK_FALSE(fs::exists(target / "opaque.pem"));
    CHECK_FALSE(fs::exists(target / "unknown.db"));
    CHECK(fs::is_regular_file(first.report_path));

    const nlohmann::json migrated_filament = nlohmann::json::parse(
        read_file(target / "user" / "default" / "filament" / "Custom.json"));
    CHECK(migrated_filament["profile_note"] == "Basic printing profile");
    CHECK(migrated_filament["basic_word"] == "Basic settings");
    CHECK(migrated_filament["basic_hyphen"] == "Basic printing-profile");
    CHECK(migrated_filament["basic_underscore"] == "Basic printing_profile");
    CHECK(migrated_filament["aiza_note"] == "Aizawanote-pla");
    CHECK(migrated_filament["dotted_note"] == "abcdefgh-ijklmnop.qrstuvwx-12345678.abcdefg");
    CHECK(migrated_filament["reference_url"] == "https://example.invalid/?mode=lan&ok=1");

    boost::nowide::ifstream migrated_printer_stream(
        (target / "user" / "default" / "printer" / "Snapmaker U1.json").string());
    nlohmann::json migrated_printer;
    REQUIRE_NOTHROW(migrated_printer_stream >> migrated_printer);
    CHECK(migrated_printer["name"] == "Snapmaker U1");
    CHECK_FALSE(migrated_printer.contains("printhost_user"));
    CHECK_FALSE(migrated_printer.contains("printhost_password"));
    CHECK_FALSE(migrated_printer.contains("printhost_apikey"));
    CHECK_FALSE(migrated_printer.contains("http_auth_blob"));
    CHECK_FALSE(migrated_printer.contains("vendor_auth"));
    CHECK_FALSE(migrated_printer.contains("private_key"));
    CHECK_FALSE(migrated_printer.contains("secret_key"));
    CHECK_FALSE(migrated_printer.contains("api_secret"));
    CHECK_FALSE(migrated_printer.contains("passwd"));
    CHECK_FALSE(migrated_printer.contains("pwd"));
    CHECK_FALSE(migrated_printer.contains("pass"));
    CHECK_FALSE(migrated_printer.contains("oauth"));
    CHECK_FALSE(migrated_printer.contains("accessToken"));
    CHECK_FALSE(migrated_printer.contains("refreshToken"));
    CHECK_FALSE(migrated_printer.contains("secretKey"));
    CHECK_FALSE(migrated_printer["nested"].contains("Session_Token"));
    CHECK_FALSE(migrated_printer["nested"].contains("device_session_secret"));
    CHECK(migrated_printer["material_note"] == "token-like-but-not-classified");
    CHECK_FALSE(migrated_printer.contains("unrelated_bearer"));
    CHECK_FALSE(migrated_printer.contains("unrelated_basic"));
    CHECK_FALSE(migrated_printer.contains("unrelated_aws"));
    CHECK_FALSE(migrated_printer.contains("unrelated_slack"));
    CHECK_FALSE(migrated_printer.contains("unrelated_google_key"));
    CHECK_FALSE(migrated_printer.contains("unrelated_google_oauth"));
    CHECK_FALSE(migrated_printer.contains("unrelated_jwt"));
    CHECK(migrated_printer["print_host"] == "https://printer.invalid/path?mode=lan&ok=1");
    CHECK(migrated_printer["print_host_no_path"] == "https://printer.invalid");
    CHECK(migrated_printer["print_host_user_only"] == "https://printer.invalid");
    CHECK(migrated_printer["path_at_sign"] == "https://printer.invalid/path@segment");

    boost::nowide::ifstream migrated_config((target / "nozzle-it-all-desktop.conf").string());
    const std::string migrated((std::istreambuf_iterator<char>(migrated_config)), std::istreambuf_iterator<char>());
    CHECK(migrated.find("do-not-copy") == std::string::npos);
    CHECK(migrated.find("access_code") == std::string::npos);
    CHECK(migrated.find("192.0.2.1") != std::string::npos);

    boost::nowide::ifstream migration_report(first.report_path);
    const std::string report((std::istreambuf_iterator<char>(migration_report)), std::istreambuf_iterator<char>());
    CHECK(report.find("source_schema=json-sanitized") != std::string::npos);
    CHECK(report.find("recovery_path=" + source.string()) != std::string::npos);
    CHECK(report.find("sanitizer_version=5") != std::string::npos);
    CHECK(report.find("value_pattern_scanning=true") != std::string::npos);
    CHECK(report.find("uninspected_files_copied=false") != std::string::npos);
    CHECK(report.find("credentials_copied=not-claimed") != std::string::npos);
    CHECK(report.find("skipped_file=opaque.pem\treason=unsupported-uninspected-file") != std::string::npos);
    CHECK(report.find("skipped_file=unknown.db\treason=unsupported-uninspected-file") != std::string::npos);
    CHECK(report.find("sanitized_file=OrcaSlicer.conf\treason=credential-content-removed") != std::string::npos);
    CHECK(report.find("sanitized_file=user/default/filament/Custom.json\treason=credential-content-removed") != std::string::npos);
    CHECK(report.find("sanitized_file=user/default/printer/Snapmaker U1.json\treason=credential-content-removed") != std::string::npos);
    CHECK(first.skipped_files.size() == 2);
    CHECK(first.sanitized_files.size() == 3);

    CHECK(discover({candidate.source}, target.string()).empty());

    const fs::path equivalent_target = target / ".." / target.filename();
    CHECK(discover({{SourceKind::HybridOrca, "same target", equivalent_target.string(), {"nozzle-it-all-desktop.conf"}}},
                   target.string(), true)
              .empty());
}

TEST_CASE("NIA settings migration detects INI content stored in a conf file", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", "[app]\nlanguage=en\n[credentials]\npassword=do-not-copy\n");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK(result.partial);
    CHECK(read_file(result.report_path).find(
              "sanitized_file=OrcaSlicer.conf\treason=credential-content-removed") != std::string::npos);
    boost::nowide::ifstream migrated_stream((target / "nozzle-it-all-desktop.conf").string());
    nlohmann::json migrated;
    REQUIRE_NOTHROW(migrated_stream >> migrated);
    CHECK(migrated["app"]["language"] == "en");
    CHECK_FALSE(migrated.contains("credentials"));
}

TEST_CASE("NIA settings migration detects sectionless INI stored in a conf file", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", "nozzle_diameter=0.4\ntemperature=210\n");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK_FALSE(result.partial);
    boost::nowide::ifstream migrated_stream((target / "nozzle-it-all-desktop.conf").string());
    nlohmann::json migrated;
    REQUIRE_NOTHROW(migrated_stream >> migrated);
    CHECK(migrated["app"]["nozzle_diameter"] == "0.4");
    CHECK(migrated["app"]["temperature"] == "210");
    CHECK_FALSE(migrated.contains(""));
}

TEST_CASE("NIA settings migration converts legacy INI and scrubs normalized secret names", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.ini",
               "; legacy settings\n[app]\nlanguage = en\nToken = do-not-copy\n"
               "malformed legacy line\n"
               "[presets]\nprinter = Snapmaker U1\nfilament = Custom TPU\n"
               "print_host = https://legacy:password@printer.invalid/\n"
               "[Credentials]\nPassword = do-not-copy\n");
    write_file(source / "user" / "default" / "printer" / "Custom U1.json",
               R"({"name":"Custom U1","printer_model":"Snapmaker U1"})");

    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.ini"}},
                              "OrcaSlicer.ini"};
    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK(result.partial);
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    CHECK(fs::is_regular_file(target / "user" / "default" / "printer" / "Custom U1.json"));

    boost::nowide::ifstream migrated_stream((target / "nozzle-it-all-desktop.conf").string());
    nlohmann::json migrated;
    REQUIRE_NOTHROW(migrated_stream >> migrated);
    CHECK(migrated["app"]["language"] == "en");
    CHECK(migrated["presets"]["printer"] == "Snapmaker U1");
    CHECK(migrated["presets"]["filament"] == "Custom TPU");
    CHECK(migrated["presets"]["print_host"] == "https://printer.invalid/");
    CHECK_FALSE(migrated["app"].contains("Token"));
    CHECK_FALSE(migrated.contains("Credentials"));

    boost::nowide::ifstream report_stream(result.report_path);
    const std::string report((std::istreambuf_iterator<char>(report_stream)), std::istreambuf_iterator<char>());
    CHECK(report.find("source_schema=ini-converted-json") != std::string::npos);
    CHECK(report.find("status=partial") != std::string::npos);
    CHECK(report.find("skipped_file=OrcaSlicer.ini\treason=malformed-ini-lines=1") != std::string::npos);
}

TEST_CASE("NIA settings migration preserves benign names ending in pass", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf",
               R"({"app":{"bypass_filter":"1","compass_mode":"north","my_pass":"remove","api_pass":"remove"}})");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    boost::nowide::ifstream migrated_stream((target / "nozzle-it-all-desktop.conf").string());
    nlohmann::json migrated;
    REQUIRE_NOTHROW(migrated_stream >> migrated);
    CHECK(migrated["app"]["bypass_filter"] == "1");
    CHECK(migrated["app"]["compass_mode"] == "north");
    CHECK_FALSE(migrated["app"].contains("my_pass"));
    CHECK_FALSE(migrated["app"].contains("api_pass"));
}

TEST_CASE("NIA settings migration reports duplicate INI keys and keeps the final value", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.ini", "[app]\nlanguage=en\nlanguage=fr\n");
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.ini"}},
                              "OrcaSlicer.ini"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    boost::nowide::ifstream migrated_stream((target / "nozzle-it-all-desktop.conf").string());
    nlohmann::json migrated;
    REQUIRE_NOTHROW(migrated_stream >> migrated);
    CHECK(migrated["app"]["language"] == "fr");
    boost::nowide::ifstream report_stream(result.report_path);
    const std::string report((std::istreambuf_iterator<char>(report_stream)), std::istreambuf_iterator<char>());
    CHECK(report.find("status=partial") != std::string::npos);
    CHECK(report.find("skipped_file=OrcaSlicer.ini\treason=duplicate-ini-keys=1") != std::string::npos);
}

TEST_CASE("NIA settings migration rejects non-object JSON configs", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"(["not","a","configuration"])");
    write_file(source / "user" / "default" / "printer" / "Custom U1.json",
               R"({"name":"Custom U1","printer_model":"Snapmaker U1"})");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}}, "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK_FALSE(fs::exists(target / "nozzle-it-all-desktop.conf"));
    CHECK(fs::is_regular_file(target / "user" / "default" / "printer" / "Custom U1.json"));
    boost::nowide::ifstream report_stream(result.report_path);
    const std::string report((std::istreambuf_iterator<char>(report_stream)), std::istreambuf_iterator<char>());
    CHECK(report.find("skipped_file=OrcaSlicer.conf\treason=unsupported-json-schema") != std::string::npos);
}

TEST_CASE("NIA settings migration never reinterprets malformed JSON as INI", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", "{\"app\" = {\"language\" = \"en\"}}");
    write_file(source / "user" / "default" / "printer" / "Custom U1.json",
               R"({"name":"Custom U1","printer_model":"Snapmaker U1"})");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}}, "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK_FALSE(fs::exists(target / "nozzle-it-all-desktop.conf"));
    boost::nowide::ifstream report_stream(result.report_path);
    const std::string report((std::istreambuf_iterator<char>(report_stream)), std::istreambuf_iterator<char>());
    CHECK(report.find("skipped_file=OrcaSlicer.conf\treason=invalid-json") != std::string::npos);
}

TEST_CASE("NIA settings migration rejects malformed JSON containing an equals sign", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"a": "x=y")");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK_FALSE(fs::exists(target / "nozzle-it-all-desktop.conf"));
    boost::nowide::ifstream report_stream(result.report_path);
    const std::string report((std::istreambuf_iterator<char>(report_stream)), std::istreambuf_iterator<char>());
    CHECK(report.find("skipped_file=OrcaSlicer.conf\treason=invalid-json") != std::string::npos);
}

TEST_CASE("NIA settings recovery rejects non-directory transaction paths", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261104-010205";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(staging, "not-a-directory");
    write_file(state, nlohmann::json({
        {"schema", 4},
        {"destination", fs::absolute(target).lexically_normal().string()},
        {"staging", fs::absolute(staging).lexically_normal().string()},
        {"backup", std::string()},
        {"destination_existed", false},
        {"completion_marker", "nia-settings-migration-test.txt"},
        {"staging_tree_sha256", std::string(64, '0')},
        {"destination_tree_sha256", std::string()}
    }).dump());

    const RecoveryResult result = recover_interrupted(target.string());

    CHECK_FALSE(result.recovered);
    CHECK(result.error.find("non-directory or symbolic link") != std::string::npos);
    CHECK(fs::is_regular_file(staging));
}

TEST_CASE("NIA settings migration cancellation leaves source and target untouched", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", false);

    CHECK(result.cancelled);
    CHECK_FALSE(result.success);
    CHECK(fs::is_regular_file(source / "OrcaSlicer.conf"));
    CHECK_FALSE(fs::exists(target));
}

TEST_CASE("NIA settings migration requires explicit confirmation before partial replacement", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(source / "unsupported.bin", "opaque");
    write_file(target / "existing.txt", "preserve");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result preview = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true, false);

    CHECK_FALSE(preview.success);
    CHECK(preview.partial);
    CHECK(preview.partial_confirmation_required);
    REQUIRE(preview.skipped_files.size() == 1);
    CHECK(preview.skipped_files[0].find("unsupported.bin") != std::string::npos);
    REQUIRE(fs::is_directory(preview.staging_path));
    REQUIRE(fs::is_regular_file(preview.report_path));
    CHECK(fs::is_regular_file(target / "existing.txt"));
    CHECK_FALSE(fs::exists(target / "nozzle-it-all-desktop.conf"));

    // Approval commits the exact reviewed staging transaction, not a fresh copy
    // of source files that may have changed while the confirmation was open.
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"fr"}})");
    const Result approved = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true, true, &preview);
    INFO(approved.error);
    REQUIRE(approved.success);
    CHECK(approved.partial);
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    CHECK(read_file(target / "nozzle-it-all-desktop.conf").find("en") != std::string::npos);
    CHECK(read_file(target / "nozzle-it-all-desktop.conf").find("fr") == std::string::npos);
    CHECK_FALSE(fs::exists(preview.staging_path));
    CHECK(fs::is_directory(approved.backup_path));
}

TEST_CASE("NIA settings migration discards a declined prepared partial import", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(source / "unsupported.bin", "opaque");
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result preview = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true, false);
    REQUIRE(preview.partial_confirmation_required);
    REQUIRE(fs::is_directory(preview.staging_path));
    CHECK(discard_prepared_candidate(preview, target.string()).empty());
    CHECK_FALSE(fs::exists(preview.staging_path));
    CHECK_FALSE(fs::exists(target));
}

TEST_CASE("NIA settings migration skips an oversized structured config", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    const fs::path oversized = source / "user" / "default" / "filament" / "Oversized.json";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(oversized, "{");
    fs::resize_file(oversized, 64u * 1024u * 1024u + 1u);
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result preview = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true, false);
    REQUIRE(preview.partial_confirmation_required);
    REQUIRE(preview.skipped_files.size() == 1);
    CHECK(preview.skipped_files[0].find("Oversized.json (file-too-large)") != std::string::npos);
    CHECK(discard_prepared_candidate(preview, target.string()).empty());
}

#ifndef _WIN32
TEST_CASE("NIA settings migration reports unreadable structured files as partial", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    const fs::path unreadable = source / "user" / "default" / "filament" / "Unreadable.json";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(unreadable, R"({"name":"Unreadable"})");
    fs::permissions(unreadable, fs::no_perms);
    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result preview = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true, false);
    fs::permissions(unreadable, fs::perms::owner_read | fs::perms::owner_write);

    CHECK_FALSE(preview.success);
    CHECK(preview.partial);
    CHECK(preview.partial_confirmation_required);
    REQUIRE(preview.skipped_files.size() == 1);
    CHECK(preview.skipped_files[0].find("Unreadable.json (unreadable)") != std::string::npos);
}
#endif

TEST_CASE("NIA settings migration remembers an explicit import decline", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    const std::vector<SourceSpec> sources = {
        {SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}}
    };

    REQUIRE_FALSE(import_declined(target.string()));
    REQUIRE(record_import_declined(target.string()).empty());
    CHECK(import_declined(target.string()));
    CHECK_FALSE(is_meaningful_data_dir(target.string()));
    CHECK(discover(sources, target.string()).empty());
}

TEST_CASE("NIA settings migration can schedule a reversible import after decline", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(target / "nozzle-it-all-desktop.conf", R"({"app":{"language":"fr"}})");
    const std::vector<SourceSpec> sources = {
        {SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}}
    };

    REQUIRE(record_import_declined(target.string()).empty());
    CHECK(discover(sources, target.string()).empty());
    REQUIRE(record_import_requested(target.string()).empty());
    CHECK(import_requested(target.string()));
    REQUIRE(discover(sources, target.string(), true).size() == 1);
    REQUIRE(clear_import_requested(target.string()).empty());
    CHECK_FALSE(import_requested(target.string()));
}

TEST_CASE("NIA settings metadata names do not hide unrelated user data", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    write_file(target / "nia-settings-import-declined", "declined");
    write_file(target / "nia-settings-migration-20261105.txt", "report");
    write_file(target / ".DS_Store", "metadata");
    write_file(target / "desktop.ini", "metadata");
    write_file(target / "Thumbs.db", "metadata");
    CHECK_FALSE(is_meaningful_data_dir(target.string()));

    write_file(target / "nia-settings-user-notes.txt", "user data");
    CHECK(is_meaningful_data_dir(target.string()));
}

TEST_CASE("NIA settings discovery deduplicates canonical source aliases", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    const fs::path alias = source / ".." / source.filename();

    const auto found = discover({
        {SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}},
        {SourceKind::OrcaSlicer, "Alias", alias.string(), {"OrcaSlicer.conf"}}
    }, (tree.root / "target").string());

    REQUIRE(found.size() == 1);
    CHECK(found.front().source.label == "Hybrid");
}

TEST_CASE("NIA settings migration renames only the top-level source config", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(source / "user" / "OrcaSlicer.conf", "[profile]\nname=Nested profile\n");
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    CHECK(fs::is_regular_file(target / "user" / "OrcaSlicer.conf"));
    CHECK_FALSE(fs::exists(target / "user" / "nozzle-it-all-desktop.conf"));
}

TEST_CASE("NIA settings migration backs up an existing target before replacement", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(target / "user" / "default" / "printer" / "Existing NIA.json", "existing");
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    REQUIRE_FALSE(result.backup_path.empty());
    CHECK(fs::is_regular_file(fs::path(result.backup_path) / "user" / "default" / "printer" / "Existing NIA.json"));
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
}

TEST_CASE("NIA settings discovery tolerates missing and partial candidates", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path partial = tree.root / "partial";
    write_file(partial / "user" / "default" / "printer" / "U1.json");

    const auto found = discover({
        {SourceKind::HybridOrca, "Missing", (tree.root / "missing").string(), {"OrcaSlicer.conf"}},
        {SourceKind::SnapmakerOrca, "Partial", partial.string(), {"Snapmaker_Orca.conf"}}
    }, (tree.root / "target").string());

    REQUIRE(found.size() == 1);
    CHECK(found.front().config_name.empty());
}

TEST_CASE("NIA settings migration skips a corrupt config but preserves valid presets", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", "{not-json");
    write_file(source / "user" / "default" / "printer" / "Custom U1.json",
               R"({"name":"Custom U1","printer_model":"Snapmaker U1"})");
    write_file(source / "user" / "default" / "printer" / "Broken.json", "{not-json");

    const Candidate candidate{{SourceKind::HybridOrca, "Hybrid", source.string(), {"OrcaSlicer.conf"}}, "OrcaSlicer.conf"};
    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK_FALSE(fs::exists(target / "nozzle-it-all-desktop.conf"));
    CHECK(fs::is_regular_file(target / "user" / "default" / "printer" / "Custom U1.json"));
    CHECK_FALSE(fs::exists(target / "user" / "default" / "printer" / "Broken.json"));
    boost::nowide::ifstream report_stream(result.report_path);
    const std::string report((std::istreambuf_iterator<char>(report_stream)), std::istreambuf_iterator<char>());
    CHECK(report.find("skipped_file=OrcaSlicer.conf\treason=invalid-json") != std::string::npos);
    CHECK(report.find("skipped_file=user/default/printer/Broken.json\treason=invalid-json") != std::string::npos);
}

TEST_CASE("NIA settings migration explicitly excludes cloud synchronization sidecars", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    write_file(source / "user" / "default" / "filament" / "Custom.json", R"({"name":"Custom PLA"})");
    write_file(source / "user" / "default" / "filament" / "Custom.info", R"({"user_id":"source-account"})");
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    INFO(result.error);
    REQUIRE(result.success);
    CHECK(fs::is_regular_file(target / "user" / "default" / "filament" / "Custom.json"));
    CHECK_FALSE(fs::exists(target / "user" / "default" / "filament" / "Custom.info"));
    CHECK(read_file(result.report_path).find(
              "skipped_file=user/default/filament/Custom.info\treason=preset-cloud-sync-sidecar-excluded") != std::string::npos);
}

TEST_CASE("NIA settings migration completes activation after the backup rename", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261104-010203";
    const fs::path backup = tree.root / "target.backup-20261104-010203";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(staging / "nozzle-it-all-desktop.conf", R"({"app":{"language":"en"}})");
    write_file(backup / "user" / "default" / "printer" / "Existing NIA.json", "existing");
    write_recovery_state(state, target, staging, backup, true);

    const RecoveryResult result = recover_interrupted(target.string());

    INFO(result.error);
    REQUIRE(result.recovered);
    CHECK(result.error.empty());
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    CHECK_FALSE(fs::exists(target / "user" / "default" / "printer" / "Existing NIA.json"));
    CHECK_FALSE(fs::exists(staging));
    CHECK(fs::is_regular_file(backup / "user" / "default" / "printer" / "Existing NIA.json"));
    CHECK_FALSE(fs::exists(state));
}

TEST_CASE("NIA settings recovery rejects an unmarked partial destination and restores the backup", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261104-010207";
    const fs::path backup = tree.root / "target.backup-20261104-010207";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(target / "partial.txt", "incomplete");
    write_file(staging / "nia-settings-migration-test.txt", "complete");
    write_file(backup / "existing.txt", "original");
    write_recovery_state(state, target, staging, backup, true);

    const RecoveryResult result = recover_interrupted(target.string());

    INFO(result.error);
    REQUIRE(result.recovered);
    CHECK(fs::is_regular_file(target / "existing.txt"));
    CHECK_FALSE(fs::exists(target / "partial.txt"));
    CHECK_FALSE(fs::exists(staging));
    CHECK_FALSE(fs::exists(backup));
    CHECK_FALSE(fs::exists(state));
}

TEST_CASE("NIA settings recovery fails closed when an expected backup is missing", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261104-010206";
    const fs::path backup = tree.root / "target.backup-20261104-010206";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(staging / "nozzle-it-all-desktop.conf", R"({"app":{"language":"en"}})");
    write_file(state, nlohmann::json({
        {"schema", 4},
        {"destination", fs::absolute(target).lexically_normal().string()},
        {"staging", fs::absolute(staging).lexically_normal().string()},
        {"backup", fs::absolute(backup).lexically_normal().string()},
        {"destination_existed", true},
        {"completion_marker", "nia-settings-migration-test.txt"},
        {"staging_tree_sha256", tree_sha256(staging)},
        {"destination_tree_sha256", std::string(64, '0')}
    }).dump());

    const RecoveryResult result = recover_interrupted(target.string());

    CHECK_FALSE(result.recovered);
    CHECK(result.error.find("backup required for recovery is missing") != std::string::npos);
    CHECK(fs::is_directory(staging));
    CHECK(fs::is_regular_file(state));
}

TEST_CASE("NIA settings recovery resumes safely before the backup rename", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261105-010206";
    const fs::path backup = tree.root / "target.backup-20261105-010206";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(target / "original.txt", "original");
    write_file(staging / "nia-settings-migration-test.txt", "complete");
    write_file(staging / "replacement.txt", "replacement");
    write_recovery_state(state, target, staging, backup, true);

    const RecoveryResult result = recover_interrupted(target.string());

    INFO(result.error);
    REQUIRE(result.recovered);
    CHECK(result.error.empty());
    CHECK(fs::is_regular_file(target / "replacement.txt"));
    CHECK_FALSE(fs::exists(target / "original.txt"));
    CHECK(fs::is_regular_file(backup / "original.txt"));
    CHECK_FALSE(fs::exists(staging));
    CHECK_FALSE(fs::exists(state));
}

TEST_CASE("NIA settings recovery rejects a tampered staging tree", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261105-010207";
    const fs::path backup = tree.root / "target.backup-20261105-010207";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(target / "original.txt", "original");
    write_file(staging / "nia-settings-migration-test.txt", "complete");
    write_file(staging / "replacement.txt", "replacement");
    write_recovery_state(state, target, staging, backup, true);
    write_file(staging / "replacement.txt", "tampered");

    const RecoveryResult result = recover_interrupted(target.string());

    CHECK_FALSE(result.recovered);
    CHECK(result.error.find("backup required for recovery is missing") != std::string::npos);
    CHECK(fs::is_regular_file(target / "original.txt"));
    CHECK(read_file(staging / "replacement.txt") == "tampered");
    CHECK_FALSE(fs::exists(backup));
    CHECK(fs::is_regular_file(state));
}

TEST_CASE("NIA settings recovery rejects an unauthenticated legacy state", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261105-010208";
    const fs::path backup = tree.root / "target.backup-20261105-010208";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(target / "original.txt", "original");
    write_file(staging / "nia-settings-migration-test.txt", "complete");
    write_file(staging / "replacement.txt", "replacement");
    write_file(state, nlohmann::json({
        {"schema", 3},
        {"destination", fs::absolute(target).lexically_normal().string()},
        {"staging", fs::absolute(staging).lexically_normal().string()},
        {"backup", fs::absolute(backup).lexically_normal().string()},
        {"destination_existed", true},
        {"completion_marker", "nia-settings-migration-test.txt"},
        {"completion_marker_sha1", "0000000000000000000000000000000000000000"}
    }).dump());

    const RecoveryResult result = recover_interrupted(target.string());

    CHECK_FALSE(result.recovered);
    CHECK(result.error.find("Legacy migration recovery state cannot be authenticated") != std::string::npos);
    CHECK(fs::is_regular_file(target / "original.txt"));
    CHECK(fs::is_regular_file(staging / "replacement.txt"));
    CHECK(fs::is_regular_file(state));
}

TEST_CASE("NIA settings migration removes an unusable temporary recovery state", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    const fs::path temporary_state = tree.root / "target.migration-state.json.tmp";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    fs::create_directories(temporary_state);
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};

    const Result result = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);

    CHECK_FALSE(result.success);
    CHECK_FALSE(result.error.empty());
    CHECK_FALSE(fs::exists(temporary_state));
    CHECK(fs::is_regular_file(source / "OrcaSlicer.conf"));
}

TEST_CASE("NIA settings migration preserves an activated target and its backup during recovery", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path target = tree.root / "target";
    const fs::path staging = tree.root / "target.migration-20261104-010204";
    const fs::path backup = tree.root / "target.backup-20261104-010204";
    const fs::path state = tree.root / "target.migration-state.json";
    write_file(target / "nozzle-it-all-desktop.conf", R"({"app":{"language":"en"}})");
    write_file(target / "nia-settings-migration-test.txt", "complete");
    write_file(backup / "user" / "default" / "printer" / "Existing NIA.json", "existing");
    write_recovery_state(state, target, staging, backup, true);

    const RecoveryResult result = recover_interrupted(target.string());

    INFO(result.error);
    REQUIRE(result.recovered);
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    CHECK(fs::is_regular_file(backup / "user" / "default" / "printer" / "Existing NIA.json"));
    CHECK_FALSE(fs::exists(state));
}

TEST_CASE("NIA settings migration serializes concurrent first launches", "[nia_settings_migration]")
{
    TempTree tree;
    const fs::path source = tree.root / "source";
    const fs::path target = tree.root / "target";
    write_file(source / "OrcaSlicer.conf", R"({"app":{"language":"en"}})");
    const Candidate candidate{{SourceKind::OrcaSlicer, "OrcaSlicer", source.string(), {"OrcaSlicer.conf"}},
                              "OrcaSlicer.conf"};
    std::atomic<int> ready{0};
    std::atomic<bool> start{false};
    Result results[2];
    auto migrate = [&](int index) {
        ++ready;
        while (!start.load())
            std::this_thread::yield();
        results[index] = copy_candidate(candidate, target.string(), "nozzle-it-all-desktop.conf", true);
    };
    std::thread first(migrate, 0);
    std::thread second(migrate, 1);
    while (ready.load() != 2)
        std::this_thread::yield();
    start = true;
    first.join();
    second.join();

    const int successes = static_cast<int>(results[0].success) + static_cast<int>(results[1].success);
    CHECK(successes == 1);
    CHECK(fs::is_regular_file(target / "nozzle-it-all-desktop.conf"));
    const Result &loser = results[0].success ? results[1] : results[0];
    CHECK((loser.error.find("migration was not repeated") != std::string::npos ||
           loser.error.find("Another Nozzle It All Desktop instance is migrating settings") != std::string::npos));
}
