#include "GCodeToolRemap.hpp"

#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>
#include <boost/nowide/fstream.hpp>
#include <boost/system/error_code.hpp>

#include <cctype>
#include <cstddef>
#include <ios>
#include <string>
#include <string_view>
#include <vector>

namespace Slic3r {
namespace GCodeToolRemap {

namespace {

bool is_digit(char c) { return c >= '0' && c <= '9'; }
bool is_space(char c) { return c == ' ' || c == '\t'; }
char upper(char c) { return static_cast<char>(std::toupper(static_cast<unsigned char>(c))); }

bool starts_with_word(std::string_view text, std::string_view word)
{
    if (text.size() < word.size())
        return false;
    for (size_t i = 0; i < word.size(); ++i)
        if (upper(text[i]) != word[i])
            return false;
    return text.size() == word.size() || is_space(text[word.size()]);
}

// Reads the digits at `pos`; false when there are none or more than a tool number can have.
bool read_number(std::string_view text, size_t pos, int &value, size_t &end)
{
    end = pos;
    value = 0;
    while (end < text.size() && is_digit(text[end]) && end - pos < 4)
        value = value * 10 + (text[end++] - '0');
    return end > pos && (end == text.size() || !is_digit(text[end]));
}

int mapped(const std::vector<int> &filament_to_tool, int filament)
{
    return filament >= 0 && static_cast<size_t>(filament) < filament_to_tool.size() ? filament_to_tool[filament] : UNMAPPED;
}

// True when a word of the code part is `T<digits>` or holds `TOOL=<digit>`: a tool named as a parameter.
bool names_a_tool(std::string_view code, size_t from)
{
    size_t i = from;
    while (i < code.size()) {
        while (i < code.size() && is_space(code[i]))
            ++i;
        size_t word_end = i;
        while (word_end < code.size() && !is_space(code[word_end]))
            ++word_end;
        const std::string_view word = code.substr(i, word_end - i);
        if (word.size() >= 2 && upper(word[0]) == 'T' && is_digit(word[1])) {
            size_t k = 1;
            while (k < word.size() && is_digit(word[k]))
                ++k;
            if (k == word.size())
                return true;
        }
        if (word.size() > 5 && starts_with_word(std::string(word.substr(0, 4)), "TOOL") && word[4] == '=' && is_digit(word[5]))
            return true;
        i = word_end;
    }
    return false;
}

} // namespace

LineResult remap_line(std::string &line, const std::vector<int> &filament_to_tool)
{
    size_t start = 0;
    while (start < line.size() && is_space(line[start]))
        ++start;
    if (start == line.size() || line[start] == ';')
        return LineResult::Unchanged;
    // The code part ends at a comment; a trailing carriage return stays outside it.
    size_t code_end = line.find(';', start);
    if (code_end == std::string::npos)
        code_end = line.size();
    while (code_end > start && (line[code_end - 1] == '\r' || is_space(line[code_end - 1])))
        --code_end;
    const std::string_view code(line.data() + start, code_end - start);

    // "T<n>", alone or followed by parameters (AFC's "T1 PURGE_LENGTH=...").
    if (upper(code[0]) == 'T' && code.size() >= 2 && is_digit(code[1])) {
        int    filament = 0;
        size_t end = 0;
        if (!read_number(code, 1, filament, end) || (end < code.size() && !is_space(code[end])))
            return LineResult::NotUnderstood;
        if (names_a_tool(code, end))
            return LineResult::NotUnderstood;
        const int tool = mapped(filament_to_tool, filament);
        if (tool < 0)
            return LineResult::NoTool;
        line.replace(start + 1, end - 1, std::to_string(tool));
        return LineResult::Rewritten;
    }

    // "PRINT_START ... TOOL=<n> ...": the lane the print starts from.
    if (starts_with_word(code, "PRINT_START")) {
        size_t found = std::string::npos;
        size_t i = 11;
        while (i < code.size()) {
            while (i < code.size() && is_space(code[i]))
                ++i;
            size_t word_end = i;
            while (word_end < code.size() && !is_space(code[word_end]))
                ++word_end;
            const std::string_view word = code.substr(i, word_end - i);
            if (word.size() > 5 && starts_with_word(std::string(word.substr(0, 4)), "TOOL") && word[4] == '=') {
                if (found != std::string::npos)
                    return LineResult::NotUnderstood;
                found = i + 5;
            } else if (word.size() >= 2 && upper(word[0]) == 'T' && is_digit(word[1]) && names_a_tool(word, 0))
                return LineResult::NotUnderstood;
            i = word_end;
        }
        if (found == std::string::npos)
            return LineResult::Unchanged;
        int    filament = 0;
        size_t end = 0;
        if (!read_number(code, found, filament, end) || (end < code.size() && !is_space(code[end])))
            return LineResult::NotUnderstood;
        const int tool = mapped(filament_to_tool, filament);
        if (tool < 0)
            return LineResult::NoTool;
        line.replace(start + found, end - found, std::to_string(tool));
        return LineResult::Rewritten;
    }

    return names_a_tool(code, 0) ? LineResult::NotUnderstood : LineResult::Unchanged;
}

bool is_identity(const std::vector<int> &filament_to_tool)
{
    for (size_t f = 0; f < filament_to_tool.size(); ++f)
        if (filament_to_tool[f] != UNMAPPED && filament_to_tool[f] != static_cast<int>(f))
            return false;
    return true;
}

Result remap_file(const std::string &source, const std::string &target, const std::vector<int> &filament_to_tool)
{
    Result result;
    boost::nowide::ifstream in(source, std::ios::binary);
    if (!in) {
        result.io_error = "cannot read " + source;
        return result;
    }
    boost::nowide::ofstream out(target, std::ios::binary | std::ios::trunc);
    if (!out) {
        result.io_error = "cannot write " + target;
        return result;
    }

    std::string line;
    size_t      number = 0;
    while (std::getline(in, line)) {
        ++number;
        const bool        had_newline = !in.eof();
        const std::string before = line;
        const LineResult  r = remap_line(line, filament_to_tool);
        if (r == LineResult::NotUnderstood || r == LineResult::NoTool) {
            result.failed_line = number;
            result.failure = r;
            result.failed_text = before.substr(0, 120);
            break;
        }
        if (r == LineResult::Rewritten) {
            ++result.tool_lines;
            if (line != before)
                ++result.changed_lines;
        }
        out.write(line.data(), static_cast<std::streamsize>(line.size()));
        if (had_newline)
            out.put('\n');
    }
    const bool read_failed = in.bad();
    out.close();
    if (result.failed_line == 0 && !read_failed && !out.fail()) {
        result.ok = true;
        return result;
    }
    if (result.failed_line == 0)
        result.io_error = read_failed ? "reading " + source + " failed" : "writing " + target + " failed";
    boost::system::error_code ec;
    boost::filesystem::remove(boost::filesystem::path(target), ec);
    return result;
}

} // namespace GCodeToolRemap
} // namespace Slic3r
