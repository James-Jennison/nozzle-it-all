#include "NIASettingsMigration.hpp"
#include "InstanceLock.hpp"

#include <boost/filesystem/directory.hpp>
#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>
#include <openssl/evp.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <ctime>
#include <exception>
#include <iomanip>
#include <ios>
#include <istream>
#include <memory>
#include <set>
#include <stdexcept>
#include <sstream>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace fs = boost::filesystem;

namespace Slic3r::NIASettingsMigration {
namespace {

std::string trim_ascii(std::string value)
{
    const auto first = std::find_if_not(value.begin(), value.end(), [](unsigned char ch) { return std::isspace(ch); });
    const auto last = std::find_if_not(value.rbegin(), value.rend(), [](unsigned char ch) { return std::isspace(ch); }).base();
    return first < last ? std::string(first, last) : std::string();
}

std::string normalized_identifier(std::string value)
{
    value = trim_ascii(std::move(value));
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

std::string compact_identifier(const std::string &value)
{
    std::string compact;
    for (unsigned char ch : normalized_identifier(value))
        if (std::isalnum(ch))
            compact += static_cast<char>(ch);
    return compact;
}

std::string percent_decode_identifier(const std::string &value)
{
    auto hex = [](unsigned char ch) -> int {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        return -1;
    };
    std::string decoded;
    decoded.reserve(value.size());
    for (size_t i = 0; i < value.size(); ++i) {
        if (value[i] == '%' && i + 2 < value.size()) {
            const int high = hex(static_cast<unsigned char>(value[i + 1]));
            const int low  = hex(static_cast<unsigned char>(value[i + 2]));
            if (high >= 0 && low >= 0) {
                decoded += static_cast<char>((high << 4) | low);
                i += 2;
                continue;
            }
        }
        decoded += value[i] == '+' ? ' ' : value[i];
    }
    return decoded;
}

bool secret_identifier(const std::string &name)
{
    static const std::vector<std::string> secret_keys = {
        "access_code", "user_access_code", "password", "token", "access_token", "refresh_token",
        "api_key", "apikey", "printhost_apikey", "printhost_password", "printhost_user", "client_secret",
        "secret", "authorization", "cookie", "cookies", "credential", "credentials", "tokens",
        "auth", "auth_blob", "http_auth_blob", "bearer", "bearer_token", "private_key", "client_key", "passphrase",
        "passwd", "pwd", "pass", "oauth", "oauth_token", "oauth_secret", "secret_key", "api_secret",
        "session_token", "session_secret"
    };
    const std::string normalized = normalized_identifier(name);
    if (std::find(secret_keys.begin(), secret_keys.end(), normalized) != secret_keys.end())
        return true;
    static const std::vector<std::string> secret_suffixes = {
        "_access_code", "_password", "_token", "_secret", "_api_key", "_apikey", "_credential",
        "_credentials", "_auth", "_auth_blob", "_cookie", "_session_secret", "_secret_key", "_api_secret",
        "_passwd", "_pwd", "_pass", "_oauth"
    };
    if (std::any_of(secret_suffixes.begin(), secret_suffixes.end(), [&](const std::string &suffix) {
        return normalized.size() >= suffix.size() &&
               normalized.compare(normalized.size() - suffix.size(), suffix.size(), suffix) == 0;
    }))
        return true;
    const std::string compact = compact_identifier(name);
    static const std::vector<std::string> compact_secret_keys = {
        "accesscode", "useraccesscode", "password", "token", "accesstoken", "refreshtoken", "apikey",
        "printhostapikey", "printhostpassword", "printhostuser", "clientsecret", "secret", "authorization",
        "cookie", "cookies", "credential", "credentials", "tokens", "auth", "authblob", "httpauthblob",
        "bearer", "bearertoken", "privatekey", "clientkey", "passphrase", "passwd", "pwd", "pass", "oauth",
        "oauthtoken", "oauthsecret", "secretkey", "apisecret", "sessiontoken", "sessionsecret"
    };
    if (std::find(compact_secret_keys.begin(), compact_secret_keys.end(), compact) != compact_secret_keys.end())
        return true;
    static const std::vector<std::string> compact_secret_suffixes = {
        "accesscode", "password", "token", "secret", "apikey", "credential", "credentials", "authblob",
        "cookie", "sessionsecret", "secretkey", "apisecret", "passwd", "oauth"
    };
    return std::any_of(compact_secret_suffixes.begin(), compact_secret_suffixes.end(), [&](const std::string &suffix) {
        return compact.size() > suffix.size() && compact.compare(compact.size() - suffix.size(), suffix.size(), suffix) == 0;
    });
}

bool excluded_component(const std::string &name)
{
    static const std::vector<std::string> excluded = {
        "cache", "log", "logs", "ota", "updater", "updates", "webview", "webview2",
        "browser", "cookies", "gpucache", "code cache", "service worker"
    };
    const std::string normalized = normalized_identifier(name);
    return std::find(excluded.begin(), excluded.end(), normalized) != excluded.end();
}

bool excluded_file(const fs::path &path)
{
    const std::string name = normalized_identifier(path.filename().string());
    const std::string extension = normalized_identifier(path.extension().string());
    return name == "orca_refresh_token.sec" || name == "orca_refresh_token.sec.tmp" ||
           name == "lockfile" || extension == ".lock" || extension == ".bak";
}

bool looks_like_secret_value(const std::string &raw)
{
    const std::string value = trim_ascii(raw);
    const std::string lowered = normalized_identifier(value);
    if (lowered.rfind("basic ", 0) == 0) {
        const std::string encoded = value.substr(6);
        const size_t padding = encoded.size() >= 2 && encoded.compare(encoded.size() - 2, 2, "==") == 0
                                   ? 2u
                                   : !encoded.empty() && encoded.back() == '=' ? 1u : 0u;
        const bool basic_token = encoded.size() >= 4 && encoded.size() % 4 == 0 &&
            std::all_of(encoded.begin(), encoded.end() - static_cast<std::ptrdiff_t>(padding), [](unsigned char c) {
                return std::isalnum(c) != 0 || c == '+' || c == '/';
            }) &&
            std::all_of(encoded.end() - static_cast<std::ptrdiff_t>(padding), encoded.end(),
                        [](unsigned char c) { return c == '='; });
        if (basic_token) {
            std::vector<unsigned char> decoded(encoded.size() / 4 * 3);
            int decoded_size = EVP_DecodeBlock(decoded.data(),
                                               reinterpret_cast<const unsigned char *>(encoded.data()),
                                               static_cast<int>(encoded.size()));
            decoded_size -= static_cast<int>(padding);
            if (decoded_size > 0 &&
                std::find(decoded.begin(), decoded.begin() + decoded_size, static_cast<unsigned char>(':')) !=
                    decoded.begin() + decoded_size)
                return true;
        }
    }
    if (value.size() == 39 && value.rfind("AIza", 0) == 0 &&
        std::all_of(value.begin() + 4, value.end(), [](unsigned char c) {
            return std::isalnum(c) != 0 || c == '-' || c == '_';
        }))
        return true;
    static const std::vector<std::string> prefixes = {
        "bearer ", "aws4-hmac-sha256 ", "sk-", "ghp_", "gho_", "ghu_", "ghs_", "ghr_",
        "github_pat_", "xoxb-", "xoxp-", "ya29."
    };
    if (std::any_of(prefixes.begin(), prefixes.end(), [&](const std::string &prefix) {
            return lowered.rfind(prefix, 0) == 0 && value.size() > prefix.size() + 4;
        }))
        return true;
    if (value.find("-----BEGIN ") != std::string::npos && value.find("PRIVATE KEY-----") != std::string::npos)
        return true;

    const size_t first_dot = value.find('.');
    if (first_dot == std::string::npos)
        return false;
    const size_t second_dot = value.find('.', first_dot + 1);
    if (second_dot == std::string::npos || value.find('.', second_dot + 1) != std::string::npos)
        return false;

    // A dotted identifier is a JWT only when its first base64url segment
    // decodes to a JSON header with an algorithm. Shape alone is too broad
    // for user-authored preset names, versions, and notes.
    const std::string encoded = value.substr(0, first_dot);
    std::string decoded;
    unsigned accumulator = 0;
    int bits = 0;
    for (unsigned char ch : encoded) {
        int digit = -1;
        if (ch >= 'A' && ch <= 'Z') digit = ch - 'A';
        else if (ch >= 'a' && ch <= 'z') digit = ch - 'a' + 26;
        else if (ch >= '0' && ch <= '9') digit = ch - '0' + 52;
        else if (ch == '-') digit = 62;
        else if (ch == '_') digit = 63;
        else return false;
        accumulator = (accumulator << 6) | static_cast<unsigned>(digit);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            decoded += static_cast<char>((accumulator >> bits) & 0xffu);
        }
    }
    try {
        const nlohmann::json header = nlohmann::json::parse(decoded);
        return header.is_object() && header.contains("alg") && header["alg"].is_string() &&
               !header["alg"].get<std::string>().empty();
    } catch (const nlohmann::json::exception &) {
        return false;
    }
}

bool remove_secret_fields(nlohmann::json &value)
{
    bool removed = false;
    if (value.is_object()) {
        for (auto item = value.begin(); item != value.end();) {
            if (secret_identifier(item.key()) || (item.value().is_string() && looks_like_secret_value(item.value().get<std::string>()))) {
                item = value.erase(item);
                removed = true;
            } else {
                removed = remove_secret_fields(item.value()) || removed;
                ++item;
            }
        }
    } else if (value.is_array()) {
        for (auto item = value.begin(); item != value.end();) {
            if (item->is_string() && looks_like_secret_value(item->get<std::string>())) {
                item = value.erase(item);
                removed = true;
            } else {
                removed = remove_secret_fields(*item) || removed;
                ++item;
            }
        }
    } else if (value.is_string()) {
        std::string text = value.get<std::string>();
        const auto scheme = text.find("://");
        if (scheme == std::string::npos)
            return false;
        const auto authority_start = scheme + 3;
        const auto authority_end = text.find_first_of("/?#", authority_start);
        const auto at = text.rfind('@', authority_end == std::string::npos ? std::string::npos : authority_end - 1);
        if (at != std::string::npos && at >= authority_start) {
            text.erase(authority_start, at - authority_start + 1);
            removed = true;
        }

        const auto fragment = text.find('#', authority_start);
        if (fragment != std::string::npos) {
            text.erase(fragment);
            removed = true;
        }

        const auto query = text.find('?', authority_start);
        if (query != std::string::npos) {
            std::string sanitized;
            size_t begin = query + 1;
            while (begin <= text.size()) {
                const size_t end = text.find('&', begin);
                const std::string parameter = text.substr(begin, end == std::string::npos ? std::string::npos : end - begin);
                const size_t equals = parameter.find('=');
                const std::string key = percent_decode_identifier(parameter.substr(0, equals));
                const std::string parameter_value = equals == std::string::npos ? std::string() :
                                                    percent_decode_identifier(parameter.substr(equals + 1));
                if (!parameter.empty() && !secret_identifier(key) && !looks_like_secret_value(parameter_value)) {
                    if (!sanitized.empty())
                        sanitized += '&';
                    sanitized += parameter;
                } else if (!parameter.empty())
                    removed = true;
                if (end == std::string::npos)
                    break;
                begin = end + 1;
            }
            text.erase(query);
            if (!sanitized.empty())
                text += '?' + sanitized;
        }
        value = std::move(text);
    }
    return removed;
}

constexpr std::uintmax_t max_structured_config_size = 64u * 1024u * 1024u;

enum class JsonCopyStatus { Copied, Invalid, UnsupportedSchema, Unreadable, TooLarge };

JsonCopyStatus copy_json_sanitized(const fs::path &source, const fs::path &destination, bool &sensitive_content_removed)
{
    boost::system::error_code size_error;
    const std::uintmax_t source_size = fs::file_size(source, size_error);
    if (size_error)
        return JsonCopyStatus::Unreadable;
    if (source_size > max_structured_config_size)
        return JsonCopyStatus::TooLarge;

    boost::nowide::ifstream input(source.string());
    if (!input)
        return JsonCopyStatus::Unreadable;

    nlohmann::json config;
    try {
        input >> config;
    } catch (const nlohmann::json::exception &) {
        return JsonCopyStatus::Invalid;
    }
    if (!config.is_object())
        return JsonCopyStatus::UnsupportedSchema;
    sensitive_content_removed = remove_secret_fields(config);
    fs::create_directories(destination.parent_path());
    boost::nowide::ofstream output(destination.string());
    if (!output)
        throw std::runtime_error("Could not create the migrated configuration.");
    output << config.dump(1, '\t') << '\n';
    output.close();
    if (!output)
        throw std::runtime_error("Could not finish the migrated JSON file.");
    return JsonCopyStatus::Copied;
}

bool looks_like_json(const fs::path &source)
{
    boost::nowide::ifstream input(source.string());
    if (!input)
        throw std::runtime_error("Could not read the source configuration.");
    std::string line;
    while (std::getline(input, line)) {
        const std::string trimmed = trim_ascii(line);
        if (trimmed.empty())
            continue;
        const char ch = trimmed.front();
        if (ch == '[') {
            // A failed JSON parse whose first meaningful line is exactly an
            // INI section header must still reach the INI converter. JSON
            // arrays either parsed above or contain JSON punctuation/content
            // that prevents this narrow section-header match.
            const bool ini_section = trimmed.size() > 2 && trimmed.back() == ']' &&
                                     trimmed.find_first_of("{\",:", 1) == std::string::npos;
            return !ini_section;
        }
        return ch == '{' || ch == '"' || ch == '-' || std::isdigit(static_cast<unsigned char>(ch)) ||
               ch == 't' || ch == 'f' || ch == 'n';
    }
    return false;
}

bool has_ini_assignment(const fs::path &source)
{
    boost::nowide::ifstream input(source.string());
    if (!input)
        throw std::runtime_error("Could not read the source configuration.");
    std::string line;
    while (std::getline(input, line)) {
        const std::string trimmed = trim_ascii(line);
        if (trimmed.empty() || trimmed.front() == '#' || trimmed.front() == ';')
            continue;
        if (trimmed.front() == '{' || trimmed.front() == '"' ||
            (trimmed.front() == '[' && trimmed.back() != ']'))
            return false;
        if (trimmed.front() == '[' && trimmed.back() == ']')
            continue;
        const size_t equals = line.find('=');
        if (equals != std::string::npos && !trim_ascii(line.substr(0, equals)).empty())
            return true;
    }
    return false;
}

bool copy_config_sanitized(const fs::path &source, const fs::path &destination, size_t &malformed_lines,
                           size_t &duplicate_keys, std::string &detected_schema, std::string &failure_reason,
                           bool &sensitive_content_removed)
{
    boost::system::error_code size_error;
    const std::uintmax_t source_size = fs::file_size(source, size_error);
    if (size_error) {
        failure_reason = "unreadable";
        return false;
    }
    if (source_size > max_structured_config_size) {
        failure_reason = "file-too-large";
        return false;
    }

    // Orca-family .conf files are normally JSON, but older forks have also
    // emitted INI syntax under that extension. Detect by parsing first and
    // fall back to the INI converter rather than dropping valid settings.
    if (normalized_identifier(source.extension().string()) == ".conf") {
        const JsonCopyStatus json = copy_json_sanitized(source, destination, sensitive_content_removed);
        if (json == JsonCopyStatus::Copied) {
            detected_schema = "json-sanitized";
            return true;
        }
        if (json == JsonCopyStatus::UnsupportedSchema) {
            failure_reason = "unsupported-json-schema";
            return false;
        }
        if (json == JsonCopyStatus::Unreadable) {
            failure_reason = "unreadable";
            return false;
        }
        if (json == JsonCopyStatus::TooLarge) {
            failure_reason = "file-too-large";
            return false;
        }
        // A sectionless legacy INI often starts with keys such as
        // nozzle_diameter or temperature, both of which resemble JSON scalar
        // prefixes. Prefer the structural key=value evidence before rejecting
        // a failed JSON parse as malformed JSON.
        if (looks_like_json(source) && !has_ini_assignment(source)) {
            failure_reason = "invalid-json";
            return false;
        }
    }

    boost::nowide::ifstream input(source.string());
    if (!input) {
        failure_reason = "unreadable";
        return false;
    }

    nlohmann::json config = nlohmann::json::object();
    std::string section;
    std::string line;
    size_t assignment_count = 0;
    while (std::getline(input, line)) {
        const std::string trimmed = trim_ascii(line);
        if (trimmed.empty() || trimmed.front() == '#' || trimmed.front() == ';')
            continue;
        if (trimmed.front() == '[' && trimmed.back() == ']') {
            section = trim_ascii(trimmed.substr(1, trimmed.size() - 2));
            continue;
        }
        const auto equals = line.find('=');
        if (equals == std::string::npos) {
            ++malformed_lines;
            continue;
        }
        const std::string key = trim_ascii(line.substr(0, equals));
        if (key.empty()) {
            ++malformed_lines;
            continue;
        }
        ++assignment_count;
        const bool secret_section = secret_identifier(section);
        if (!secret_section && !secret_identifier(key)) {
            // AppConfig persists ordinary sectionless legacy settings in its
            // "app" object. An empty JSON member would load into an unused
            // empty section and falsely report a successful migration.
            const std::string output_section = section.empty() ? "app" : section;
            if (config[output_section].contains(key))
                ++duplicate_keys;
            config[output_section][key] = trim_ascii(line.substr(equals + 1));
        } else
            sensitive_content_removed = true;
    }
    if (assignment_count == 0) {
        failure_reason = "invalid-config";
        return false;
    }

    sensitive_content_removed = remove_secret_fields(config) || sensitive_content_removed;
    fs::create_directories(destination.parent_path());
    boost::nowide::ofstream output(destination.string());
    if (!output)
        throw std::runtime_error("Could not create the migrated configuration.");
    output << config.dump(1, '\t') << '\n';
    output.close();
    if (!output)
        throw std::runtime_error("Could not finish the migrated configuration.");
    detected_schema = "ini-converted-json";
    return true;
}

std::string timestamp()
{
    const auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm, &now);
#else
    localtime_r(&now, &tm);
#endif
    std::ostringstream out;
    out << std::put_time(&tm, "%Y%m%d-%H%M%S");
    return out.str();
}

void copy_tree(const fs::path &source, const fs::path &destination, const fs::path &source_root,
               const std::string &source_config, const std::string &target_config,
               std::vector<std::pair<std::string, std::string>> &skipped_files,
               std::vector<std::pair<std::string, std::string>> &sanitized_files,
               std::string &source_schema)
{
    // The source is a user-selected local Orca-family settings directory, not
    // an untrusted concurrent filesystem boundary. Entries are reclassified
    // and parsed while copied, but a hostile process mutating this directory
    // concurrently is outside the migration contract.
    fs::create_directories(destination);
    for (fs::directory_iterator it(source), end; it != end; ++it) {
        const fs::path current = it->path();
        if (fs::is_symlink(current) || excluded_component(current.filename().string()) || excluded_file(current))
            continue;

        const bool is_top_level_config = !source_config.empty() && current.parent_path() == source_root &&
                                         current.filename().string() == source_config;
        std::string output_name = current.filename().string();
        if (is_top_level_config)
            output_name = target_config;
        const fs::path output = destination / output_name;

        if (fs::is_directory(current))
            copy_tree(current, output, source_root, source_config, target_config, skipped_files, sanitized_files,
                      source_schema);
        else if (fs::is_regular_file(current)) {
            fs::create_directories(output.parent_path());
            const std::string relative = fs::relative(current, source_root).generic_string();
            if (is_top_level_config) {
                size_t malformed_lines = 0;
                size_t duplicate_keys = 0;
                std::string failure_reason;
                bool sensitive_content_removed = false;
                if (!copy_config_sanitized(current, output, malformed_lines, duplicate_keys, source_schema, failure_reason,
                                           sensitive_content_removed)) {
                    source_schema = "invalid-skipped";
                    skipped_files.emplace_back(relative, failure_reason.empty() ? "invalid-config" : failure_reason);
                } else if (malformed_lines != 0)
                    skipped_files.emplace_back(relative, "malformed-ini-lines=" + std::to_string(malformed_lines));
                if (duplicate_keys != 0)
                    skipped_files.emplace_back(relative, "duplicate-ini-keys=" + std::to_string(duplicate_keys));
                if (sensitive_content_removed)
                    sanitized_files.emplace_back(relative, "credential-content-removed");
            } else if (normalized_identifier(current.extension().string()) == ".json") {
                bool sensitive_content_removed = false;
                const JsonCopyStatus status = copy_json_sanitized(current, output, sensitive_content_removed);
                if (status != JsonCopyStatus::Copied)
                    skipped_files.emplace_back(relative,
                                               status == JsonCopyStatus::UnsupportedSchema ? "unsupported-json-schema" :
                                               status == JsonCopyStatus::Unreadable ? "unreadable" :
                                               status == JsonCopyStatus::TooLarge ? "file-too-large" : "invalid-json");
                else if (sensitive_content_removed)
                    sanitized_files.emplace_back(relative, "credential-content-removed");
            } else if (const std::string extension = normalized_identifier(current.extension().string());
                       extension == ".conf" || extension == ".ini") {
                size_t malformed_lines = 0;
                size_t duplicate_keys = 0;
                std::string ignored_schema;
                std::string failure_reason;
                bool sensitive_content_removed = false;
                if (!copy_config_sanitized(current, output, malformed_lines, duplicate_keys, ignored_schema, failure_reason,
                                           sensitive_content_removed))
                    skipped_files.emplace_back(relative, failure_reason.empty() ? "invalid-config" : failure_reason);
                else if (malformed_lines != 0)
                    skipped_files.emplace_back(relative, "malformed-ini-lines=" + std::to_string(malformed_lines));
                if (duplicate_keys != 0)
                    skipped_files.emplace_back(relative, "duplicate-ini-keys=" + std::to_string(duplicate_keys));
                if (sensitive_content_removed)
                    sanitized_files.emplace_back(relative, "credential-content-removed");
            } else if (normalized_identifier(current.extension().string()) == ".info") {
                // Orca .info sidecars contain cloud synchronization identity,
                // not the user preset itself. Crossing the product boundary
                // would incorrectly associate NIA with the source account.
                skipped_files.emplace_back(relative, "preset-cloud-sync-sidecar-excluded");
            } else {
                // Never copy opaque files across the product boundary. They
                // cannot be inspected for credentials without understanding
                // their format, and are not needed for presets/settings.
                skipped_files.emplace_back(relative, "unsupported-uninspected-file");
            }
        }
    }
}

fs::path migration_lock_path(const fs::path &destination)
{
    return destination.parent_path() / (destination.filename().string() + ".migration.lock");
}

fs::path migration_state_path(const fs::path &destination)
{
    return destination.parent_path() / (destination.filename().string() + ".migration-state.json");
}

fs::path normalized_absolute(const fs::path &path)
{
    return fs::absolute(path).lexically_normal();
}

bool valid_sibling_path(const fs::path &path, const fs::path &destination, const std::string &suffix)
{
    if (path.empty())
        return false;
    const fs::path absolute = normalized_absolute(path);
    const fs::path parent = normalized_absolute(destination.parent_path());
    const std::string prefix = destination.filename().string() + suffix;
    return absolute.parent_path() == parent && absolute.filename().string().rfind(prefix, 0) == 0;
}

class Sha256
{
public:
    Sha256() : m_context(EVP_MD_CTX_new())
    {
        if (m_context == nullptr || EVP_DigestInit_ex(m_context, EVP_sha256(), nullptr) != 1)
            throw std::runtime_error("Could not initialize the migration tree digest.");
    }
    ~Sha256() { EVP_MD_CTX_free(m_context); }

