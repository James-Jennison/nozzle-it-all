#ifndef slic3r_NiaBridgePrintHost_hpp_
#define slic3r_NiaBridgePrintHost_hpp_

#include "PrintHost.hpp"
#include "libslic3r/U1PrintTask.hpp"

#include <nlohmann/json.hpp>

#include <optional>
#include <string>

namespace Slic3r {

/// Prototype: hands a confirmed Snapmaker U1 job to the local NIA bridge
/// instead of talking to the printer directly.
///
/// The bridge is a separate process on 127.0.0.1 that runs NIA's Kotlin
/// printer modules. It stores the exact prepared file by SHA-256, checks the
/// printer is ready, uploads, verifies the uploaded bytes and starts the print
/// at most once per *intent_id*. When the Start acknowledgment is lost it never
/// resends: it reads the printer's state and either binds the running job or
/// holds the printer until that is resolved.
///
/// Only used when the NIA_BRIDGE_URL environment variable is set, so a normal
/// run is unaffected.
class NiaBridgePrintHost : public PrintHost
{
public:
    /// Bridge root from NIA_BRIDGE_URL (e.g. "http://127.0.0.1:47811"), empty when unset.
    static std::string bridge_url();

    /// The bridge's record of the job printing *filename* on *printer_address*,
    /// or nullopt when the bridge does not know it (blocking, short timeout).
    static std::optional<nlohmann::json> lookup_job(const std::string &printer_address, const std::string &filename);

    /// *printer_address* is the Moonraker root the send dialog already reached;
    /// *intent_id* is a UUID minted once per confirmed Print dialog.
    NiaBridgePrintHost(std::string bridge_url, std::string printer_address, std::string intent_id, U1PrintTask::Options options);
    ~NiaBridgePrintHost() override = default;

    const char *get_name() const override { return "Snapmaker U1 (NIA bridge)"; }
    bool        test(wxString &curl_msg) const override;
    wxString    get_test_ok_msg() const override;
    wxString    get_test_failed_msg(wxString &msg) const override;
    bool upload(PrintHostUpload upload_data, ProgressFn progress_fn, ErrorFn error_fn, InfoFn info_fn) const override;
    bool has_auto_discovery() const override { return false; }
    bool can_test() const override { return true; }
    PrintHostPostUploadActions get_post_upload_actions() const override { return PrintHostPostUploadAction::StartPrint; }
    std::string get_host() const override { return m_printer_address; }

private:
    std::string          m_bridge_url;
    std::string          m_printer_address;
    std::string          m_intent_id;
    U1PrintTask::Options m_options;
};

} // namespace Slic3r

#endif // slic3r_NiaBridgePrintHost_hpp_
