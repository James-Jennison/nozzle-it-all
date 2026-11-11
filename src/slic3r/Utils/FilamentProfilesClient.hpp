#ifndef slic3r_Utils_FilamentProfilesClient_hpp_
#define slic3r_Utils_FilamentProfilesClient_hpp_

#include <functional>
#include <string>
#include <vector>

#include <nlohmann/json_fwd.hpp>

#include "Http.hpp"

namespace Slic3r {

struct FilamentProfileRecord
{
    std::string id;
    std::string short_code;
    std::string brand;
    std::string material;
    std::string material_type;
    std::string color;
    std::string rgb;
    std::string website;
};

struct FilamentProfilesSearchReply
{
    bool ok{false};
    std::vector<FilamentProfileRecord> records;
    std::string error;
};

/// Parses the supported array and {data|filaments|results:[...]} response shapes.
std::vector<FilamentProfileRecord> parse_filament_profiles_records(const nlohmann::json& payload);

/// Product identity retained by Orca. Color stays a per-project slot property and is not part
/// of the preset name, so a spool color change does not create another slicing preset.
std::string filament_profile_product_name(const FilamentProfileRecord& record);

/// Maps product names such as PLA+ and PLA Pro to Orca's base slicing material class.
std::string filament_profile_base_material(const FilamentProfileRecord& record);

/// A catalog result can be offered only when it has a visible product identity and
/// maps unambiguously to a slicing material family.
bool filament_profile_record_is_usable(const FilamentProfileRecord& record);

/// Builds a user-initiated, bounded catalog search URL. Authentication is always a request
/// header and is deliberately absent from this value.
std::string filament_profiles_search_url(const std::string& query, unsigned limit = 25);

/// Converts a catalog response body into the same success/failure value delivered by the
/// asynchronous client. An empty result set is a successful search; malformed JSON is not.
FilamentProfilesSearchReply parse_filament_profiles_search_response(const std::string& body);

/// Starts a cancellable catalog request. The callback runs on the HTTP worker thread;
/// GUI callers must marshal results back to the main thread.
Http::Ptr search_filament_profiles_async(const std::string& query, const std::string& api_key,
                                         std::function<void(FilamentProfilesSearchReply)> callback);

std::string load_filament_profiles_api_key();
bool save_filament_profiles_api_key(const std::string& api_key);

} // namespace Slic3r

#endif
