#include "SpoolInventory.hpp"

#include "SpoolTags.hpp"
#include "Utils.hpp"

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>
#include <nlohmann/json.hpp>

#include <algorithm>

namespace Slic3r {
namespace Spools {

using nlohmann::json;

namespace {

const char *kind_name(Location::Kind kind)
{
    switch (kind) {
    case Location::Kind::Dryer: return "dryer";
    case Location::Kind::Printer: return "printer";
    case Location::Kind::Storage: break;
    }
    return "storage";
}

Location::Kind kind_of(const std::string &name)
{
    return name == "dryer" ? Location::Kind::Dryer : name == "printer" ? Location::Kind::Printer : Location::Kind::Storage;
}

json to_json(const Spool &spool)
{
    json uses = json::array(), weighings = json::array(), drying = json::array();
    for (const Use &use : spool.uses)
        uses.push_back({{"job", use.job_id}, {"grams", use.grams}, {"measured", use.measured}, {"at", use.at_ms}});
    for (const Weighing &weighing : spool.weighings)
        weighings.push_back({{"remaining", weighing.remaining_g}, {"at", weighing.at_ms}});
    for (const Drying &session : spool.drying)
        drying.push_back({{"start", session.start_ms}, {"end", session.end_ms}, {"place", session.place}, {"notes", session.notes}});
    return {{"number", spool.number},     {"name", spool.name},         {"vendor", spool.vendor},   {"material", spool.material},
            {"color", spool.color},       {"initial", spool.initial_g}, {"tags", spool.tags},
            {"location", {{"kind", kind_name(spool.location.kind)}, {"place", spool.location.place}, {"slot", spool.location.slot}}},
            {"uses", uses},               {"weighings", weighings},     {"drying", drying},         {"notes", spool.notes},
            {"archived", spool.archived}, {"created", spool.created_ms}};
}

std::optional<Spool> from_json(const json &doc)
{
    try {
        if (!doc.is_object())
            return std::nullopt;
        Spool spool;
        spool.number = doc.at("number").get<int>();
        if (spool.number <= 0)
            return std::nullopt;
        spool.name       = doc.value("name", std::string());
        spool.vendor     = doc.value("vendor", std::string());
        spool.material   = doc.value("material", std::string());
        spool.color      = doc.value("color", std::string());
        spool.initial_g  = doc.value("initial", 0.);
        spool.notes      = doc.value("notes", std::string());
        spool.archived   = doc.value("archived", false);
        spool.created_ms = doc.value("created", 0LL);
        for (const json &tag : doc.value("tags", json::array()))
            if (tag.is_string())
                if (const std::string id = SpoolTags::normalized(tag.get<std::string>()); !id.empty())
                    spool.tags.push_back(id);
        if (doc.contains("location") && doc["location"].is_object()) {
            spool.location.kind  = kind_of(doc["location"].value("kind", std::string()));
            spool.location.place = doc["location"].value("place", std::string());
            spool.location.slot  = doc["location"].value("slot", -1);
        }
        for (const json &use : doc.value("uses", json::array()))
            if (use.is_object())
                spool.uses.push_back({use.value("job", std::string()), use.value("grams", 0.), use.value("measured", false), use.value("at", 0LL)});
        for (const json &weighing : doc.value("weighings", json::array()))
            if (weighing.is_object())
                spool.weighings.push_back({weighing.value("remaining", 0.), weighing.value("at", 0LL)});
        for (const json &session : doc.value("drying", json::array()))
            if (session.is_object())
                spool.drying.push_back({session.value("start", 0LL), session.value("end", 0LL), session.value("place", std::string()),
                                        session.value("notes", std::string())});
        return spool;
    } catch (const std::exception &) {
        return std::nullopt;
    }
}

} // namespace

Remaining remaining(const Spool &spool)
{
    Remaining left;
    left.grams        = spool.initial_g;
    long long since   = -1;
    for (const Weighing &weighing : spool.weighings)
        if (weighing.at_ms >= since) {
            since         = weighing.at_ms;
            left.grams    = weighing.remaining_g;
            left.measured = true;
        }
    for (const Use &use : spool.uses)
        // A use recorded at the very moment of a weighing is taken as weighed in.
        if (use.at_ms > since && use.grams > 0.) {
            left.grams    -= use.grams;
            left.measured = false;
        }
    left.grams = std::max(0., left.grams);
    return left;
}

const Drying *drying_now(const Spool &spool)
{
    for (auto it = spool.drying.rbegin(); it != spool.drying.rend(); ++it)
        if (it->end_ms == 0)
            return &*it;
    return nullptr;
}

Inventory Inventory::load(const std::string &path, bool *damaged)
{
    Inventory inventory;
    if (damaged != nullptr)
        *damaged = false;
    boost::system::error_code ec;
    if (!boost::filesystem::exists(path, ec))
        return inventory;
    const auto fail = [&] {
        if (damaged != nullptr)
            *damaged = true;
        return Inventory();
    };
    boost::nowide::ifstream in(path);
    if (!in)
        return fail();
    const json doc = json::parse(in, nullptr, false);
    if (doc.is_discarded() || !doc.is_object() || !doc.contains("spools") || !doc["spools"].is_array())
        return fail();
    for (const json &entry : doc["spools"]) {
        std::optional<Spool> spool = from_json(entry);
        // One entry that cannot be read makes the file one that must not be written over.
        if (!spool || inventory.find(spool->number) != nullptr)
            return fail();
        inventory.m_next_number = std::max(inventory.m_next_number, spool->number + 1);
        inventory.m_spools.push_back(std::move(*spool));
    }
    inventory.m_next_number = std::max(inventory.m_next_number, doc.value("nextNumber", 1));
    return inventory;
}

bool Inventory::save(const std::string &path) const
{
    json spools = json::array();
    for (const Spool &spool : m_spools)
        spools.push_back(to_json(spool));
    const json                doc = {{"format", 1}, {"nextNumber", m_next_number}, {"spools", spools}};
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

Spool *Inventory::find(int number)
{
    const auto it = std::find_if(m_spools.begin(), m_spools.end(), [number](const Spool &spool) { return spool.number == number; });
    return it == m_spools.end() ? nullptr : &*it;
}

const Spool *Inventory::find(int number) const { return const_cast<Inventory *>(this)->find(number); }

Spool *Inventory::at(const std::string &printer, int slot)
{
    if (printer.empty() || slot < 0)
        return nullptr;
    for (Spool &spool : m_spools)
        if (!spool.archived && spool.location.kind == Location::Kind::Printer && spool.location.place == printer && spool.location.slot == slot)
            return &spool;
    return nullptr;
}

const Spool *Inventory::at(const std::string &printer, int slot) const { return const_cast<Inventory *>(this)->at(printer, slot); }

const Spool *Inventory::with_tag(const std::string &tag_id) const
{
    const std::string id = SpoolTags::normalized(tag_id);
    if (id.empty())
        return nullptr;
    for (const Spool &spool : m_spools)
        if (!spool.archived && std::find(spool.tags.begin(), spool.tags.end(), id) != spool.tags.end())
            return &spool;
    return nullptr;
}

int Inventory::add(Spool spool, long long now_ms)
{
    spool.number     = m_next_number++;
    spool.created_ms = now_ms;
    spool.initial_g  = std::max(0., spool.initial_g);
    std::vector<std::string> tags;
    for (const std::string &tag : spool.tags)
        if (const std::string id = SpoolTags::normalized(tag); !id.empty() && std::find(tags.begin(), tags.end(), id) == tags.end())
            tags.push_back(id);
    spool.tags = std::move(tags);
    // A slot holds one spool: one added into an occupied slot starts in storage instead.
    if (spool.location.kind == Location::Kind::Printer && at(spool.location.place, spool.location.slot) != nullptr)
        spool.location = Location{Location::Kind::Storage, "unplaced", -1};
    m_spools.push_back(std::move(spool));
    return m_spools.back().number;
}

int Inventory::move(int number, const Location &to)
{
    Spool *spool = find(number);
    if (spool == nullptr)
        return 0;
    int displaced = 0;
    if (to.kind == Location::Kind::Printer)
        if (Spool *there = at(to.place, to.slot); there != nullptr && there->number != number) {
            displaced       = there->number;
            there->location = Location{Location::Kind::Storage, "unplaced", -1};
        }
    spool->location = to;
    if (to.kind != Location::Kind::Printer)
        spool->location.slot = -1;
    return displaced;
}

bool Inventory::record_use(int number, const Use &use)
{
    Spool *spool = find(number);
    if (spool == nullptr || use.grams < 0.)
        return false;
    if (!use.job_id.empty()) {
        const auto had = std::find_if(spool->uses.begin(), spool->uses.end(), [&use](const Use &other) { return other.job_id == use.job_id; });
        if (had != spool->uses.end()) {
            // The time first recorded is kept: a weighing made since then already has this job in it.
            const long long first = had->at_ms;
            *had                  = use;
            had->at_ms            = first;
            return true;
        }
    }
    spool->uses.push_back(use);
    return true;
}

bool Inventory::weigh(int number, double remaining_g, long long now_ms)
{
    Spool *spool = find(number);
    if (spool == nullptr || remaining_g < 0.)
        return false;
    spool->weighings.push_back({remaining_g, now_ms});
    return true;
}

bool Inventory::start_drying(int number, const std::string &place, long long now_ms)
{
    Spool *spool = find(number);
    if (spool == nullptr || drying_now(*spool) != nullptr)
        return false;
    spool->drying.push_back({now_ms, 0, place, std::string()});
    return true;
}

bool Inventory::end_drying(int number, const std::string &notes, long long now_ms)
{
    Spool *spool = find(number);
    if (spool == nullptr)
        return false;
    for (auto it = spool->drying.rbegin(); it != spool->drying.rend(); ++it)
        if (it->end_ms == 0) {
            it->end_ms = std::max(now_ms, it->start_ms);
            it->notes  = notes;
            return true;
        }
    return false;
}

std::string inventory_path() { return (boost::filesystem::path(data_dir()) / "spools.json").string(); }

} // namespace Spools
} // namespace Slic3r
