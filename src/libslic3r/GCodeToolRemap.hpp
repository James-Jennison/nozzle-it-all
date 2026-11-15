#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace Slic3r {

/// Renumbering of the tools a sliced G-code file selects.
///
/// A single-nozzle printer fed from several lanes (a Centauri Carbon on COSMOS with a CANVAS driven by AFC)
/// loads the lane whose tool number a `T<n>` line names, and starts from the one its start macro is given as
/// `TOOL=<n>`. Printing a project's filament from another lane than its own number therefore means writing
/// other numbers into the file; nothing is changed on the printer. Only those two forms are rewritten, and a
/// file that names a tool in any other way is refused, so a map is either applied in full or not at all.
///
/// No wxWidgets or network dependency, so it can be unit-tested directly.
namespace GCodeToolRemap {

/// Marks a filament the map has no tool for.
constexpr int UNMAPPED = -1;

enum class LineResult {
    Unchanged,     ///< nothing in the line names a tool
    Rewritten,     ///< a tool number was replaced (or was already the mapped one)
    NotUnderstood, ///< the line names a tool in a form this code does not rewrite
    NoTool         ///< the line selects a filament the map has no tool for
};

/// Rewrites one line (without its line ending) in place. `filament_to_tool[f]` is the tool filament `f` prints from.
LineResult remap_line(std::string &line, const std::vector<int> &filament_to_tool);

struct Result
{
    bool        ok = false;
    size_t      tool_lines = 0;   ///< lines that select a tool, rewritten or already right
    size_t      changed_lines = 0;///< of those, lines whose number changed
    size_t      failed_line = 0;  ///< 1-based line number of the first refusal, 0 if none
    LineResult  failure = LineResult::Unchanged;
    std::string failed_text;      ///< that line, shortened
    std::string io_error;         ///< set when a file could not be read or written
};

/// True when every used filament prints from the tool of its own number, so the file needs no rewriting.
bool is_identity(const std::vector<int> &filament_to_tool);

/// Copies `source` to `target` with the tool numbers rewritten. Every other byte, line endings included, is
/// copied as it is. On a refusal `target` is removed and `ok` is false.
Result remap_file(const std::string &source, const std::string &target, const std::vector<int> &filament_to_tool);

} // namespace GCodeToolRemap
} // namespace Slic3r
