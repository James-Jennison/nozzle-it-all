#ifndef slic3r_GUI_U1JobHistoryPanel_hpp_
#define slic3r_GUI_U1JobHistoryPanel_hpp_

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include <wx/scrolwin.h>
#include <wx/timer.h>

class ComboBox;
class wxBoxSizer;
class wxStaticText;

namespace Slic3r::GUI {

/// History side of the Printer tab: every job this app sent to the selected printer, newest first,
/// read from the job journal, together with the prints the printer's own history lists that were
/// started elsewhere, labelled as such. Each row shows what the machine reported and, apart from it,
/// the person's own verdict, which is chosen here. A job whose prepared file is still kept can be printed
/// again, through the send dialog, as a new job. The list itself only reads the printer's history.
class U1JobHistoryPanel final : public wxScrolledWindow
{
public:
    explicit U1JobHistoryPanel(wxWindow *parent);
    ~U1JobHistoryPanel() override;

    /// Rebuilds the list for the selected printer. Call when shown and when the printer changes.
    void reload();
    /// Keeps the list current while it shows.
    void set_active(bool active);
    void on_sys_color_changed();

private:
    void rebuild(bool force);
    /// Asks the printer for its own job history on a worker thread this panel owns and remembers the prints in it
    /// that were not sent from this app. One read request; no command.
    void read_printer_history();

    wxBoxSizer  *m_rows{nullptr};
    wxStaticText *m_empty{nullptr};
    wxTimer      m_timer;
    std::string  m_shown; ///< what the rows were built from, to skip rebuilding an unchanged list
    std::shared_ptr<std::atomic<bool>> m_alive;   ///< false once this panel is gone
    std::shared_ptr<std::atomic<bool>> m_reading; ///< a read of the printer's history is under way
    std::shared_ptr<std::atomic<bool>> m_stop;    ///< the panel is going: the read gives up
    std::thread                        m_reader;  ///< the one read under way or last finished; joined before the panel goes
    std::vector<::ComboBox *>          m_verdicts; ///< the rows' "Your result" lists, owned by the rows
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_U1JobHistoryPanel_hpp_
