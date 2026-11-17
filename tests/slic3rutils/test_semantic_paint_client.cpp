#include <catch2/catch_test_macros.hpp>

#include "slic3r/Utils/SemanticPaintClient.hpp"

#include <string>

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
