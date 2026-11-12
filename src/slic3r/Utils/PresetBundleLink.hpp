#pragma once

#include <string>

namespace Slic3r {

// What a link the user typed for "Import Preset Bundle from Link" points at.
struct PresetBundleLink
{
    enum class Kind {
        Invalid,      // not a link this import takes
        SharedBundle, // a bundle its author shared on Orca Cloud; request_url is the public bundle address
        File          // a bundle or preset file; request_url is the link itself
    };

    Kind        kind{Kind::Invalid};
    std::string request_url; // the one address that is downloaded
    std::string token;       // SharedBundle: the share token
    std::string file_name;   // File: a safe name for the downloaded copy, with the link's extension
};

// Reads a link. Only https is taken, and no link that carries user information.
// A shared bundle is https://cloud.orcaslicer.com/b/<token> or the public bundle address itself;
// a file is any other https link whose path ends in .orca_bundle, .orca_printer, .orca_filament, .zip or .json.
PresetBundleLink resolve_preset_bundle_link(const std::string& text);

} // namespace Slic3r
