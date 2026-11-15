#ifndef slic3r_GUI_NativeDevicePage_hpp_
#define slic3r_GUI_NativeDevicePage_hpp_

#include <atomic>
#include <cstddef>
#include <functional>
#include <memory>
#include <string>

#include <wx/panel.h>

class Button;
class wxStaticText;

namespace Slic3r::GUI {

class DirectLanPage;
class NativePrinterPanel;
class U1JobHistoryPanel;
class FleetPanel;

/// "Printer" tab for Moonraker/Klipper printers: the native monitor-first printer
/// panel (job, telemetry, quick controls, materials) with a live camera view. It
/// connects while the tab is visible and disconnects when it is hidden.
///
/// Where the Device and Printer tabs are one tab, the page also hosts the printer's own web
/// interface (the page Orca shows on its Device tab) behind a switch above the panel.
class NativeDevicePage final : public wxPanel
{
public:
    explicit NativeDevicePage(wxWindow* parent);
    ~NativeDevicePage() override;

    /// Reconnect to the selected printer's address (call when the printer preset changes). A printer opened
    /// from the Printers side is left: the page follows the selection again.
    void reload();
    void set_active(bool active);
    void on_sys_color_changed();
    void msw_rescale();

    /// Takes the page showing the printer's web interface as a child and shows the switch.
    /// `before_show` runs each time the web interface is about to be shown.
    void host_web_page(wxWindow* page, std::function<void()> before_show);
    /// Gives the hosted page up, hidden and out of this page's sizer; the caller re-parents it.
    wxWindow* release_web_page();
    wxWindow* web_page() const { return m_web_page; }
    /// Switches between the native panel (false) and the hosted web interface (true).
    void show_web(bool web);
    /// The three sides of the page. The switch above them is always shown; its Web interface
    /// button only while a web page is hosted.
    enum class View { Monitor, History, Printers, Web };
    void show_view(View view);

private:
    NativePrinterPanel* m_panel{nullptr};
    DirectLanPage*      m_camera{nullptr};
    std::size_t         m_camera_generation{0};
    bool                m_active{false};

    void apply_switch_state();
    /// A preset of the "Octo/Klipper" connection type may be a real OctoPrint server, which answers none of
    /// the Monitor side's reads. The selected printer is asked once what it is; when it answers and is no
    /// Klipper (Moonraker) printer, the page says so and shows its own web interface first.
    void ask_server_kind();
    void apply_server_kind();
    wxStaticText*                      m_notice{nullptr};
    std::shared_ptr<std::atomic<bool>> m_alive{std::make_shared<std::atomic<bool>>(true)};
    bool                               m_web_first_done{false}; ///< the web side was shown first once for this selection
    /// Reconnects Monitor and History to the printer now viewed.
    void refresh();

    wxPanel*              m_switch_bar{nullptr};
    ::Button*             m_btn_monitor{nullptr};
    ::Button*             m_btn_history{nullptr};
    ::Button*             m_btn_web{nullptr};
    wxWindow*             m_web_page{nullptr};
    std::function<void()> m_before_web_show;
    bool                  m_web{false};
    U1JobHistoryPanel*    m_history{nullptr};
    bool                  m_history_shown{false};
    ::Button*             m_btn_printers{nullptr};
    FleetPanel*           m_fleet{nullptr};
    bool                  m_fleet_shown{false};
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_NativeDevicePage_hpp_
