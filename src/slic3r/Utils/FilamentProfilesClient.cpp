#include "FilamentProfilesClient.hpp"

#include <algorithm>
#include <cctype>
#include <set>

#include <nlohmann/json.hpp>
#include <wx/secretstore.h>

#include "Http.hpp"

namespace Slic3r {
namespace {

constexpr const char* kEndpoint      = "https://3dfilamentprofiles.com/api/v1/filaments";
constexpr const char* kSecretService = "NozzleItAll/3DFilamentProfiles";
constexpr const char* kSecretUser    = "api-key";

std::string field(const nlohmann::json& item, std::initializer_list<const char*> names)
{
    for (const char* name : names) {
        const auto it = item.find(name);
        if (it != item.end() && it->is_string())
            return it->get<std::string>();
    }
    return {};
}

std::string lower(std::string value)
{
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) { return char(std::tolower(ch)); });
    return value;
}

std::set<std::string> material_families(const std::string& value)
{
    std::set<std::string> families;
    std::string token;
    auto classify = [&]() {
        if (token == "pla" || token == "pla+")
            families.insert("PLA");
        else if (token == "petg")
            families.insert("PETG");
        else if (token == "tpu")
            families.insert("TPU");
        else if (token == "asa")
            families.insert("ASA");
        else if (token == "abs")
            families.insert("ABS");
        else if (token == "pc" || token == "polycarbonate")
            families.insert("PC");
        else if (token == "pa" || token == "nylon" ||
                 (token.size() > 2 && token.rfind("pa", 0) == 0 &&
                  std::all_of(token.begin() + 2, token.end(), [](unsigned char ch) { return std::isdigit(ch) != 0; })))
            families.insert("PA");
        else if (token == "pva")
            families.insert("PVA");
        else if (token == "hips")
            families.insert("HIPS");
        token.clear();
    };
    for (unsigned char ch : lower(value)) {
        if (std::isalnum(ch) || ch == '+')
            token += char(ch);
        else
            classify();
    }
    classify();
    return families;
}

void append_word(std::string& out, const std::string& word)
{
    if (word.empty())
        return;
    if (!out.empty())
        out += ' ';
    out += word;
}

} // namespace

std::vector<FilamentProfileRecord> parse_filament_profiles_records(const nlohmann::json& payload)
{
    const nlohmann::json* items = &payload;
    if (payload.is_object()) {
        items = nullptr;
        for (const char* key : {"filaments", "data", "results"}) {
            const auto it = payload.find(key);
            if (it != payload.end() && it->is_array()) {
                items = &*it;
                break;
            }
        }
    }
    if (items == nullptr || !items->is_array())
        return {};

    std::vector<FilamentProfileRecord> records;
    records.reserve(items->size());
    for (const nlohmann::json& item : *items) {
        if (!item.is_object())
            continue;
        FilamentProfileRecord record;
        record.id            = field(item, {"id", "filament_id"});
        record.short_code    = field(item, {"short_code", "shortCode"});
        record.brand         = field(item, {"brand_name", "brand", "manufacturer"});
        record.material      = field(item, {"material", "material_name"});
        record.material_type = field(item, {"material_type", "materialType", "type"});
        record.color         = field(item, {"color", "colour", "color_name"});
        record.rgb           = field(item, {"rgb", "hex", "color_hex"});
        record.website       = field(item, {"website", "url", "record_url"});
        if (!record.brand.empty() || !record.material.empty() || !record.id.empty())
            records.push_back(std::move(record));
    }
    return records;
}

std::string filament_profile_product_name(const FilamentProfileRecord& record)
{
    std::string name;
    append_word(name, record.brand);
    append_word(name, record.material);
    const std::string material = lower(record.material);
    const std::string subtype  = lower(record.material_type);
    if (!record.material_type.empty() && material.find(subtype) == std::string::npos)
        append_word(name, record.material_type);
    return name;
}

std::string filament_profile_base_material(const FilamentProfileRecord& record)
{
    // The catalog's material field is authoritative. A product that names
    // multiple base families is ambiguous and must not silently inherit one
    // family's slicing settings. Only consult the subtype when material itself
    // contains no recognized family.
    const std::set<std::string> declared = material_families(record.material);
    if (declared.size() == 1)
        return *declared.begin();
    if (declared.size() > 1)
        return {};
    const std::set<std::string> subtype = material_families(record.material_type);
    if (subtype.size() == 1)
        return *subtype.begin();
    if (subtype.size() > 1)
        return {};
    return record.material;
}

bool filament_profile_record_is_usable(const FilamentProfileRecord& record)
{
    return !filament_profile_product_name(record).empty() && !filament_profile_base_material(record).empty();
}

std::string filament_profiles_search_url(const std::string& query, unsigned limit)
{
    limit = std::clamp(limit, 1u, 50u);
    return std::string(kEndpoint) + "?q=" + Http::url_encode(query) + "&limit=" + std::to_string(limit);
}

FilamentProfilesSearchReply parse_filament_profiles_search_response(const std::string& body)
{
    FilamentProfilesSearchReply reply;
    try {
        reply.records = parse_filament_profiles_records(nlohmann::json::parse(body));
        reply.records.erase(std::remove_if(reply.records.begin(), reply.records.end(), [](const FilamentProfileRecord& record) {
                                return !filament_profile_record_is_usable(record);
                            }),
                            reply.records.end());
        reply.ok      = true;
    } catch (const std::exception&) {
        reply.error = "3D Filament Profiles returned an unreadable response.";
    }
    return reply;
}

Http::Ptr search_filament_profiles_async(const std::string& query, const std::string& api_key,
                                         std::function<void(FilamentProfilesSearchReply)> callback)
{
    FilamentProfilesSearchReply reply;
    if (query.empty()) {
        reply.error = "Enter a filament brand or product to search.";
        callback(std::move(reply));
        return {};
    }
    if (api_key.empty()) {
        reply.error = "A 3D Filament Profiles API key is required.";
        callback(std::move(reply));
        return {};
    }

    Http http = Http::get(filament_profiles_search_url(query));
    http.header("Accept", "application/json")
        .header("X-API-Key", api_key)
        .tls_verify(true)
        .timeout_connect(10)
        .timeout_max(30)
        .size_limit(2 * 1024 * 1024)
        .on_complete([callback](std::string body, unsigned) mutable {
            callback(parse_filament_profiles_search_response(body));
        })
        .on_error([callback](std::string, std::string error, unsigned status) mutable {
            FilamentProfilesSearchReply failed;
            failed.error = error.empty() ? "3D Filament Profiles returned HTTP " + std::to_string(status) + "." : std::move(error);
            callback(std::move(failed));
        });
    return http.perform();
}

std::string load_filament_profiles_api_key()
{
    wxSecretStore store = wxSecretStore::GetDefault();
    if (!store.IsOk())
        return {};
    wxString user;
    wxSecretValue value;
    if (!store.Load(kSecretService, user, value))
        return {};
    return std::string(value.GetAsString().utf8_str());
}

bool save_filament_profiles_api_key(const std::string& api_key)
{
    wxSecretStore store = wxSecretStore::GetDefault();
    if (!store.IsOk())
        return false;
    if (api_key.empty())
        return store.Delete(kSecretService);
    return store.Save(kSecretService, kSecretUser, wxSecretValue(wxString::FromUTF8(api_key.c_str())));
}

} // namespace Slic3r
