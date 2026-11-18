#include <catch2/catch_test_macros.hpp>

#include "slic3r/Utils/SemanticPaintClient.hpp"

#include "libslic3r/AppConfig.hpp"

#include <atomic>
#include <chrono>
#include <string>
#include <thread>
#include <vector>

using namespace Slic3r;

TEST_CASE("AI auto-paint has no provider until one is chosen", "[SemanticPaintClient]")
{
    const SemanticPaintSettings fresh;
    CHECK(fresh.provider.empty());
    CHECK(fresh.command.empty());
    CHECK(fresh.endpoint.empty());
    CHECK(semantic_paint_not_ready(fresh) == "No provider chosen.");
    CHECK(semantic_paint_recipient(fresh).empty());

    // Asking anyway sends nothing and says why.
    const SemanticPaintReply reply = semantic_paint_query(fresh, "prompt", {});
    CHECK_FALSE(reply.ok);
    CHECK(reply.error == "No provider chosen.");
}

TEST_CASE("AI auto-paint says what a chosen provider still lacks", "[SemanticPaintClient]")
{
    SemanticPaintSettings s;
    s.provider = "command";
    CHECK(semantic_paint_not_ready(s) == "No command set.");
    s.command = "  \t ";
    CHECK(semantic_paint_not_ready(s) == "No command set.");
    s.command = "ollama run llava";
    CHECK(semantic_paint_not_ready(s).empty());

    s.provider = "openai";
    CHECK(semantic_paint_not_ready(s) == "No server address set.");
    s.endpoint = "http://localhost:11434/v1";
    CHECK(semantic_paint_not_ready(s) == "No model set.");
    s.model = "llava";
    CHECK(semantic_paint_not_ready(s).empty());

    // A key is never sent in the clear.
    s.api_key = "secret";
    CHECK(semantic_paint_not_ready(s) == "A server that takes an API key must use HTTPS.");
    s.endpoint = " HTTPS://example.invalid/v1";
    CHECK(semantic_paint_not_ready(s).empty());

    s.provider = "other";
    CHECK(semantic_paint_not_ready(s) == "Unknown provider: other");
}

TEST_CASE("AI auto-paint names where the data goes", "[SemanticPaintClient]")
{
    SemanticPaintSettings s;
    s.provider = "command";
    s.command  = "  /usr/bin/llm -m gpt --attach {dir}";
    CHECK(semantic_paint_recipient(s) == "/usr/bin/llm");
    s.command = "claude";
    CHECK(semantic_paint_recipient(s) == "claude");
    s.command = "";
    CHECK(semantic_paint_recipient(s).empty());

    s.provider = "openai";
    s.endpoint = "https://example.invalid/v1";
    CHECK(semantic_paint_recipient(s) == "https://example.invalid/v1");
}

TEST_CASE("The Claude Code command line is offered, not preset", "[SemanticPaintClient]")
{
    CHECK(std::string(k_semantic_paint_claude_code_command).rfind("claude ", 0) == 0);
    CHECK(SemanticPaintSettings().command != k_semantic_paint_claude_code_command);
}

TEST_CASE("A server address is tidied without adding anything", "[SemanticPaintClient]")
{
    CHECK(semantic_paint_tidy_endpoint("  http://localhost:11434/v1/  ") == "http://localhost:11434/v1");
    CHECK(semantic_paint_tidy_endpoint("http://localhost:1234/v1/chat/completions") == "http://localhost:1234/v1");
    CHECK(semantic_paint_tidy_endpoint("https://example.test/v1beta/openai///") == "https://example.test/v1beta/openai");
    // Nothing is guessed: an address without /v1 stays without it.
    CHECK(semantic_paint_tidy_endpoint("http://localhost:8080") == "http://localhost:8080");
    CHECK(semantic_paint_tidy_endpoint("   ").empty());
}

TEST_CASE("A server's model list is read, and anything else gives none", "[SemanticPaintClient]")
{
    const std::vector<std::string> models =
        semantic_paint_parse_models(R"({"object":"list","data":[{"id":"llava"},{"id":"gemma3"},{"id":"llava"},{"name":"no id"},{"id":7}]})");
    REQUIRE(models.size() == 2);
    CHECK(models[0] == "gemma3");
    CHECK(models[1] == "llava");
    CHECK(semantic_paint_parse_models("<html>not json</html>").empty());
    CHECK(semantic_paint_parse_models(R"({"error":"unauthorized"})").empty());
    CHECK(semantic_paint_parse_models(R"({"data":"x"})").empty());
}

