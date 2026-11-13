#ifndef slic3r_GUI_U1JobBar_hpp_
#define slic3r_GUI_U1JobBar_hpp_

#include "slic3r/Utils/U1JobWatcher.hpp"

#include <functional>
#include <vector>

#include <wx/panel.h>

class Button;
class wxStaticText;

namespace Slic3r::GUI {

/// A slim strip under every tab that names the job now printing and how far it is, with a button
/// to the Printer tab. Hidden while nothing sent from this app is printing. It shows what the job
/// watcher last read; it sends nothing to a printer.
class U1JobBar final : public wxPanel
{
public:
    U1JobBar(wxWindow *parent, std::function<void()> open_printer_tab);

    /// The jobs still printing as of the watcher's last pass. Main thread.
    void set_active(const std::vector<U1JobWatcher::Active> &active);
    void on_sys_color_changed();

private:
    wxStaticText *m_text{nullptr};
    ::Button     *m_open{nullptr};
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_U1JobBar_hpp_
