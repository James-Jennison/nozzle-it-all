#include "SpoolJobs.hpp"

#include <algorithm>
#include <map>

namespace Slic3r {
namespace SpoolJobs {

Spools::Spool *spool_in(Spools::Inventory &inventory, const std::string &printer, int slot)
{
    if (slot < 0)
        return nullptr;
    const std::string key = U1Jobs::printer_key(printer);
    for (const Spools::Spool &spool : inventory.spools())
        if (!spool.archived && spool.location.kind == Spools::Location::Kind::Printer && spool.location.slot == slot &&
            U1Jobs::same_printer(U1Jobs::printer_key(spool.location.place), key))
            return inventory.find(spool.number);
    return nullptr;
}

int record_job(Spools::Inventory &inventory, const U1Jobs::Job &job, long long now_ms)
{
    const bool completed = job.state == U1Jobs::State::Completed;
    if (!completed && job.state != U1Jobs::State::Cancelled && job.state != U1Jobs::State::Failed)
        return 0;
    // A job that never started printing took nothing; one that stopped took the part the printer's own history
    // says was fed, and nothing is written while the printer has not said.
    if (!completed && (!job.start_sent || job.fed_part < 0.))
        return 0;
    // Across several filaments the part is of their total length, so each is counted in that proportion.
    const double part = completed ? 1. : std::clamp(job.fed_part, 0., 1.);
    // By spool: the one noted when the job was sent, or, for a job without that note, the one in the slot now.
    std::map<int, double> by_spool;
    for (size_t i = 0; i < job.origin.filaments.size() && i < job.toolhead_map.size(); ++i) {
        if (!job.origin.filaments[i].used || job.origin.filaments[i].grams <= 0. || job.toolhead_map[i] < 0)
            continue;
        // The note is one entry per filament; a filament it does not reach was not noted and goes by the slot.
        int number = 0;
        if (i < job.spools.size())
            number = job.spools[i];
        else if (const Spools::Spool *spool = spool_in(inventory, job.printer, job.toolhead_map[i]))
            number = spool->number;
        if (number > 0)
            by_spool[number] += job.origin.filaments[i].grams * part;
    }
    int written = 0;
    for (const auto &taken : by_spool)
        if (inventory.record_use(taken.first, Spools::Use{job.id, taken.second, false, job.ended_ms > 0 ? job.ended_ms : now_ms}))
            ++written;
    return written;
}

void set_reported_weight(Spools::Spool &spool, double full_g, double left_g, long long now_ms)
{
    spool.initial_g = full_g > 0. ? full_g : ASSUMED_FULL_G;
    if (left_g > 0. && left_g < spool.initial_g)
        spool.uses.push_back(Spools::Use{std::string(), spool.initial_g - left_g, false, now_ms});
}

std::vector<int> spools_for(Spools::Inventory &inventory, const std::string &printer, const std::vector<int> &filament_to_slot)
{
    std::vector<int> numbers;
    for (int slot : filament_to_slot) {
        const Spools::Spool *spool = spool_in(inventory, printer, slot);
        numbers.push_back(spool != nullptr ? spool->number : 0);
    }
    return numbers;
}

std::vector<Shortfall> shortfalls(Spools::Inventory &inventory, const std::string &printer, const std::vector<double> &grams,
                                  const std::vector<int> &filament_to_slot)
{
    std::map<int, double> needed;
    std::map<int, int>    first_filament;
    for (size_t i = 0; i < grams.size() && i < filament_to_slot.size(); ++i)
        if (grams[i] > 0. && filament_to_slot[i] >= 0) {
            needed[filament_to_slot[i]] += grams[i];
            first_filament.emplace(filament_to_slot[i], int(i));
        }
    std::vector<Shortfall> found;
    for (const auto &slot : needed)
        if (const Spools::Spool *spool = spool_in(inventory, printer, slot.first)) {
            const Spools::Remaining left = Spools::remaining(*spool);
            if (left.grams < slot.second)
                found.push_back({first_filament[slot.first], spool->number, slot.second, left.grams, left.measured});
        }
    return found;
}

} // namespace SpoolJobs
} // namespace Slic3r
