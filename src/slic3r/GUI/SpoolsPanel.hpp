#ifndef slic3r_GUI_SpoolsPanel_hpp_
#define slic3r_GUI_SpoolsPanel_hpp_

#include <wx/scrolwin.h>

#include <string>

class wxBoxSizer;
class wxStaticText;

namespace Slic3r::GUI {

/// The Spools side of the Printer tab: the person's spools, how much is left on each (an estimate from the
/// jobs sent from this app, or a weighing), where each is, and its drying sessions. Every entry is made or
/// confirmed by the person. Nothing here is sent to a printer, and nothing here stops a print.
class SpoolsPanel final : public wxScrolledWindow
{
public:
    explicit SpoolsPanel(wxWindow *parent);

    /// Shown or hidden: the list is read again when it comes to show.
    void set_active(bool active);
    void reload() { rebuild(); }
    void on_sys_color_changed();

private:
    void rebuild();
    void add_by_hand();
    /// Reads the printer now shown on the Printer tab (read requests only) and, once the person agrees, adds a
    /// spool for each loaded slot that has none recorded.
    void add_from_printer();
    void weigh(int number);
    void move(int number);
    void dry(int number);
    void archive(int number);

    wxBoxSizer   *m_rows{nullptr};
    wxStaticText *m_empty{nullptr};
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_SpoolsPanel_hpp_
