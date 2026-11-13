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

/// Where a job came from: the project and plate that were sliced, and which state of the project.
struct Origin
{
    std::string file_name;          ///< the name the user gave the print, before the job's own suffix
    std::string project;            ///< path of the project file; empty for a project never saved
    long long   project_saved_ms = 0; ///< when that file was last written; 0 when there is none
    bool        project_changed = false; ///< the project had changes that were not in the file
    int         plate = 0;          ///< 1-based; 0 when unknown
    std::string plate_name;
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
    Origin      origin;
    /// The person's own verdict on the print, apart from what the machine reported:
    /// "success", "partial", "failed", or empty while not judged.
    std::string assessment;
    State       state = State::Uploading;
    std::vector<int> toolhead_map;
    long long   created_ms = 0;
    double      progress = 0.;
    /// When the print began and ended, by the printer's own job history. 0 while unknown: a time is
    /// never taken from this computer's clock.
    long long   started_ms = 0;
    long long   ended_ms = 0;
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
/// Jobs recorded as printing: the ones whose outcome is still to be read from the printer.
std::vector<Job> printing();

/// What a printer's own record says about a job.
struct PrinterRecord
{
    std::optional<State> outcome; ///< Completed, Cancelled or Failed; empty while the printer still shows it running
    long long started_ms = 0;     ///< 0 when the record gives no time
    long long ended_ms   = 0;
};
/// Reads *status* (Moonraker print_stats, as U1LanPrintHost queries it). An outcome is returned only when
/// the printer shows this job's own file as complete, cancelled or failed. Another file, no file or a
/// state it does not name gives nothing: the job's outcome is then unknown, not assumed.
std::optional<State> outcome_from_status(const Job &job, const nlohmann::json &status);
/// Finds this job in Moonraker's job history (the "result" of server/history/list) by its file name,
/// which is unique to the job. Empty when the history does not list it.
std::optional<PrinterRecord> record_from_history(const Job &job, const nlohmann::json &history);
/// Writes what the printer's record says into job *id*, under the journal's lock. Only a job still
/// recorded as printing takes an outcome; times are filled in when they are not known yet.
/// Returns the record when something was written.
std::optional<Job> record_outcome(const std::string &id, const PrinterRecord &record, const std::string &message);

/// Records the person's verdict ("success", "partial", "failed", or empty to clear it) on job *id*.
/// The job's state, which is the machine's, is not touched. Any other text is refused.
std::optional<Job> set_assessment(const std::string &id, const std::string &assessment);
/// Every job sent to *printer*, newest first.
std::vector<Job> history(const std::string &printer);

/// A print the printer's own history lists that no job of this app accounts for: started at the
/// printer's screen, from its web interface or from another program.
struct ExternalPrint
{
    std::string printer_job_id; ///< the printer's own id for the entry
    std::string file_name;
    std::string status;         ///< the printer's word: in_progress, completed, cancelled, error, ...
    long long   started_ms = 0;
    long long   ended_ms   = 0;
    std::string assessment;     ///< the person's verdict, as on Job
};
/// The entries of *history* (the "result" of server/history/list) that are not a file of any of *jobs*.
std::vector<ExternalPrint> external_from_history(const nlohmann::json &history, const std::vector<Job> &jobs);
/// Keeps *seen* for *printer* on disk, so such prints stay listed after the printer forgets them.
/// What the printer says replaces what was stored; a verdict already given is kept.
bool remember_external(const std::string &printer, const std::vector<ExternalPrint> &seen);
/// Every remembered outside print of *printer*, newest first.
std::vector<ExternalPrint> external_prints(const std::string &printer);
bool set_external_assessment(const std::string &printer, const std::string &printer_job_id, const std::string &assessment);

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
