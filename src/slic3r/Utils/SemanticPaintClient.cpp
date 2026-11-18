#include "SemanticPaintClient.hpp"

#include "Http.hpp"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/SemanticPaint.hpp"

#include <chrono>
#include <cstdlib>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <system_error>
#include <thread>

#include <boost/algorithm/string/predicate.hpp>
#include <boost/algorithm/string/replace.hpp>
#include <boost/algorithm/string/trim.hpp>
#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>
#include <boost/process.hpp>
#include <nlohmann/json.hpp>
#include <wx/secretstore.h>
#include <wx/string.h>

namespace fs      = boost::filesystem;
namespace process = boost::process;

namespace Slic3r {

const char *const k_semantic_paint_claude_code_command = "claude -p --output-format json --allowedTools Read --add-dir {dir} --no-session-persistence";

namespace {

constexpr int kTimeoutSeconds = 300;

std::string read_file(const fs::path &p)
{
    std::ifstream in(p.string(), std::ios::binary);
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

struct TempDir
{
    fs::path path;
    TempDir() : path(fs::temp_directory_path() / fs::unique_path("semantic-paint-%%%%-%%%%-%%%%")) { fs::create_directories(path); }
    ~TempDir()
    {
        boost::system::error_code ec;
        fs::remove_all(path, ec);
    }
};

std::string quote(const std::string &s)
{
#ifdef _WIN32
    return "\"" + s + "\"";
#else
    std::string out = "'";
    for (char c : s)
        out += c == '\'' ? std::string("'\\''") : std::string(1, c);
    return out + "'";
#endif
}

SemanticPaintReply run_command(const SemanticPaintSettings &s, const std::string &prompt, const std::vector<std::string> &png_files,
                               const std::atomic<bool> *cancel)
{
    SemanticPaintReply out;
    if (s.command.find_first_not_of(" \t") == std::string::npos) {
        out.error = "No command set.";
        return out;
    }
    TempDir tmp;
    std::vector<std::string> paths;
    for (size_t i = 0; i < png_files.size(); ++i) {
        const fs::path p = tmp.path / ("view" + std::to_string(i + 1) + ".png");
        std::ofstream(p.string(), std::ios::binary).write(png_files[i].data(), std::streamsize(png_files[i].size()));
        paths.push_back(p.string());
    }
    const fs::path in_file = tmp.path / "prompt.txt", out_file = tmp.path / "reply.txt", err_file = tmp.path / "err.txt";
    {
        std::ofstream in(in_file.string(), std::ios::binary);
        in << SemanticPaint::command_prompt(prompt, paths);
    }

    std::string cmd = s.command;
    // A GUI app often starts without the user's PATH additions; fall back to the usual Claude Code install folders.
    if (cmd.compare(0, 7, "claude ") == 0 && process::search_path("claude").empty()) {
        const std::string found = semantic_paint_find_claude();
        if (!found.empty())
            cmd = quote(found) + cmd.substr(6);
    }
    boost::replace_all(cmd, "{dir}", quote(tmp.path.string()));
    boost::replace_all(cmd, "{model}", s.model);
    if (!s.model.empty() && s.command.find("{model}") == std::string::npos && cmd.compare(0, 7, "claude ") == 0)
        cmd += " --model " + quote(s.model);

    try {
        // The command runs in a group of its own: the shell usually starts the real program as a child of its own, and
        // stopping only the shell would leave that program running, and asking its service, with nobody to read the answer.
        process::group group;
        const auto     stop = [&group](process::child &child) {
            std::error_code ec;
            group.terminate(ec);
            child.wait(ec);
        };
#ifdef _WIN32
        const char *comspec = std::getenv("COMSPEC");
        const std::string shell = comspec ? comspec : "cmd.exe";
        process::child child(shell, process::args({"/c", cmd}), process::std_in < in_file, process::std_out > out_file,
                             process::std_err > err_file, process::start_dir(tmp.path.string()), group);
#else
        process::child child(process::search_path("sh"), process::args({"-c", cmd}), process::std_in < in_file, process::std_out > out_file,
                             process::std_err > err_file, process::start_dir(tmp.path.string()), group);
#endif
        const auto start = std::chrono::steady_clock::now();
        while (child.running()) {
            if (cancel && cancel->load()) {
                stop(child);
                out.error = "Cancelled.";
                return out;
            }
            if (std::chrono::steady_clock::now() - start > std::chrono::seconds(kTimeoutSeconds)) {
                stop(child);
                out.error = "The command did not answer in time.";
                return out;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        child.wait();
        group.detach();
        const std::string body = read_file(out_file);
        out.text = SemanticPaint::command_reply_text(body);
        if (child.exit_code() != 0 || out.text.find_first_not_of(" \t\r\n") == std::string::npos) {
            // What the command printed is not shown: it is not ours, and it can repeat a key or private data.
            out.error = "The command returned no answer (exit " + std::to_string(child.exit_code()) + ").";
            out.text.clear();
            return out;
        }
        out.ok = true;
    } catch (const std::exception &e) {
        out.error = std::string("Could not run the command: ") + e.what();
    }
    return out;
}

SemanticPaintReply run_openai(const SemanticPaintSettings &s, const std::string &prompt, const std::vector<std::string> &png_files)
{
    SemanticPaintReply out;
    if (s.model.empty()) {
        out.error = "No model set.";
        return out;
    }
    std::vector<std::string> b64;
    for (const std::string &p : png_files)
        b64.push_back(SemanticPaint::base64_encode(p));
    const std::string base = semantic_paint_tidy_endpoint(s.endpoint);

    Http http = Http::post(base + "/chat/completions");
    http.header("Content-Type", "application/json").timeout_connect(15).timeout_max(kTimeoutSeconds);
    if (!s.api_key.empty())
        http.header("Authorization", "Bearer " + s.api_key);
    bool        failed = false;
    std::string reply;
    http.set_post_body(SemanticPaint::openai_request_json(s.model, prompt, b64))
        .on_complete([&](std::string resp, unsigned) { reply = std::move(resp); })
        .on_error([&](std::string resp, std::string err, unsigned status) {
            failed    = true;
            // The server's own error text is not shown: it is not ours, and it can repeat the key or private data.
            (void) resp;
            out.error = status != 0 ? "The server answered HTTP " + std::to_string(status) + "." : "The server could not be reached: " + err;
        })
        .perform_sync();
    if (failed)
        return out;
    out.text = SemanticPaint::openai_reply_text(reply);
    if (out.text.empty()) {
        out.error = "The server returned no text.";
        return out;
    }
    out.ok = true;
    return out;
}

constexpr const char *kSecretService = "OrcaSlicer/SemanticPaint";
constexpr const char *kSecretUser    = "openai-compatible-api-key";

} // namespace

std::string semantic_paint_find_claude()
{
    std::vector<fs::path> candidates;
    if (const char *home = std::getenv("HOME")) {
        candidates.push_back(fs::path(home) / ".local/bin/claude");
        candidates.push_back(fs::path(home) / ".claude/local/claude");
        candidates.push_back(fs::path(home) / ".npm-global/bin/claude");
    }
    candidates.push_back("/usr/local/bin/claude");
    candidates.push_back("/opt/homebrew/bin/claude");
    candidates.push_back("/usr/bin/claude");
    boost::system::error_code ec;
    for (const fs::path &c : candidates)
        if (fs::exists(c, ec))
            return c.string();
    return {};
}

std::string semantic_paint_not_ready(const SemanticPaintSettings &s)
{
    const auto blank = [](const std::string &t) { return t.find_first_not_of(" \t") == std::string::npos; };
    if (s.provider == "command")
        return blank(s.command) ? "No command set." : "";
    if (s.provider == "openai")
        return blank(s.endpoint) ? "No server address set." : blank(s.model) ? "No model set." :
               !blank(s.api_key) && !boost::istarts_with(boost::trim_left_copy(s.endpoint), "https://") ? "A server that takes an API key must use HTTPS." : "";
    return s.provider.empty() ? "No provider chosen." : "Unknown provider: " + s.provider;
}

std::string semantic_paint_recipient(const SemanticPaintSettings &s)
{
    if (s.provider == "openai")
        return s.endpoint;
    if (s.provider != "command")
        return {};
    const size_t from = s.command.find_first_not_of(" \t");
    if (from == std::string::npos)
        return {};
    const size_t to = s.command.find_first_of(" \t", from);
    return s.command.substr(from, to == std::string::npos ? std::string::npos : to - from);
}

SemanticPaintReply semantic_paint_query(const SemanticPaintSettings &settings, const std::string &prompt,
                                        const std::vector<std::string> &png_files, const std::atomic<bool> *cancel)
{
    if (const std::string why = semantic_paint_not_ready(settings); !why.empty()) {
        SemanticPaintReply r;
        r.error = why;
        return r;
    }
    if (settings.provider == "command")
        return run_command(settings, prompt, png_files, cancel);
    if (settings.provider == "openai")
        return run_openai(settings, prompt, png_files);
    SemanticPaintReply r;
    r.error = "Unknown provider: " + settings.provider;
    return r;
}

std::string semantic_paint_tidy_endpoint(std::string endpoint)
{
    boost::trim(endpoint);
    const auto drop_slashes = [&endpoint] {
        while (!endpoint.empty() && endpoint.back() == '/')
            endpoint.pop_back();
    };
    drop_slashes();
    if (boost::iends_with(endpoint, "/chat/completions"))
        endpoint.erase(endpoint.size() - std::string("/chat/completions").size());
    drop_slashes();
    return endpoint;
}

std::vector<std::string> semantic_paint_parse_models(const std::string &body)
{
    std::vector<std::string> out;
    const nlohmann::json     j = nlohmann::json::parse(body, nullptr, false);
    if (!j.is_object() || !j.contains("data") || !j["data"].is_array())
        return out;
    for (const nlohmann::json &item : j["data"])
        if (item.is_object() && item.contains("id") && item["id"].is_string())
            out.push_back(item["id"].get<std::string>());
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}

SemanticPaintCheck semantic_paint_check(const SemanticPaintSettings &s)
{
    SemanticPaintCheck out;
    if (s.provider == "command") {
        const std::string program = semantic_paint_recipient(s);
        if (program.empty()) {
            out.error = "No command set.";
            return out;
        }
        boost::system::error_code ec;
        fs::path                  found = fs::path(program).has_parent_path() && fs::exists(program, ec) ? fs::path(program) :
                                                                                                           boost::process::search_path(program);
        if (found.empty() && program == "claude")
            found = semantic_paint_find_claude();
        if (found.empty()) {
            out.error = "The program was not found.";
            return out;
        }
        out.ok    = true;
        out.found = found.string();
        return out;
    }
    if (s.provider != "openai") {
        out.error = semantic_paint_not_ready(s);
        return out;
    }
    const std::string base = semantic_paint_tidy_endpoint(s.endpoint);
    if (base.empty()) {
        out.error = "No server address set.";
        return out;
    }
    if (!s.api_key.empty() && !boost::istarts_with(base, "https://")) {
        out.error = "A server that takes an API key must use HTTPS.";
        return out;
    }
    Http http = Http::get(base + "/models");
    http.timeout_connect(10).timeout_max(20);
    if (!s.api_key.empty())
        http.header("Authorization", "Bearer " + s.api_key);
    std::string body;
    http.on_complete([&](std::string resp, unsigned) { body = std::move(resp); })
        .on_error([&](std::string, std::string err, unsigned status) {
            // The server's own error text is not shown: it can repeat the key.
            out.error = status == 401 || status == 403 ? "The server refused the API key." :
                        status == 404                  ? "The server answered, but it has no model list at this address." :
                        status != 0                    ? "The server answered HTTP " + std::to_string(status) + "." :
                                                         "The server could not be reached: " + err;
        })
        .perform_sync();
    if (!out.error.empty())
        return out;
    out.models = semantic_paint_parse_models(body);
    if (out.models.empty()) {
        out.error = "The server answered, but not with a model list.";
        return out;
    }
    out.ok = true;
    return out;
}

std::string semantic_paint_stored_model(const AppConfig &config, const std::string &provider)
{
    return provider == "command" ? config.get("semantic_paint", "command_model") : provider == "openai" ? config.get("semantic_paint", "model") : std::string();
}

SemanticPaintSettings semantic_paint_load_settings(const AppConfig &config)
{
    SemanticPaintSettings s;
    s.provider = config.get("semantic_paint", "provider");
    s.command  = config.get("semantic_paint", "command");
    s.endpoint = config.get("semantic_paint", "endpoint");
    if (s.provider != "command" && s.provider != "openai")
        s.provider.clear();
    s.model = semantic_paint_stored_model(config, s.provider);
    if (s.provider == "openai")
        s.api_key = semantic_paint_load_api_key();
    return s;
}

void semantic_paint_save_settings(AppConfig &config, const SemanticPaintSettings &s)
{
    config.set("semantic_paint", "provider", s.provider);
    config.set("semantic_paint", "command", s.command);
    config.set("semantic_paint", "endpoint", s.endpoint);
    if (s.provider == "command")
        config.set("semantic_paint", "command_model", s.model);
    else if (s.provider == "openai")
        config.set("semantic_paint", "model", s.model);
}

std::string semantic_paint_load_api_key()
{
    wxSecretStore store = wxSecretStore::GetDefault();
    if (!store.IsOk())
        return {};
    wxString      user;
    wxSecretValue value;
    if (!store.Load(kSecretService, user, value))
        return {};
    return std::string(value.GetAsString().utf8_str());
}

bool semantic_paint_save_api_key(const std::string &key)
{
    wxSecretStore store = wxSecretStore::GetDefault();
    if (!store.IsOk())
        return false;
    if (key.empty())
        return store.Delete(kSecretService);
    return store.Save(kSecretService, kSecretUser, wxSecretValue(wxString::FromUTF8(key.c_str())));
}

} // namespace Slic3r
