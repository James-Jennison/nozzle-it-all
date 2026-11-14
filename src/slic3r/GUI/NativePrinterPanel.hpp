#ifndef slic3r_NativePrinterPanel_hpp_
#define slic3r_NativePrinterPanel_hpp_

#include "libslic3r/PrinterSnapshot.hpp"
#include "slic3r/Utils/MoonrakerLive.hpp"

#include <wx/panel.h>
#include <wx/timer.h>

#include <atomic>
#include <chrono>
#include <functional>
#include <memory>
#include <string>
#include <vector>

class wxBoxSizer;
class wxButton;
class wxFlexGridSizer;
class wxGauge;
class wxSlider;
class wxStaticText;
class wxToggleButton;
class Button;
class ComboBox;
class SpinInput;

namespace Slic3r::GUI {

/// The printer's LAN address for the selected printer preset: the
/// "print_host" typed into the printer settings (physical printer first).
/// Empty when unknown or not a private LAN address. Returns a host, not a URL.
std::string resolve_printer_lan_host();

/// The Printer tab can show a printer other than the one selected for slicing (opened from the Printers side).
/// *host* as resolve_printer_lan_host() gives it; an empty host goes back to the selected printer.
/// What follows the printer shown: everything on the Monitor side (the status, Pause, Stop print, Resume and
/// the Controls card go to the printer whose name the page shows, with "not the printer selected for
/// slicing" beside it) and the History side (that printer's jobs; Print again sends a job to the printer the
/// job was sent to, which is the one shown). What never follows it: Print and Send from the slicer and the
/// filament sync, which use the printer selected for slicing. Main thread only.
void view_printer(const std::string &host, const std::string &name);
/// The address the Printer tab's Monitor and History show: the printer set with view_printer(), else the selected one.
std::string viewed_printer_lan_host();
/// The name given to view_printer(); empty while the selected printer is shown.
std::string viewed_printer_name();

/// Native "monitor first, intervene second" printer page: live camera and the
/// current job on the left, telemetry, quick controls and materials on the
/// right. Sections are driven by what the printer reports, so it adapts to
/// any Moonraker printer; narrow windows stack into one column.
class NativePrinterPanel final : public wxPanel
{
public:
    /// Called with (true, lan_host, webcam) when the camera should run and
    /// (false, ...) when it should stop; the owner supplies the camera view.
    using CameraFn = std::function<void(bool active, const std::string &lan_host, const PrinterPanel::Webcam &webcam)>;

    NativePrinterPanel(wxWindow *parent, CameraFn camera_fn);
    ~NativePrinterPanel() override;

    /// Parent for the camera view the owner creates.
    wxWindow *camera_host() const { return m_camera_host; }

    /// Connects while active (the page is visible), disconnects when not.
    void set_active(bool active);
    void on_sys_color_changed();

private:
    struct Palette;
    Palette palette() const;

    void build();
    void connect();
    void disconnect();
    void set_compact(bool compact);

    void on_link(MoonrakerLiveClient::Link link, const std::string &detail);
    void on_capabilities(const PrinterPanel::Capabilities &caps);
    void on_snapshot(const PrinterPanel::Snapshot &snapshot);
    void rebuild_controls();
    void rebuild_materials();
    void show_adjust_dialog();
    void show_toolhead_dialog(size_t index);
    void confirm_stop();
    /// Asks, then sends one guarded Resume for the print the button was shown for.
    void confirm_resume();
    /// After a Resume the printer took or may have taken: none is offered again for RESUME_HOLD_S while it still says paused.
    void hold_resume();
    void resume_finished(const MoonrakerLiveClient::ResumeResult &result, const std::string &printer, const std::string &shown_file);
    void report_error(const std::string &what, const std::string &error);
    /// Motion, Temperature and Filament: one card, each control disabled with its reason when it must not be sent.
    void build_actions(wxWindow *card, wxBoxSizer *body);
    void refresh_actions();
    U1Controls::PrinterFacts printer_facts() const;
    /// Judges, asks as much as the risk calls for, judges again, then sends the control once.
    void run_action(U1Controls::Action action);
    void action_finished(U1Controls::Action action, MoonrakerLiveClient::Sent sent, const std::string &detail);
    /// Marks the fault now shown as seen. Sends nothing to the printer, so it can never resume a print.
    void acknowledge_fault();
    void apply_colors();
    /// Shows which sent job (U1Jobs) the printer's current file is, and records its outcome.
    void refresh_job_identity(const PrinterPanel::Snapshot &snapshot);

    // Defers a callback from the client thread to the GUI thread, dropped if
    // this panel or its client is gone by then.
    template<class F> void post(F &&fn);

