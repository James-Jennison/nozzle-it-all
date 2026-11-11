#ifndef slic3r_U1JobJournal_hpp_
#define slic3r_U1JobJournal_hpp_

#include <nlohmann/json.hpp>

#include <boost/filesystem/path.hpp>

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace Slic3r {

/// Durable record of every job sent to a Snapmaker U1 over the LAN, and the
/// rules that decide whether a job may be sent or started.
///
/// Each confirmed send gets an identity and one small JSON file under
/// data_dir()/u1_jobs. The record is written and synced to disk *before* the
/// Start is sent, so a crash or a lost reply can never lead to a second Start:
/// a job whose Start was sent but not confirmed keeps its printer held until
/// the printer's own state, or a person, settles what happened.
///
/// No wxWidgets or network dependency, so it is unit-tested directly.
namespace U1Jobs {

enum class State {
    Uploading,
    Verifying,        ///< reading the file back from the printer
    Starting,         ///< Start recorded as sent, reply pending
    StartUnconfirmed, ///< reply lost; reconciling against the printer
    Held,             ///< reply lost and the printer does not show this job; needs a person
    Printing,
    Uploaded,         ///< verified on the printer, no Start requested
    Completed,
    Cancelled,
    Failed
};

struct Job
{
    std::string id;              ///< UUID minted when the send is confirmed
    std::string printer;         ///< host[:port] of the printer it was sent to
    std::string sha256;          ///< of the prepared file
    std::string uploaded_sha256; ///< of the file read back from the printer
    std::string remote_path;     ///< name on the printer; unique to this job
    std::string message;
    std::uintmax_t bytes = 0;
    bool        start = false;
    bool        start_sent = false;
    bool        recovered_from_lost_ack = false;
    State       state = State::Uploading;
    std::vector<int> toolhead_map;
    long long   created_ms = 0;
    double      progress = 0.;
    /// State changes and decisions in order, with the time each was recorded.
    std::vector<std::pair<std::string, long long>> events;

    /// True while the Start may have run and nothing has settled it.
    bool unresolved() const { return start_sent && (state == State::Starting || state == State::StartUnconfirmed || state == State::Held); }
};

const char *state_name(State state);
std::string new_id();
long long   now_ms();

/// Where the records live. Tests point this at a scratch folder; empty restores data_dir()/u1_jobs.
void set_journal_dir_for_tests(const boost::filesystem::path &dir);

/// "host[:port]" from a URL or address, lower case, no scheme or path.
std::string printer_key(const std::string &address);
/// Same printer when the keys match, or the hosts match and both ports are among
/// "none", 80 and 7125: a U1 answers on its web proxy and on Moonraker's own port.
bool same_printer(const std::string &a, const std::string &b);

/// Name on the printer for *requested*: it carries the file's digest and the
/// job id, so no other job, earlier or later, can have the same name.
std::string remote_name(const std::string &requested, const std::string &sha256, const std::string &job_id);
/// True when *reported* (print_stats.filename) is this job's file. Moonraker
/// may report the path with or without its folder.
bool        is_job_file(const Job &job, const std::string &reported);

/// Sets the state, appends it to the events and writes the record to disk
/// (unique temp file, sync, rename, directory sync). On failure *job* is left
/// unchanged and false is returned: memory never runs ahead of the disk.
bool save(Job &job, State state, const std::string &message = {});

/// Re-reads job *id* from disk, lets *change* edit it, and saves it, all under
/// one lock, so two threads cannot overwrite each other's update. *change*
/// returns the new state, or nullopt to leave the record alone.
std::optional<Job> update(const std::string &id, const std::function<std::optional<State>(Job &)> &change, const std::string &message = {},
                          const std::string &event = {});

/// All readable records, oldest first. Files in the folder that look like
/// records but cannot be trusted are listed in *unreadable*, never guessed at.
std::vector<Job> load_all(std::vector<std::string> *unreadable = nullptr);
/// Newest job sent to *printer* whose file *reported_filename* is.
std::optional<Job> find(const std::string &printer, const std::string &reported_filename);
/// A job on *printer* whose Start outcome is still open.
std::optional<Job> unresolved(const std::string &printer);

/// One send per printer at a time, across threads and across running copies
/// of the app. Held from before the first check until the job is settled.
class PrinterLock
{
public:
    explicit PrinterLock(const std::string &printer);
    ~PrinterLock();
    PrinterLock(const PrinterLock &)            = delete;
    PrinterLock &operator=(const PrinterLock &) = delete;
    bool acquired() const { return m_acquired; }

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
    std::string           m_key;
    bool                  m_acquired = false;
};
/// True while this process holds the lock for *printer*.
bool sending_now(const std::string &printer);

// ---------------------------------------------------------------------------
// Readiness

/// What the prepared G-code says it was sliced for (Orca writes its settings as trailing comments).
struct GcodeFacts
{
    std::string         printer_model;
    std::vector<double> nozzles; ///< per extruder
};
GcodeFacts read_gcode_facts(const boost::filesystem::path &file);

struct Blocker
{
    enum class Kind {
        None,
        WrongModel,         ///< detail = the model the file was sliced for
        PrinterUnreachable, ///< no usable status
        PrinterNotIdle,     ///< detail = the printer's print state
        ToolheadUnknown,    ///< the printer does not say whether the toolhead is loaded
        ToolheadEmpty,
        NozzleUnknown,      ///< the printer does not report the toolhead's nozzle
        NozzleMismatch
    };
    Kind        kind = Kind::None;
    std::string detail;
    int         filament = -1; ///< 0-based
    int         toolhead = -1; ///< 0-based
    double      sliced   = 0.;
    double      fitted   = 0.;
    explicit operator bool() const { return kind != Kind::None; }
};

/// Why this file must not be sent to (and, with *start*, started on) the
/// printer whose Moonraker status is *status* (print_stats, print_task_config,
/// extruder..extruder3). Anything a Start depends on that the printer does not
/// report is a refusal, never a pass.
Blocker start_blocker(const GcodeFacts &facts, const nlohmann::json &status, const std::vector<int> &filament_to_toolhead, bool start);

} // namespace U1Jobs
} // namespace Slic3r

#endif // slic3r_U1JobJournal_hpp_
