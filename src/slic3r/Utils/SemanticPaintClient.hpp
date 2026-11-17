#pragma once

#include <atomic>
#include <string>
#include <vector>

namespace Slic3r {

// The command line for Claude Code in print mode, which uses the user's own Claude subscription. It is offered as
// something the user can fill in, never set without being chosen: no provider is the default. {dir} is replaced by
// the folder holding the view images, {model} by the model name.
extern const char *const k_semantic_paint_claude_code_command;

struct SemanticPaintSettings
{
    // "command": run a program that reads the prompt on stdin and prints the answer (see SemanticPaint::command_prompt).
    // "openai":  an OpenAI-compatible chat completions endpoint (OpenAI, Ollama, LM Studio, vLLM, OpenRouter...).
    // Empty until the user chooses one: nothing can be sent before that.
    std::string provider;
    std::string command;
    std::string model;    // blank = the command's / server's default where that is allowed
    std::string endpoint; // openai: base URL without /chat/completions, e.g. http://localhost:11434/v1
    std::string api_key;  // openai: optional for local servers
};

struct SemanticPaintReply
{
    bool        ok{false};
    std::string text;  // assistant text
    std::string error; // set when !ok
};

// Sends the prompt and the PNG images (raw bytes, view order) to the chosen provider and blocks until it answers,
// fails, times out or *cancel becomes true.
SemanticPaintReply semantic_paint_query(const SemanticPaintSettings &settings, const std::string &prompt,
                                        const std::vector<std::string> &png_files, const std::atomic<bool> *cancel = nullptr);

// Why nothing can be sent with these settings, in English for the log and tests ("" when they can be used):
// no provider chosen, no command, no server address or no model. The dialog says the same in its own words.
std::string semantic_paint_not_ready(const SemanticPaintSettings &settings);

// Where the data goes with these settings, for showing before anything is sent: the program a command line starts
// (its first word), or the server address. Empty when no provider is chosen.
std::string semantic_paint_recipient(const SemanticPaintSettings &settings);

// Full path of `claude` if it is installed but not on PATH (GUI apps often start without ~/.local/bin), else empty.
std::string semantic_paint_find_claude();

// API key storage in the system keychain. Empty / false when no keychain is available.
std::string semantic_paint_load_api_key();
bool        semantic_paint_save_api_key(const std::string &key);

} // namespace Slic3r