    CameraFn                             m_camera_fn;
    std::unique_ptr<MoonrakerLiveClient> m_client;
    std::shared_ptr<std::atomic<NativePrinterPanel *>> m_lifetime;
    std::atomic<std::uint64_t>           m_client_generation{0};
    bool                                 m_active            = false;
    bool                                 m_compact           = false;
    bool                                 m_camera_running    = false;
    std::string                          m_host;
    std::string                          m_identity_file;       ///< file the identity line describes
    PrinterPanel::JobState               m_identity_state = PrinterPanel::JobState::Standby;

    PrinterPanel::Capabilities m_caps;
    PrinterPanel::Snapshot     m_snapshot;

    // Layout
    wxBoxSizer *m_columns     = nullptr;
    wxBoxSizer *m_left        = nullptr;
    wxBoxSizer *m_right       = nullptr;
    wxWindow   *m_camera_card = nullptr;
    wxWindow   *m_job_card    = nullptr;
    wxWindow   *m_camera_host = nullptr;

    MoonrakerLiveClient::Link m_link = MoonrakerLiveClient::Link::Connecting;

    // Header
    wxStaticText *m_printer_name = nullptr;
    wxStaticText *m_link_label   = nullptr;
    wxStaticText *m_health_label = nullptr;
    ::Button     *m_ack_button   = nullptr; ///< shown while a fault is shown that has not been acknowledged
    wxString      m_acknowledged_fault;     ///< the fault text last acknowledged; empty when none

    // Job
    wxStaticText *m_job_name      = nullptr;
    wxStaticText *m_job_detail    = nullptr;
    wxStaticText *m_job_identity  = nullptr; ///< the sent job (U1Jobs) this file belongs to
    wxStaticText *m_job_percent   = nullptr;
    wxGauge      *m_job_progress  = nullptr;
    wxStaticText *m_job_remaining = nullptr;
    wxStaticText *m_job_finishes  = nullptr;
    wxStaticText *m_job_layer     = nullptr;
    wxButton     *m_pause_button  = nullptr;
    bool          m_pause_button_resumes = false; ///< what the Pause / Resume button does when pressed, as labelled
    bool          m_resume_in_flight     = false; ///< a Resume is being checked or sent; none other may start
    std::chrono::steady_clock::time_point m_resume_hold_until{}; ///< no Resume is offered again before this
    wxTimer       m_resume_hold_timer;            ///< redraws the button when that hold ends
    static constexpr int RESUME_HOLD_S = 10;
    wxButton     *m_adjust_button = nullptr;
    wxButton     *m_stop_button   = nullptr;

    // Telemetry
    wxStaticText *m_nozzle_value  = nullptr;
    wxStaticText *m_nozzle_label  = nullptr;
    wxStaticText *m_bed_value     = nullptr;
    wxStaticText *m_chamber_value = nullptr;
    wxStaticText *m_speed_value   = nullptr;
    wxStaticText *m_flow_value    = nullptr;

    // Controls and materials, rebuilt when capabilities change
    wxWindow        *m_controls_card = nullptr;
    wxFlexGridSizer *m_controls_grid = nullptr;
    wxWindow        *m_material_card = nullptr;
    wxBoxSizer      *m_material_row  = nullptr;
    wxStaticText    *m_material_note = nullptr;

    // Motion, Temperature, Filament
    struct ActionButton
    {
        U1Controls::Action action;
        ::Button          *button = nullptr;
    };
    std::vector<ActionButton> m_action_buttons;
    wxWindow     *m_actions_card       = nullptr;
    wxStaticText *m_group_reason[3]    = {nullptr, nullptr, nullptr}; ///< by U1Controls::Group
    ::ComboBox   *m_nozzle_choice      = nullptr;
    ::SpinInput  *m_nozzle_target      = nullptr;
    ::SpinInput  *m_bed_target         = nullptr;
    bool          m_action_in_flight   = false; ///< a confirmed control is with the printer; none other is offered meanwhile

    struct FanRow
    {
        PrinterPanel::Control control;
        bool                  part_fan = false;
        wxSlider             *slider   = nullptr;
        wxStaticText         *value    = nullptr;
        long                  hold_until_ms = 0; ///< ignore echoes right after a user change
    };
    std::vector<FanRow> m_fan_rows;
    struct LightRow
    {
        PrinterPanel::Control control;
        wxToggleButton       *toggle = nullptr;
    };
    std::vector<LightRow> m_light_rows;
    struct MaterialTile
    {
        wxWindow     *panel = nullptr;
        wxWindow     *swatch = nullptr;
        wxStaticText *name  = nullptr;
        wxStaticText *detail = nullptr;
    };
    std::vector<MaterialTile> m_material_tiles;
};

} // namespace Slic3r::GUI

#endif // slic3r_NativePrinterPanel_hpp_