    Sha256(const Sha256 &) = delete;
    Sha256 &operator=(const Sha256 &) = delete;

    void update(const void *data, size_t size)
    {
        if (EVP_DigestUpdate(m_context, data, size) != 1)
            throw std::runtime_error("Could not update the migration tree digest.");
    }

    void field(const std::string &value)
    {
        const std::uint64_t size = value.size();
        update(&size, sizeof(size));
        update(value.data(), value.size());
    }

    std::string finish()
    {
        unsigned char digest[EVP_MAX_MD_SIZE];
        unsigned int size = 0;
        if (EVP_DigestFinal_ex(m_context, digest, &size) != 1 || size != 32)
            throw std::runtime_error("Could not finish the migration tree digest.");
        std::ostringstream output;
        output << std::hex << std::setfill('0');
        for (unsigned int i = 0; i < size; ++i)
            output << std::setw(2) << static_cast<unsigned>(digest[i]);
        return output.str();
    }

private:
    EVP_MD_CTX *m_context;
};

std::string tree_sha256(const fs::path &root)
{
    if (!fs::is_directory(root) || fs::is_symlink(fs::symlink_status(root)))
        throw std::runtime_error("Could not authenticate a migration settings tree.");

    std::vector<fs::path> entries;
    for (fs::recursive_directory_iterator it(root), end; it != end; ++it)
        entries.push_back(it->path());
    std::sort(entries.begin(), entries.end(), [&](const fs::path &left, const fs::path &right) {
        return fs::relative(left, root).generic_string() < fs::relative(right, root).generic_string();
    });

    Sha256 hash;
    for (const fs::path &entry : entries) {
        const fs::file_status status = fs::symlink_status(entry);
        if (fs::is_symlink(status))
            throw std::runtime_error("A migration settings tree contains a symbolic link.");
        const std::string relative = fs::relative(entry, root).generic_string();
        if (fs::is_directory(status)) {
            hash.field("directory");
            hash.field(relative);
            continue;
        }
        if (!fs::is_regular_file(status))
            throw std::runtime_error("A migration settings tree contains an unsupported entry.");
        hash.field("file");
        hash.field(relative);
        const std::uint64_t file_size = fs::file_size(entry);
        hash.update(&file_size, sizeof(file_size));
        boost::nowide::ifstream input(entry.string(), std::ios::binary);
        if (!input)
            throw std::runtime_error("Could not read a migration settings file for authentication.");
        char buffer[64 * 1024];
        while (input) {
            input.read(buffer, sizeof(buffer));
            const std::streamsize count = input.gcount();
            if (count > 0)
                hash.update(buffer, static_cast<size_t>(count));
        }
        if (!input.eof())
            throw std::runtime_error("Could not finish authenticating a migration settings file.");
    }
    return hash.finish();
}

void publish_durable_file(const fs::path &temporary, const fs::path &destination)
{
#ifdef _WIN32
    HANDLE file = CreateFileW(temporary.wstring().c_str(), GENERIC_READ | GENERIC_WRITE,
                              FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE)
        throw std::runtime_error("Could not open the migration recovery state for durable storage.");
    const BOOL flushed = FlushFileBuffers(file);
    CloseHandle(file);
    if (!flushed)
        throw std::runtime_error("Could not flush the migration recovery state to durable storage.");
    if (!MoveFileExW(temporary.wstring().c_str(), destination.wstring().c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
        throw std::runtime_error("Could not publish the durable migration recovery state.");
#else
    const int file = ::open(temporary.string().c_str(), O_RDONLY | O_CLOEXEC);
    if (file < 0)
        throw std::runtime_error("Could not open the migration recovery state for durable storage.");
    const int file_sync = ::fsync(file);
    const int file_sync_error = errno;
    ::close(file);
    if (file_sync != 0)
        throw std::runtime_error("Could not flush the migration recovery state to durable storage: " +
                                 std::error_code(file_sync_error, std::generic_category()).message());

    fs::rename(temporary, destination);

    // write_migration_state creates the temporary as state_path + ".tmp", so
    // source and destination always share this parent. One post-rename fsync
    // durably records both directory-entry changes.
#ifdef O_DIRECTORY
    const int directory = ::open(destination.parent_path().string().c_str(), O_RDONLY | O_CLOEXEC | O_DIRECTORY);
#else
    const int directory = ::open(destination.parent_path().string().c_str(), O_RDONLY | O_CLOEXEC);
#endif
    if (directory < 0)
        throw std::runtime_error(
            "The migration recovery state was published, but its directory durability could not be confirmed.");
    const int directory_sync = ::fsync(directory);
    const int directory_sync_error = errno;
    ::close(directory);
    if (directory_sync != 0)
        throw std::runtime_error("The migration recovery state was published, but its directory durability could not be confirmed: " +
                                 std::error_code(directory_sync_error, std::generic_category()).message());
#endif
}

void write_migration_state(const fs::path &state_path, const fs::path &destination, const fs::path &staging,
                           const fs::path &backup, bool destination_existed, const std::string &completion_marker)
{
    const std::string staging_tree_sha256 = tree_sha256(staging);
    const std::string destination_tree_sha256 = destination_existed ? tree_sha256(destination) : std::string();
    const fs::path temporary = fs::path(state_path.string() + ".tmp");
    try {
        boost::nowide::ofstream output(temporary.string(), std::ios::trunc);
        if (!output)
            throw std::runtime_error("Could not create the migration recovery state.");
        output << nlohmann::json({
            {"schema", 4},
            {"destination", normalized_absolute(destination).string()},
            {"staging", normalized_absolute(staging).string()},
            {"backup", destination_existed ? normalized_absolute(backup).string() : std::string()},
            {"destination_existed", destination_existed},
            {"completion_marker", completion_marker},
            {"staging_tree_sha256", staging_tree_sha256},
            {"destination_tree_sha256", destination_tree_sha256}
        }).dump(1, '\t') << '\n';
        output.close();
        if (!output)
            throw std::runtime_error("Could not finish the migration recovery state.");

        publish_durable_file(temporary, state_path);
    } catch (...) {
        boost::system::error_code ignored;
        fs::remove(temporary, ignored);
        throw;
    }
}

RecoveryResult recover_interrupted_locked(const fs::path &destination)
{
    RecoveryResult result;
    const fs::path state_path = migration_state_path(destination);
    if (!fs::exists(state_path))
        return result;

    try {
        if (fs::is_symlink(fs::symlink_status(state_path)) || !fs::is_regular_file(state_path))
            throw std::runtime_error("The migration recovery state is not a regular file.");
        boost::nowide::ifstream input(state_path.string());
        nlohmann::json state;
        if (!input)
            throw std::runtime_error("Could not read the migration recovery state.");
        input >> state;
        const int schema = state.value("schema", 0);
        if (schema != 2 && schema != 3 && schema != 4)
            throw std::runtime_error("The migration recovery state uses an unsupported schema.");
        if (schema != 4)
            throw std::runtime_error(
                "Legacy migration recovery state cannot be authenticated automatically; all settings trees were retained.");

        const fs::path recorded_destination(state.at("destination").get<std::string>());
        const fs::path staging(state.at("staging").get<std::string>());
        const fs::path backup(state.value("backup", std::string()));
        const bool destination_existed = state.value("destination_existed", false);
        const fs::path completion_marker(state.at("completion_marker").get<std::string>());
        const std::string staging_tree_sha256 = state.value("staging_tree_sha256", std::string());
        const std::string destination_tree_sha256 = state.value("destination_tree_sha256", std::string());
        if (normalized_absolute(recorded_destination) != normalized_absolute(destination) ||
            !valid_sibling_path(staging, destination, ".migration-") ||
            (destination_existed && !valid_sibling_path(backup, destination, ".backup-")) ||
            completion_marker.empty() || completion_marker.has_parent_path() || completion_marker.filename() != completion_marker ||
            staging_tree_sha256.size() != 64 ||
            (destination_existed ? destination_tree_sha256.size() != 64 : !destination_tree_sha256.empty()))
            throw std::runtime_error("The migration recovery state contains an invalid path.");

        for (const fs::path &path : {destination, staging, backup}) {
            if (path.empty() || !fs::exists(path))
                continue;
            const fs::file_status status = fs::symlink_status(path);
            if (fs::is_symlink(status) || !fs::is_directory(status))
                throw std::runtime_error("The migration recovery state refers to a non-directory or symbolic link.");
        }

        boost::system::error_code error;
        auto tree_matches = [&](const fs::path &directory, const std::string &expected) {
            if (directory.empty() || !fs::is_directory(directory) || fs::is_symlink(fs::symlink_status(directory)))
                return false;
            return tree_sha256(directory) == expected;
        };
        const bool destination_complete = tree_matches(destination, staging_tree_sha256);
        const bool staging_complete     = tree_matches(staging, staging_tree_sha256);
        if (destination_complete) {
            fs::remove_all(staging, error);
            if (error)
                throw std::runtime_error("Could not remove stale migration staging data: " + error.message());
            result.recovery_path = destination.string();
        } else if (destination_existed && !fs::exists(destination) && fs::exists(backup) && staging_complete) {
            if (!tree_matches(backup, destination_tree_sha256))
                throw std::runtime_error("The previous settings backup does not match the authenticated recovery state.");
            fs::rename(staging, destination, error);
            if (error)
                throw std::runtime_error("Could not activate the authenticated staged settings during recovery: " + error.message());
            result.recovery_path = destination.string();
        } else if (destination_existed && fs::exists(backup)) {
            if (!tree_matches(backup, destination_tree_sha256))
                throw std::runtime_error("The previous settings backup does not match the authenticated recovery state.");
            fs::remove_all(destination, error);
            if (error)
                throw std::runtime_error("Could not remove an incomplete migrated settings directory: " + error.message());
            fs::rename(backup, destination, error);
            if (error)
                throw std::runtime_error("Could not restore the previous settings backup: " + error.message());
            fs::remove_all(staging, error);
            if (error)
                throw std::runtime_error("Could not remove interrupted migration staging data: " + error.message());
            result.recovery_path = destination.string();
        } else if (destination_existed && fs::exists(destination) && staging_complete) {
            if (!tree_matches(destination, destination_tree_sha256))
                throw std::runtime_error(
                    "Migration stopped before backup and the original settings no longer match the authenticated recovery state; all trees were retained.");
            fs::rename(destination, backup, error);
            if (error)
                throw std::runtime_error("Could not create the authenticated settings backup during recovery: " + error.message());
            fs::rename(staging, destination, error);
            if (error)
                throw std::runtime_error("Could not activate the authenticated staged settings during recovery: " + error.message());
            result.recovery_path = destination.string();
        } else if (destination_existed) {
            throw std::runtime_error("The previous settings backup required for recovery is missing.");
        } else if (staging_complete) {
            fs::remove_all(destination, error);
            if (error)
                throw std::runtime_error("Could not remove an incomplete migrated settings directory: " + error.message());
            fs::rename(staging, destination, error);
            if (error)
                throw std::runtime_error("Could not activate the complete staged settings: " + error.message());
            result.recovery_path = destination.string();
        } else {
            throw std::runtime_error("No complete migrated settings or previous backup is available for recovery.");
        }
        fs::remove(state_path, error);
        if (error)
            throw std::runtime_error("Could not clear the migration recovery state: " + error.message());
        result.recovered = true;
    } catch (const std::exception &e) {
        result.error = e.what();
    }
    return result;
}

} // namespace

bool is_meaningful_data_dir(const std::string &path)
{
    const fs::path root(path);
    if (!fs::is_directory(root))
        return false;
    for (fs::directory_iterator it(root), end; it != end; ++it) {
        const std::string name = it->path().filename().string();
        const std::string normalized = normalized_identifier(name);
        const bool migration_report = normalized.rfind("nia-settings-migration-", 0) == 0 &&
                                      it->path().extension() == ".txt";
        if (normalized == "log" || normalized == "logs" || normalized == "cache" ||
            normalized == ".ds_store" || normalized == "desktop.ini" || normalized == "thumbs.db" ||
            normalized == "nia-settings-import-declined" || normalized == "nia-settings-import-requested" ||
            migration_report)
            continue;
        return true;
    }
    return false;
}

bool import_declined(const std::string &target)
{
    return fs::is_regular_file(fs::path(target) / "nia-settings-import-declined");
}

std::string record_import_declined(const std::string &target)
{
    try {
        const fs::path destination(target);
        fs::create_directories(destination);
        boost::nowide::ofstream marker((destination / "nia-settings-import-declined").string(), std::ios::trunc);
        marker << "The user declined the optional settings import.\n";
        if (!marker.good())
            return "Could not record the settings import choice.";
    } catch (const std::exception &e) {
        return e.what();
    }
    return {};
}

bool import_requested(const std::string &target)
{
    return fs::is_regular_file(fs::path(target) / "nia-settings-import-requested");
}

std::string record_import_requested(const std::string &target)
{
    try {
        const fs::path destination(target);
        fs::create_directories(destination);
        boost::nowide::ofstream marker((destination / "nia-settings-import-requested").string(), std::ios::trunc);
        marker << "The user requested settings import on the next launch.\n";
        if (!marker.good())
            return "Could not schedule the settings import.";
    } catch (const std::exception &e) {
        return e.what();
    }
    return {};
}

std::string clear_import_requested(const std::string &target)
{
    try {
        boost::system::error_code error;
        fs::remove(fs::path(target) / "nia-settings-import-requested", error);
        return error ? error.message() : std::string();
    } catch (const std::exception &e) {
        return e.what();
    }
}

std::vector<Candidate> discover(const std::vector<SourceSpec> &ordered_sources, const std::string &target,
                                bool allow_existing_target)
{
    if (!allow_existing_target && (is_meaningful_data_dir(target) || import_declined(target)))
        return {};

    std::vector<Candidate> result;
    const fs::path target_path(target);
    boost::system::error_code target_error;
    const fs::path normalized_target = fs::canonical(target_path, target_error);
    const fs::path comparable_target = target_error ? normalized_absolute(target_path) : normalized_target;
    std::set<std::string> seen_sources;
    for (const SourceSpec &source : ordered_sources) {
        const fs::path source_path(source.path);
        boost::system::error_code source_error;
        const fs::path normalized_source = source.path.empty() ? fs::path() : fs::canonical(source_path, source_error);
        const fs::path comparable_source = source_error ? normalized_absolute(source_path) : normalized_source;
        if (source.path.empty() || comparable_source == comparable_target ||
            !seen_sources.insert(comparable_source.generic_string()).second || !is_meaningful_data_dir(source.path))
            continue;
        auto config = std::find_if(source.config_names.begin(), source.config_names.end(), [&](const std::string &name) {
            return fs::is_regular_file(source_path / name);
        });
        if (config != source.config_names.end() || fs::is_directory(source_path / "user") ||
            fs::is_directory(source_path / "presets"))
            result.push_back({source, config == source.config_names.end() ? std::string() : *config});
    }
    return result;
}

RecoveryResult recover_interrupted(const std::string &target)
{
    const fs::path destination(target);
    try {
        fs::create_directories(destination.parent_path());
        InstanceLock lock(migration_lock_path(destination).string(), std::chrono::seconds(5));
        if (!lock.locked())
            return {false, true, {}, {}};
        return recover_interrupted_locked(destination);
    } catch (const std::exception &e) {
        return {false, false, {}, e.what()};
    }
}

Result copy_candidate(const Candidate &candidate, const std::string &target, const std::string &target_config_name,
                      bool consent_granted, bool allow_partial, const Result *prepared)
{
    Result result = prepared == nullptr ? Result{} : *prepared;
    if (!consent_granted) {
        result.cancelled = true;
        return result;
    }

    const fs::path source(candidate.source.path);
    const fs::path destination(target);
    if (prepared == nullptr && !is_meaningful_data_dir(source.string())) {
        result.error = "The selected source data directory is missing or empty.";
        return result;
    }
    const bool destination_was_meaningful = is_meaningful_data_dir(destination.string());

    const std::string stamp = timestamp() + "-" + fs::unique_path("%%%%-%%%%").string();
    const fs::path staging = prepared == nullptr
                                 ? destination.parent_path() / (destination.filename().string() + ".migration-" + stamp)
                                 : fs::path(prepared->staging_path);
    const fs::path backup = prepared == nullptr || prepared->backup_path.empty()
                                ? destination.parent_path() / (destination.filename().string() + ".backup-" + stamp)
                                : fs::path(prepared->backup_path);
    const fs::path state_path = migration_state_path(destination);
    bool destination_moved = false;
    std::unique_ptr<InstanceLock> migration_lock;
    try {
        fs::create_directories(destination.parent_path());
        migration_lock = std::make_unique<InstanceLock>(migration_lock_path(destination).string(), std::chrono::seconds(5));
        if (!migration_lock->locked())
            throw std::runtime_error("Another Nozzle It All Desktop instance is migrating settings.");

        const RecoveryResult recovery = recover_interrupted_locked(destination);
        if (!recovery.error.empty())
            throw std::runtime_error("Could not recover an interrupted settings migration: " + recovery.error);
        if (!destination_was_meaningful && is_meaningful_data_dir(destination.string()))
            throw std::runtime_error("The Nozzle It All Desktop settings directory now contains data; migration was not repeated.");
        if (prepared == nullptr && fs::exists(staging))
            throw std::runtime_error("Migration staging directory already exists.");
        if (fs::exists(backup))
            throw std::runtime_error("Migration backup directory already exists.");

        const bool destination_exists = fs::exists(destination);
        if (prepared != nullptr && destination_exists != !prepared->backup_path.empty())
            throw std::runtime_error("The target settings directory changed after the partial import was reviewed.");
        if (destination_exists && prepared == nullptr)
            result.backup_path = backup.string();

        std::vector<std::pair<std::string, std::string>> skipped_files;
        std::vector<std::pair<std::string, std::string>> sanitized_files;
        std::string report_name;
        if (prepared == nullptr) {
            std::string source_schema = candidate.config_name.empty() ? "presets-only" : "unknown";
            copy_tree(source, staging, source, candidate.config_name, target_config_name, skipped_files, sanitized_files,
                      source_schema);

            report_name = "nia-settings-migration-" + stamp + ".txt";
            boost::nowide::ofstream report((staging / report_name).string());
            if (!report)
                throw std::runtime_error("Could not write the migration report.");
            report << "status=" << (skipped_files.empty() && sanitized_files.empty() ? "success" : "partial") << "\n"
                   << "source_kind=" << static_cast<int>(candidate.source.kind) << "\n"
                   << "source_path=" << candidate.source.path << "\n"
                   << "source_config=" << candidate.config_name << "\n"
                   << "source_schema=" << source_schema << "\n"
                   << "target_path=" << target << "\n"
                   << "target_config=" << target_config_name << "\n"
                   << "recovery_path=" << candidate.source.path << "\n"
                   << "target_backup_path=" << result.backup_path << "\n"
                   << "sanitizer_version=5\n"
                   << "value_pattern_scanning=true\n"
                   << "credential_field_filter=identifier-value-pattern-and-url-userinfo-query-fragment\n"
                   << "uninspected_files_copied=false\n"
                   << "copy_only=true\n"
                   << "credentials_copied=not-claimed\n"
                   << "browser_cache_copied=false\n";
            for (const auto &[path, reason] : skipped_files)
                report << "skipped_file=" << path << "\treason=" << reason << "\n";
            for (const auto &[path, reason] : sanitized_files)
                report << "sanitized_file=" << path << "\treason=" << reason << "\n";
            report.close();
            if (!report)
                throw std::runtime_error("Could not finish the migration report.");

            result.partial = !skipped_files.empty() || !sanitized_files.empty();
            for (const auto &[path, reason] : skipped_files)
                result.skipped_files.push_back(path + " (" + reason + ")");
            for (const auto &[path, reason] : sanitized_files)
                result.sanitized_files.push_back(path + " (" + reason + ")");
            result.staging_path = staging.string();
            result.report_path = (staging / report_name).string();
            if (result.partial && !allow_partial) {
                result.partial_confirmation_required = true;
                return result;
            }
        } else {
            const fs::path expected_parent = normalized_absolute(destination.parent_path());
            const fs::path normalized_staging = normalized_absolute(staging);
            const std::string expected_prefix = destination.filename().string() + ".migration-";
            if (!prepared->partial_confirmation_required || !prepared->partial || prepared->staging_path.empty() ||
                normalized_staging.parent_path() != expected_parent ||
                normalized_staging.filename().string().rfind(expected_prefix, 0) != 0 || !fs::is_directory(staging) ||
                fs::is_symlink(staging))
                throw std::runtime_error("The reviewed partial-import staging directory is unavailable or invalid.");
            const fs::path prepared_report(prepared->report_path);
            if (normalized_absolute(prepared_report.parent_path()) != normalized_staging || !fs::is_regular_file(prepared_report) ||
                fs::is_symlink(prepared_report))
                throw std::runtime_error("The reviewed partial-import report is unavailable or invalid.");
            report_name = prepared_report.filename().string();
            result.partial_confirmation_required = false;
        }

        write_migration_state(state_path, destination, staging, backup, destination_exists, report_name);
        if (destination_exists) {
            fs::rename(destination, backup);
            destination_moved = true;
        }
        fs::rename(staging, destination);
        boost::system::error_code ignored;
        fs::remove(state_path, ignored);
        result.report_path = (destination / report_name).string();
        result.staging_path.clear();
        result.success = true;
    } catch (const std::exception &e) {
        boost::system::error_code ignored;
        if (fs::exists(state_path)) {
            const RecoveryResult recovery = recover_interrupted_locked(destination);
            if (!recovery.error.empty())
                result.error = std::string(e.what()) + " Recovery also failed: " + recovery.error;
        } else {
            fs::remove_all(staging, ignored);
            if (destination_moved && !fs::exists(destination) && fs::exists(backup))
                fs::rename(backup, destination, ignored);
        }
        if (!result.error.empty())
            return result;
        result.error = e.what();
    }
    return result;
}

std::string discard_prepared_candidate(const Result &prepared, const std::string &target)
{
    if (prepared.staging_path.empty())
        return {};
    const fs::path destination(target);
    const fs::path staging(prepared.staging_path);
    const fs::path normalized_staging = normalized_absolute(staging);
    const std::string expected_prefix = destination.filename().string() + ".migration-";
    if (normalized_staging.parent_path() != normalized_absolute(destination.parent_path()) ||
        normalized_staging.filename().string().rfind(expected_prefix, 0) != 0)
        return "The prepared settings-import path was outside the expected target directory.";
    boost::system::error_code error;
    fs::remove_all(staging, error);
    return error ? "The prepared settings import could not be removed: " + error.message() : std::string{};
}

} // namespace Slic3r::NIASettingsMigration
