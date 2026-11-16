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
    // A job that never started printing took nothing.
    if (!completed && !job.start_sent)
        return 0;
    const double part = completed ? 1. : std::clamp(job.progress, 0., 1.);
    // By spool: the one noted when the job was sent, or, for a job without that note, the one in the slot now.
    std::map<int, double> by_spool;
    for (size_t i = 0; i < job.origin.filaments.size() && i < job.toolhead_map.size(); ++i) {
        if (!job.origin.filaments[i].used || job.origin.filaments[i].grams <= 0. || job.toolhead_map[i] < 0)
            continue;
        int number = 0;
        if (!job.spools.empty())
            number = i < job.spools.size() ? job.spools[i] : 0;
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
