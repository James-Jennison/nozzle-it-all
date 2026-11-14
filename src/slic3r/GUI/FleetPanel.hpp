#ifndef slic3r_GUI_FleetPanel_hpp_
#define slic3r_GUI_FleetPanel_hpp_

#include "slic3r/Utils/FleetStatus.hpp"

#include <wx/image.h>
#include <wx/scrolwin.h>
#include <wx/timer.h>

#include <atomic>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <vector>

class wxBoxSizer;
class wxStaticBitmap;
class wxStaticText;
class Button;

namespace Slic3r::GUI {

/// The Printers side of the Printer tab: one card for every printer preset that has an address, sorted into
/// Needs attention, Printing, Ready and Offline by what each printer answers (Fleet::classify), with a filter
/// per group. While it shows, every printer is read every few seconds on a worker thread this panel owns:
/// one read request each, never a command, and where the printer lists a camera one still picture from it,
/// shown small on the card. Opening a card shows that printer on the Monitor side; it does
/// not change the printer selected for slicing.
class FleetPanel final : public wxScrolledWindow
{
public:
    /// Called with a card's address and name when it is opened.
    using OpenFn = std::function<void(const std::string &host, const std::string &name)>;

    FleetPanel(wxWindow *parent, OpenFn open);
    ~FleetPanel() override;

    /// Reads the printers while active (the side is visible).
    void set_active(bool active);
    /// The printer presets changed.
    void reload();
    void on_sys_color_changed();

private:
    struct Printer
    {
        std::string name;             ///< the preset's name
        std::string host;             ///< empty when the preset has no usable LAN address
        bool        selected = false; ///< the preset selected for slicing
    };
    /// The printers to show: presets with an address, one per address, and the selected preset in any case.
    static std::vector<Printer> printers();
    void rebuild(bool force);
    /// Reads every printer once on the worker thread; the cards are rebuilt on the main thread when it is done.
    void read_printers();

    OpenFn        m_open;
    wxBoxSizer   *m_filters{nullptr};
    wxBoxSizer   *m_rows{nullptr};
    wxStaticText *m_empty{nullptr};
    wxTimer       m_timer;
    int           m_filter{-1}; ///< a Fleet::Band, or -1 for all
    std::string   m_shown;      ///< what the cards were built from, to skip rebuilding an unchanged list
    std::map<std::string, Fleet::Reading> m_readings; ///< by address; absent until the first read
    std::vector<::Button *>               m_filter_buttons;
    std::map<std::string, wxImage>          m_snapshots; ///< by address: the camera's last still picture, scaled for the card
    std::map<std::string, wxStaticBitmap *> m_pictures;  ///< the cards' picture windows, owned by the cards
    /// By address: where each printer's camera serves a still picture; empty when it lists none. Filled and read
    /// only by the worker thread, one pass at a time.
    std::shared_ptr<std::map<std::string, std::string>> m_camera_urls;

    std::shared_ptr<std::atomic<bool>> m_alive;   ///< false once this panel is gone
    std::shared_ptr<std::atomic<bool>> m_reading; ///< a pass over the printers is under way
    std::shared_ptr<std::atomic<bool>> m_stop;    ///< the panel is going: the pass gives up
    std::thread                        m_reader;  ///< the one pass under way or last finished; joined before the panel goes
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_FleetPanel_hpp_
