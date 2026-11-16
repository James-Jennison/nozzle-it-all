#ifndef slic3r_SpoolJobs_hpp_
#define slic3r_SpoolJobs_hpp_

#include "U1JobJournal.hpp"
#include "libslic3r/SpoolInventory.hpp"

#include <string>
#include <vector>

namespace Slic3r {

/// Where the job journal and the spool inventory meet: what a job took from the spools the person said were in
/// the printer, and what a print would need of them. No network and no wxWidgets, so it is unit-tested.
namespace SpoolJobs {

/// The spool the person said is in *slot* of the printer a job names (the same printer whether it is written
/// with its web port, Moonraker's own or none; see U1Jobs::same_printer).
Spools::Spool *spool_in(Spools::Inventory &inventory, const std::string &printer, int slot);

/// Records what *job* took: the slicer's grams for each filament the job printed, from the spool noted for that
/// filament when the job was sent (Job::spools) or, for a job recorded without that note, from the spool said
/// to be in the filament's toolhead or slot now. A job that ended before its end counts for the part of its
/// filament the printer's history says was fed (Job::fed_part), and for nothing while that is not known. Only a job that has ended (completed, cancelled or failed) is recorded, and
/// a job is counted once however often this is called. Returns how many spools were written to.
int record_job(Spools::Inventory &inventory, const U1Jobs::Job &job, long long now_ms);

/// Per filament of a job about to be sent to *printer* with each filament printing from *filament_to_slot*: the
/// number of the spool the person has recorded in that slot now, 0 where there is none. Kept in the job's record
/// (Job::spools), so that what the job takes goes to the spools that were there when it was sent, wherever they
/// are when it ends.
std::vector<int> spools_for(Spools::Inventory &inventory, const std::string &printer, const std::vector<int> &filament_to_slot);

/// What a spool is taken to hold when no figure is known for it.
constexpr double ASSUMED_FULL_G = 1000.;
/// Sets what *spool*, newly added from a printer's report, holds: *full_g* when new (a tag's figure or the
/// printer's; ASSUMED_FULL_G when it is 0) and, where the printer counts *left_g* as on it now (0 for unknown),
/// the difference as one use without a job. That figure is the printer's own estimate and stays shown as one.
void set_reported_weight(Spools::Spool &spool, double full_g, double left_g, long long now_ms);

/// A spool that, by the inventory, does not hold what a print would take from it.
struct Shortfall
{
    int    filament = 0; ///< 0-based
    int    spool    = 0; ///< the spool's number
    double needed_g = 0.;
    double left_g   = 0.;
    bool   measured = false; ///< what is left is a weighing, not an estimate
};
/// For a print of *grams* per filament on *printer* with each filament printing from *filament_to_slot*: the
/// spools said to be in those slots that hold less than the print takes from them (several filaments from one
/// slot are added up). A slot with no spool recorded gives nothing: the inventory only knows what it was told.
std::vector<Shortfall> shortfalls(Spools::Inventory &inventory, const std::string &printer, const std::vector<double> &grams,
                                  const std::vector<int> &filament_to_slot);

} // namespace SpoolJobs
} // namespace Slic3r

#endif // slic3r_SpoolJobs_hpp_
