#ifndef slic3r_SpoolTags_hpp_
#define slic3r_SpoolTags_hpp_

#include <nlohmann/json_fwd.hpp>

#include <map>
#include <string>

namespace Slic3r {

/// What the app remembers about a spool's tag as a printer's own reader reports it: the filament preset a
/// person chose for that tag, per printer model. It is a convenience for the filament sync, which then offers
/// that preset first; it never decides anything about a print. Kept in one file in the data folder.
namespace SpoolTags {

/// The identity of the tag a Snapmaker U1 toolhead read: the bytes of filament_detect.info[i].CARD_UID as
/// upper-case hexadecimal ("70498FE2"). Empty when the toolhead read no tag (the printer then reports 0).
std::string u1_card_uid(const nlohmann::json &tag);

/// A tag's identity as the memory keys it: letters and digits only, upper case, at most 64 characters.
/// Empty when nothing usable is left.
std::string normalized(const std::string &tag_id);

struct Remembered
{
    /// By printer model (the printer preset's printer_model): the name of the filament preset chosen.
    std::map<std::string, std::string> presets;
    long long                          seen_ms = 0; ///< when a choice was last recorded for this tag
};

class Memory
{
public:
    /// Tags kept at most; the one not chosen for the longest goes first.
    static constexpr size_t CAPACITY = 2048;

    /// Reads *path*. A file that is missing or cannot be read gives an empty memory, never an error: the
    /// memory is a convenience.
    static Memory load(const std::string &path);
    /// Writes to *path* through a temporary file and a rename. False when it could not be written.
    bool save(const std::string &path) const;

    /// The preset remembered for *tag_id* on *printer_model*; empty when there is none.
    std::string preset_for(const std::string &tag_id, const std::string &printer_model) const;
    /// Records the choice. Returns false, and changes nothing, for an empty tag, model or preset.
    bool remember(const std::string &tag_id, const std::string &printer_model, const std::string &preset, long long now_ms);
    size_t size() const { return m_tags.size(); }

private:
    std::map<std::string, Remembered> m_tags;
};

/// Where the memory is kept: data_dir()/spool_tags.json.
std::string memory_path();

} // namespace SpoolTags
} // namespace Slic3r

#endif // slic3r_SpoolTags_hpp_
