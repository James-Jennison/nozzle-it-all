#include <catch2/catch_test_macros.hpp>

#include "slic3r/Utils/SemanticPaintClient.hpp"

#include "libslic3r/AppConfig.hpp"

#include <string>
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
    s.command = "sh -c 'exit 9'";
    const SemanticPaintCheck found = semantic_paint_check(s);
    CHECK(found.ok);
    CHECK(found.found.find("sh") != std::string::npos);

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
