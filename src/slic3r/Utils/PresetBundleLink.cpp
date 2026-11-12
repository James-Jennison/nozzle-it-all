#include "PresetBundleLink.hpp"

#include <algorithm>
#include <cctype>
#include <initializer_list>

#include <boost/algorithm/string/case_conv.hpp>
#include <boost/algorithm/string/predicate.hpp>
#include <boost/algorithm/string/trim.hpp>

namespace Slic3r {

namespace {

bool is_token(const std::string& s)
{
    return s.size() >= 4 && s.size() <= 64 &&
           std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isalnum(c) || c == '-' || c == '_'; });
}

// The text after `prefix` when `path` is exactly prefix + one path component, else empty.
std::string single_component_after(const std::string& path, const std::string& prefix)
{
    if (!boost::algorithm::starts_with(path, prefix))
        return {};
    std::string rest = path.substr(prefix.size());
    while (!rest.empty() && rest.back() == '/')
        rest.pop_back();
    return rest.find('/') == std::string::npos ? rest : std::string();
}

} // namespace

PresetBundleLink resolve_preset_bundle_link(const std::string& text)
{
    PresetBundleLink  link;
    const std::string url = boost::algorithm::trim_copy(text);
    const std::string scheme = "https://";
    if (!boost::algorithm::istarts_with(url, scheme))
        return link;
    // Nothing a browser and the HTTP library could read differently: no blanks, controls or backslashes.
    if (std::any_of(url.begin(), url.end(), [](unsigned char c) { return c <= ' ' || c == 0x7f || c == '\\'; }))
        return link;

    const size_t      authority_end = url.find_first_of("/?#", scheme.size());
    const std::string authority     = url.substr(scheme.size(), authority_end == std::string::npos ? std::string::npos : authority_end - scheme.size());
    if (authority.empty() || authority.find('@') != std::string::npos || authority.front() == '[')
        return link;
    std::string host = boost::algorithm::to_lower_copy(authority.substr(0, authority.find(':')));
    while (!host.empty() && host.back() == '.')
        host.pop_back();
    if (host.empty())
        return link;

    std::string path;
    if (authority_end != std::string::npos && url[authority_end] == '/')
        path = url.substr(authority_end, url.find_first_of("?#", authority_end) - authority_end);

    std::string token;
    if (host == "cloud.orcaslicer.com")
        token = single_component_after(path, "/b/");
    else if (host == "api.orcaslicer.com")
        token = single_component_after(path, "/api/v1/bundles/share/");
    if (is_token(token)) {
        link.kind        = PresetBundleLink::Kind::SharedBundle;
        link.token       = token;
        link.request_url = "https://api.orcaslicer.com/api/v1/bundles/share/" + token;
        return link;
    }

    for (const char* extension : {".orca_bundle", ".orca_printer", ".orca_filament", ".zip", ".json"}) {
        if (!boost::algorithm::iends_with(path, extension))
            continue;
        std::string name = path.substr(path.find_last_of('/') + 1);
        name.erase(name.size() - std::string(extension).size());
        std::replace_if(name.begin(), name.end(), [](unsigned char c) { return !(std::isalnum(c) || c == '-' || c == '_'); }, '_');
        if (name.empty() || name.size() > 80)
            name = "bundle";
        link.kind        = PresetBundleLink::Kind::File;
        link.file_name   = name + boost::algorithm::to_lower_copy(std::string(extension));
        link.request_url = url.substr(0, url.find('#'));
        return link;
    }
    return link;
}

} // namespace Slic3r