TEST_CASE("Testing a connection sends nothing when there is nowhere to send", "[SemanticPaintClient]")
{
    SemanticPaintSettings s;
    CHECK_FALSE(semantic_paint_check(s).ok);
    CHECK(semantic_paint_check(s).error == "No provider chosen.");

    s.provider = "command";
    CHECK(semantic_paint_check(s).error == "No command set.");
    s.command = "no-such-program-nia-test --flag";
    CHECK(semantic_paint_check(s).error == "The program was not found.");
    // A program that every system has is found, and only looked up, not run.
#ifdef _WIN32
    s.command = "cmd /c exit 9";
    const char *const program = "cmd";
#else
    s.command = "sh -c 'exit 9'";
    const char *const program = "sh";
#endif
    const SemanticPaintCheck found = semantic_paint_check(s);
    CHECK(found.ok);
    CHECK(found.found.find(program) != std::string::npos);

    s.provider = "openai";
    s.endpoint = " ";
    CHECK(semantic_paint_check(s).error == "No server address set.");
    // A key is never sent over plain HTTP, not even for a test.
    s.endpoint = "http://127.0.0.1:9/v1";
    s.api_key  = "secret";
    CHECK(semantic_paint_check(s).error == "A server that takes an API key must use HTTPS.");
}

TEST_CASE("A command and a server each keep their own model name", "[SemanticPaintClient]")
{
    AppConfig config;
    SemanticPaintSettings s;
    s.provider = "openai";
    s.endpoint = "http://localhost:11434/v1";
    s.model    = "llava";
    semantic_paint_save_settings(config, s);
    s.provider = "command";
    s.command  = "runner {model}";
    s.model    = "";
    semantic_paint_save_settings(config, s);

    // The command is chosen: it has no model of its own, and the server's is not handed to it.
    SemanticPaintSettings loaded = semantic_paint_load_settings(config);
    CHECK(loaded.provider == "command");
    CHECK(loaded.command == "runner {model}");
    CHECK(loaded.model.empty());
    CHECK(loaded.api_key.empty()); // a command never gets the server's key
    CHECK(semantic_paint_stored_model(config, "openai") == "llava");

    // An unknown provider in the file counts as nothing chosen.
    config.set("semantic_paint", "provider", "something-else");
    CHECK(semantic_paint_load_settings(config).provider.empty());
    CHECK(semantic_paint_not_ready(semantic_paint_load_settings(config)) == "No provider chosen.");
}

namespace {
// Commands every system of its kind has: one that gives back what it is given, one that says its argument, one that waits.
#ifdef _WIN32
const char *const k_gives_back = "findstr \"^\"";
const char *const k_says_model = "echo {model}";
const char *const k_waits      = "ping -n 30 127.0.0.1";
#else
const char *const k_gives_back = "cat";
const char *const k_says_model = "printf %s {model}";
const char *const k_waits      = "sleep 30";
#endif

SemanticPaintSettings command(const char *line)
{
    SemanticPaintSettings s;
    s.provider = "command";
    s.command  = line;
    return s;
}
} // namespace

TEST_CASE("A command gets the question on its input, and what it prints is the answer", "[SemanticPaintClient]")
{
    const SemanticPaintReply reply = semantic_paint_query(command(k_gives_back), "which filament for part 7?", {"stands in for a picture"});
    REQUIRE(reply.ok);
    CHECK(reply.text.find("which filament for part 7?") != std::string::npos);
    // The picture was written beside the question and its file is named in it.
    CHECK(reply.text.find("view1.png") != std::string::npos);

    const SemanticPaintReply none = semantic_paint_query(command("no-such-program-of-nia"), "anything", {});
    CHECK_FALSE(none.ok);
    CHECK(none.error.find("The command returned no answer") == 0);
}

TEST_CASE("A model name reaches a command as one word, whatever it holds", "[SemanticPaintClient]")
{
    // A name the shell would otherwise read as a second command.
    SemanticPaintSettings s = command(k_says_model);
#ifdef _WIN32
    s.model = "first & echo SECOND";
    const SemanticPaintReply reply = semantic_paint_query(s, "anything", {});
    REQUIRE(reply.ok);
    CHECK(reply.text.find("first & echo SECOND") != std::string::npos);
    // Printed once, as part of the name: no second command ran and printed it on a line of its own.
    CHECK(reply.text.find("SECOND") == reply.text.rfind("SECOND"));
    CHECK(reply.text.find("\nSECOND") == std::string::npos);
    // A double quote cannot be kept inside a quoted word there, so the name is refused and nothing is run.
    s.model = "first\" & echo SECOND & \"";
    const SemanticPaintReply refused = semantic_paint_query(s, "anything", {});
    CHECK_FALSE(refused.ok);
    CHECK(refused.error == "A model name cannot hold a double quote.");
#else
    s.model = "first; echo SECOND $(echo third) 'quoted' \"too\"";
    const SemanticPaintReply reply = semantic_paint_query(s, "anything", {});
    REQUIRE(reply.ok);
    CHECK(reply.text == s.model);
#endif
}

TEST_CASE("A question to a command that was given up ends at once", "[SemanticPaintClient]")
{
    std::atomic<bool> cancel{false};
    std::thread       gives_up([&cancel] {
        std::this_thread::sleep_for(std::chrono::milliseconds(400));
        cancel.store(true);
    });
    const auto               began = std::chrono::steady_clock::now();
    const SemanticPaintReply reply = semantic_paint_query(command(k_waits), "anything", {}, &cancel);
    const double             took  = std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
    gives_up.join();
    CHECK_FALSE(reply.ok);
    CHECK(reply.error == "Cancelled.");
    // The command would have waited half a minute.
    CHECK(took < 10.);
}
