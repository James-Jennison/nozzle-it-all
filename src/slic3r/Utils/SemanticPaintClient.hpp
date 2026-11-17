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
// no provider chosen, no command, no server address, no model, or an API key for a server that is not reached over HTTPS. The dialog says the same in its own words.
std::string semantic_paint_not_ready(const SemanticPaintSettings &settings);

// Where the data goes with these settings, for showing before anything is sent: the program a command line starts
// (its first word), or the server address. Empty when no provider is chosen.
std::string semantic_paint_recipient(const SemanticPaintSettings &settings);

// A server address as it is used: surrounding blanks, trailing slashes and a pasted "/chat/completions" removed.
// Nothing is added: some servers' addresses do not end in /v1.
std::string semantic_paint_tidy_endpoint(std::string endpoint);

// The model names in a server's answer to GET <address>/models, sorted; empty when the answer is not such a list.
std::vector<std::string> semantic_paint_parse_models(const std::string &body);

struct SemanticPaintCheck
{
    bool                     ok{false};
    std::string              found;  // command: the program's full path
    std::vector<std::string> models; // server: the models it lists
    std::string              error;  // set when !ok, in English
};

// Checks that the settings lead somewhere, sending no question, no picture and no model data. A command: its
// program is looked up. A server: its model list is asked for, with the key when one is set. Blocks until done.
SemanticPaintCheck semantic_paint_check(const SemanticPaintSettings &settings);

class AppConfig;
// The AI service settings as kept in the app's configuration, with the API key from the system keychain.
SemanticPaintSettings semantic_paint_load_settings(const AppConfig &config);
// Keeps everything but the API key, which only semantic_paint_save_api_key() stores.
void semantic_paint_save_settings(AppConfig &config, const SemanticPaintSettings &settings);

// Full path of `claude` if it is installed but not on PATH (GUI apps often start without ~/.local/bin), else empty.
std::string semantic_paint_find_claude();

// API key storage in the system keychain. Empty / false when no keychain is available.
std::string semantic_paint_load_api_key();
bool        semantic_paint_save_api_key(const std::string &key);

} // namespace Slic3r
