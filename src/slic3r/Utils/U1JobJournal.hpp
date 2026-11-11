#ifndef slic3r_U1JobJournal_hpp_
#define slic3r_U1JobJournal_hpp_

#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace Slic3r {

/// Durable record of every job sent to a Snapmaker U1 over the LAN.
///
/// Each confirmed send gets an identity and one small JSON file under
/// data_dir()/u1_jobs. The record is written and synced to disk *before* the
/// Start is sent, so a crash or a lost reply can never lead to a second Start:
/// a job whose Start was sent but not confirmed keeps its printer held until
/// the printer's own state, or a person, settles what happened.
///
/// No wxWidgets or network dependency.
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
    std::string remote_path;     ///< name on the printer; carries the digest
    std::string message;
    std::uintmax_t bytes = 0;
    bool        start = false;
    bool        start_sent = false;
    bool        recovered_from_lost_ack = false;
    State       state = State::Uploading;
    std::vector<int> toolhead_map;
    long long   created_ms = 0;
    double      progress = 0.;
    /// State changes in order, with the time each was recorded.
    std::vector<std::pair<std::string, long long>> events;

    /// True while the Start may have run and nothing has settled it.
    bool unresolved() const { return start_sent && (state == State::Starting || state == State::StartUnconfirmed || state == State::Held); }
};

const char *state_name(State state);
std::string new_id();
long long   now_ms();

/// "host[:port]" from a URL or address, lower case, no scheme or path.
std::string printer_key(const std::string &address);
/// Same printer when the keys match, or the hosts match and one side has no port.
bool same_printer(const std::string &a, const std::string &b);

/// Name on the printer for *requested* with the file's identity in it.
std::string remote_name(const std::string &requested, const std::string &sha256);

/// Sets the state, appends it to the events and writes the record to disk
/// (temp file, sync, rename). Returns false when the record could not be made durable.
bool save(Job &job, State state, const std::string &message = {});

std::vector<Job> load_all();
/// Newest job sent to *printer* under *remote_path*.
std::optional<Job> find(const std::string &printer, const std::string &remote_path);
/// A job on *printer* whose Start outcome is still open.
std::optional<Job> unresolved(const std::string &printer);

} // namespace U1Jobs
} // namespace Slic3r

#endif // slic3r_U1JobJournal_hpp_
