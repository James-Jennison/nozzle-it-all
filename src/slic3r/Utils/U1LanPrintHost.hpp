#ifndef slic3r_U1LanPrintHost_hpp_
#define slic3r_U1LanPrintHost_hpp_

#include "PrintHost.hpp"
#include "U1JobJournal.hpp"
#include "libslic3r/U1PrintTask.hpp"

#include <utility>
#include <nlohmann/json.hpp>

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace Slic3r {

/// Sends a job straight to a Snapmaker U1's Moonraker over the LAN: plain
/// HTTP upload, then server.files.start_local_print (websocket JSON-RPC) with the filament ->
/// toolhead map and print options. No Flutter, MQTT or cloud involved, and no
/// overall time limit on the upload, so large multi-color jobs are fine.
///
/// A send is one job with an identity (U1Jobs). The upload is read back and
/// compared by SHA-256 before a Start is offered. The Start is recorded on disk
/// before it is sent, sent once and never repeated: when its reply is lost the
/// printer's state decides whether the job is running, and otherwise the
/// printer stays held until a person says what happened.
///
/// The same send serves a Klipper printer whose filament comes from AFC lanes (Target::AfcLanes: a Centauri
/// Carbon on COSMOS with a CANVAS). Such a printer has no firmware map: the tool numbers in the file are
/// rewritten before the upload (GCodeToolRemap), the rewritten file is the job's file, and the Start is
/// Moonraker's own printer.print.start.
class U1LanPrintHost : public PrintHost
{
public:
    enum class Target { SnapmakerU1, AfcLanes };

    /// *base_url* is the Moonraker root, e.g. "http://192.168.1.113".
    U1LanPrintHost(std::string base_url, U1PrintTask::Options options, Target target = Target::SnapmakerU1);
    /// For Target::AfcLanes: the model the selected printer preset names; a file sliced for another is refused.
    void set_printer_model(std::string model) { m_printer_model = std::move(model); }
    ~U1LanPrintHost() override = default;

    /// The project and plate this send comes from; written into the job's record.
    void set_origin(U1Jobs::Origin origin) { m_origin = std::move(origin); }
    /// For a file sent again: the SHA-256 the prepared file must have. upload() refuses any other bytes
    /// before anything is sent.
    void set_expected_sha256(std::string sha256) { m_expected_sha256 = std::move(sha256); }

    /// Reads print_task_config from the printer. Tries *host* as given (port 80
    /// through the printer's web proxy), then Moonraker's own port 7125. On
    /// success returns the parsed state and sets *base_url* to the root that
    /// answered; on failure returns nullopt and sets *error*.
    static std::optional<U1PrintTask::PrinterState> query_printer(const std::string &host, std::string &base_url, wxString &error);

    /// Reads the AFC lanes of a Klipper printer as the send dialog's sources, indexed by tool number. Null
    /// when the printer does not answer or has no AFC; *error* is set only when it did not answer.
    static std::optional<U1PrintTask::PrinterState> query_afc_printer(const std::string &host, std::string &base_url, wxString &error);

    /// Reads the loaded filaments and nozzles for the slicer's Sync Filament
    /// flow (see U1PrintTask::build_filament_report). *host* as in query_printer.
    static std::optional<nlohmann::json> query_filament_report(const std::string &host, wxString &error);

    const char *get_name() const override { return m_target == Target::AfcLanes ? "Klipper with AFC (LAN)" : "Snapmaker U1 (LAN)"; }
    bool        test(wxString &curl_msg) const override;
    wxString    get_test_ok_msg() const override;
    wxString    get_test_failed_msg(wxString &msg) const override;
    bool upload(PrintHostUpload upload_data, ProgressFn progress_fn, ErrorFn error_fn, InfoFn info_fn) const override;
    bool has_auto_discovery() const override { return false; }
    bool can_test() const override { return true; }
    PrintHostPostUploadActions get_post_upload_actions() const override
    {
        return PrintHostPostUploadAction::StartPrint;
    }
    std::string get_host() const override { return m_base_url; }

    /// Reads the printer once for a job whose Start outcome is open. Marks the
    /// job Printing when the printer is running this job's own file. Returns the
    /// printer's print state ("printing", "standby", ...), empty when unreachable.
    static std::string reconcile(U1Jobs::Job &job, const std::string &base_url);
    /// The refusal as a sentence for the user.
    static wxString    describe(const U1Jobs::Blocker &blocker, Target target = Target::SnapmakerU1);
    /// Reads the printer once and returns a check for the send dialog: why a file with these *facts* must not
    /// be sent (and, with the second argument true, started) with a given filament -> toolhead map; empty when
    /// nothing stands in the way. It judges the state read here, so it only informs: upload() reads the
    /// printer again and decides for itself.
    struct ReadinessRead
    {
        std::function<wxString(const std::vector<int> &, bool)> check;
        bool answered = false; ///< the printer returned a status with its print state
    };
    static ReadinessRead readiness_check(const std::string &base_url, U1Jobs::GcodeFacts facts, Target target = Target::SnapmakerU1,
                                         std::string printer_model = {});

private:
    enum class StartOutcome {
        Accepted,
        Rejected, ///< the printer answered and refused
        NotSent,  ///< the request never left this computer
        Unknown   ///< sent; it may or may not have run
    };
    /// Sends the Start once.
    StartOutcome start_print(const std::string &path, wxString &detail) const;
    /// SHA-256 of *path* as stored on the printer, fetched in ranges. False when it could not be read whole.
    bool         read_back_sha256(const std::string &path, std::uintmax_t expected_bytes, std::string &sha256) const;

    /// The printer's status as the start rules of this target read it; null when it does not answer.
    nlohmann::json       status() const;
    U1Jobs::Blocker      blocker(const U1Jobs::GcodeFacts &facts, const nlohmann::json &status, bool start) const;

    std::string          m_base_url;
    U1PrintTask::Options m_options;
    Target               m_target = Target::SnapmakerU1;
    std::string          m_printer_model;
    U1Jobs::Origin       m_origin;
    std::string          m_expected_sha256;
};

} // namespace Slic3r

#endif // slic3r_U1LanPrintHost_hpp_
