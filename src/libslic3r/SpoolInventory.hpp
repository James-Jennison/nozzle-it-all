#ifndef slic3r_SpoolInventory_hpp_
#define slic3r_SpoolInventory_hpp_

#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace Slic3r {

/// The person's spools, kept beside the filament presets: what each is, how much is left, where it is and
/// when it was dried. Everything here is a record the person keeps or confirms. It produces warnings only:
/// nothing in it decides whether a print may be sent or started. Kept in one file in the data folder.
namespace Spools {

/// Where a spool is. A place is what the person said, never inferred from a sensor or a reading.
struct Location
{
    enum class Kind { Storage, Dryer, Printer };
    Kind        kind = Kind::Storage;
    std::string place;      ///< storage or dryer: the person's own words ("shelf 2", "S4 left"); printer: host[:port]
    int         slot = -1;  ///< printer: 0-based toolhead or slot; -1 otherwise
    bool operator==(const Location &other) const { return kind == other.kind && place == other.place && slot == other.slot; }
};

/// Filament a job took from a spool.
struct Use
{
    std::string job_id;        ///< the job's id in the job journal; empty for an entry made by hand
    double      grams    = 0.;
    bool        measured = false; ///< false: the slicer's estimate, scaled by how far the print got
    long long   at_ms    = 0;
};

/// The spool put on a scale: what is left on it, by the person's own measurement.
struct Weighing
{
    double    remaining_g = 0.;
    long long at_ms       = 0;
};

/// A drying session the person recorded. It says the spool was in a dryer, not that the filament is dry.
struct Drying
{
    long long   start_ms = 0;
    long long   end_ms   = 0; ///< 0 while it is still going
    std::string place;
    std::string notes;
};

struct Spool
{
    int                      number = 0; ///< the app's own number for the spool; never reused
    std::string              name;       ///< what the person calls it; by default brand and material
    std::string              vendor;
    std::string              material;
    std::string              color;      ///< "#RRGGBB", or empty
    double                   initial_g = 0.; ///< filament on the spool when it was added
    std::vector<std::string> tags;       ///< tag identities read for this spool (SpoolTags::normalized form)
    Location                 location;
    std::vector<Use>         uses;
    std::vector<Weighing>    weighings;
    std::vector<Drying>      drying;
    std::string              notes;
    bool                     archived = false; ///< used up or given away: kept for the record, not listed
    long long                created_ms = 0;
};

/// How much is left on a spool, and how that is known.
struct Remaining
{
    double grams    = 0.;
    /// True when the figure is the last weighing itself: nothing was taken from the spool since.
    bool   measured = false;
};
/// The last weighing less every use recorded after it; without a weighing, the initial amount less every use.
/// Never below zero.
Remaining remaining(const Spool &spool);

/// The drying session still going, if any.
const Drying *drying_now(const Spool &spool);

class Inventory
{
public:
    /// Reads *path*. A missing file is an empty inventory. A file that is there and cannot be read is not: then
    /// *damaged* is set, and the caller must not save over it without the person knowing.
    static Inventory load(const std::string &path, bool *damaged = nullptr);
    /// Writes to *path* through a temporary file and a rename.
    bool save(const std::string &path) const;

    const std::vector<Spool> &spools() const { return m_spools; }
    Spool       *find(int number);
    const Spool *find(int number) const;
    /// The spool the person said is in this printer's slot; archived spools are not found.
    const Spool *at(const std::string &printer, int slot) const;
    Spool       *at(const std::string &printer, int slot);
    /// The spool that carries this tag identity; archived spools are not found.
    const Spool *with_tag(const std::string &tag_id) const;

    /// Adds a spool and gives it the next number. Returns the number.
    int add(Spool spool, long long now_ms);
    /// Moves a spool. A printer slot holds one spool: the spool that was said to be there goes to storage
    /// (place "unplaced"), and its number is returned; 0 when the slot was free or the spool is not known.
    int move(int number, const Location &to);
    /// Records what a job took. The same job is recorded once per spool: a second call replaces the first.
    bool record_use(int number, const Use &use);
    bool weigh(int number, double remaining_g, long long now_ms);
    bool start_drying(int number, const std::string &place, long long now_ms);
    bool end_drying(int number, const std::string &notes, long long now_ms);

private:
    std::vector<Spool> m_spools;
    int                m_next_number = 1;
};

/// Where the inventory is kept: data_dir()/spools.json.
std::string inventory_path();

/// What became of a change().
enum class Changed {
    Saved,
    NotChanged, ///< *change* returned false; nothing was written
    Damaged,    ///< the file is there and cannot be read; nothing was written
    NotSaved    ///< the file could not be locked or written
};
/// Reads the inventory at *path*, lets *change* edit it and writes it back, all while holding a lock on the
/// file that other threads and other running copies of the app wait for: no change made in between is lost.
/// *change* returns false to leave the file as it is.
Changed change(const std::string &path, const std::function<bool(Inventory &)> &change);

} // namespace Spools
} // namespace Slic3r

#endif // slic3r_SpoolInventory_hpp_
