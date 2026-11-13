#ifndef slic3r_GUI_U1JobHistoryPanel_hpp_
#define slic3r_GUI_U1JobHistoryPanel_hpp_

#include <string>

#include <wx/scrolwin.h>
#include <wx/timer.h>

class wxBoxSizer;
class wxStaticText;

namespace Slic3r::GUI {

/// History side of the Printer tab: every job this app sent to the selected printer, newest first,
/// read from the job journal. Each row shows what the machine reported and, apart from it, the
/// person's own verdict, which is chosen here. Reads the journal only; talks to no printer.
class U1JobHistoryPanel final : public wxScrolledWindow
{
public:
    explicit U1JobHistoryPanel(wxWindow *parent);

    /// Rebuilds the list for the selected printer. Call when shown and when the printer changes.
    void reload();
    /// Keeps the list current while it shows.
    void set_active(bool active);
    void on_sys_color_changed();

private:
    void rebuild(bool force);

    wxBoxSizer  *m_rows{nullptr};
    wxStaticText *m_empty{nullptr};
    wxTimer      m_timer;
    std::string  m_shown; ///< what the rows were built from, to skip rebuilding an unchanged list
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_U1JobHistoryPanel_hpp_
