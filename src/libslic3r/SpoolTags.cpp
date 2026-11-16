#include "SpoolTags.hpp"

#include "Utils.hpp"

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <cctype>
#include <cstdio>

namespace Slic3r {
namespace SpoolTags {

std::string u1_card_uid(const nlohmann::json &tag)
{
    if (!tag.is_object())
        return {};
    const auto uid = tag.find("CARD_UID");
    if (uid == tag.end() || !uid->is_array() || uid->empty() || uid->size() > 16)
        return {};
    std::string hex;
    for (const nlohmann::json &byte : *uid) {
        if (!byte.is_number_integer() || byte.get<long long>() < 0 || byte.get<long long>() > 255)
            return {};
        char text[3];
        std::snprintf(text, sizeof(text), "%02X", static_cast<unsigned>(byte.get<long long>()));
        hex += text;
    }
    // A reader with no card reports zeros.
    return std::all_of(hex.begin(), hex.end(), [](char c) { return c == '0'; }) ? std::string() : hex;
}

std::string normalized(const std::string &tag_id)
{
    std::string id;
    for (unsigned char c : tag_id)
        if (std::isalnum(c) != 0)
            id += static_cast<char>(std::toupper(c));
    return id.size() > 64 || std::all_of(id.begin(), id.end(), [](char c) { return c == '0'; }) ? std::string() : id;
}

Memory Memory::load(const std::string &path)
{
    Memory                  memory;
    boost::nowide::ifstream in(path);
    if (!in)
        return memory;
    const nlohmann::json doc = nlohmann::json::parse(in, nullptr, false);
    if (doc.is_discarded() || !doc.is_object() || !doc.contains("tags") || !doc["tags"].is_object())
        return memory;
    for (const auto &entry : doc["tags"].items()) {
        const std::string id = normalized(entry.key());
        if (id.empty() || !entry.value().is_object() || memory.m_tags.size() >= CAPACITY)
            continue;
        Remembered remembered;
        const auto presets = entry.value().find("presets");
        if (presets != entry.value().end() && presets->is_object())
            for (const auto &choice : presets->items())
                if (choice.value().is_string() && !choice.key().empty() && !choice.value().get<std::string>().empty())
                    remembered.presets[choice.key()] = choice.value().get<std::string>();
        const auto seen = entry.value().find("seenAt");
        if (seen != entry.value().end() && seen->is_number_integer())
            remembered.seen_ms = seen->get<long long>();
        if (!remembered.presets.empty())
            memory.m_tags[id] = std::move(remembered);
    }
    return memory;
}

bool Memory::save(const std::string &path) const
{
    nlohmann::json tags = nlohmann::json::object();
    for (const auto &entry : m_tags)
        tags[entry.first] = {{"presets", entry.second.presets}, {"seenAt", entry.second.seen_ms}};
    const nlohmann::json      doc = {{"format", 1}, {"tags", tags}};
    const std::string         tmp = path + ".tmp";
    boost::system::error_code ec;
    {
        boost::nowide::ofstream out(tmp, std::ios::trunc);
        if (!out)
            return false;
        out << doc.dump(1);
        out.flush();
        if (!out)
            return false;
    }
    boost::filesystem::rename(tmp, path, ec);
    if (ec)
        boost::filesystem::remove(tmp, ec);
    return !ec;
}

std::string Memory::preset_for(const std::string &tag_id, const std::string &printer_model) const
{
    const auto tag = m_tags.find(normalized(tag_id));
    if (tag == m_tags.end())
        return {};
    const auto preset = tag->second.presets.find(printer_model);
    return preset == tag->second.presets.end() ? std::string() : preset->second;
}

bool Memory::remember(const std::string &tag_id, const std::string &printer_model, const std::string &preset, long long now_ms)
{
    const std::string id = normalized(tag_id);
    if (id.empty() || printer_model.empty() || preset.empty())
        return false;
    Remembered &remembered           = m_tags[id];
    remembered.presets[printer_model] = preset;
    remembered.seen_ms               = now_ms;
    while (m_tags.size() > CAPACITY) {
        const auto oldest = std::min_element(m_tags.begin(), m_tags.end(), [&id](const auto &a, const auto &b) {
            // The tag just recorded is never the one that goes.
            if ((a.first == id) != (b.first == id))
                return b.first == id;
            return a.second.seen_ms < b.second.seen_ms;
        });
        m_tags.erase(oldest);
    }
    return true;
}

std::string memory_path() { return (boost::filesystem::path(data_dir()) / "spool_tags.json").string(); }

} // namespace SpoolTags
} // namespace Slic3r
