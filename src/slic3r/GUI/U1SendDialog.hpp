#ifndef slic3r_U1SendDialog_hpp_
#define slic3r_U1SendDialog_hpp_

#include "GUI_Utils.hpp"
#include "libslic3r/U1PrintTask.hpp"

#include <boost/filesystem/path.hpp>
#include <wx/bitmap.h>
#include <wx/scrolwin.h>
#include <wx/string.h>

#include <cstddef>
#include <functional>
#include <vector>

class CheckBox;
class Button;
class Label;
class TextInput;

namespace Slic3r {
namespace GUI {

class U1FilamentCard;

/// Native replacement for the Flutter pre-print dialog on the Snapmaker U1:
/// what is being printed and the printer side by side, then the filament ->
/// toolhead mapping and the printer's print options, each in its own outlined
/// section. The sections scroll when the screen is too small for them, so the
/// buttons are always reachable.
class U1SendDialog : public DPIDialog
{
public:
    /// Why the file cannot be sent (second argument false) or started (true)
    /// with a filament -> toolhead map; empty when nothing stands in the way.
    using Readiness = std::function<wxString(const std::vector<int> &, bool)>;
    struct ReadinessRead
    {
        Readiness check;
        bool      answered = false; ///< the printer returned its state for this read
    };

    /// *plate_filaments* is indexed by slicer filament; entries the plate does
    /// not use have present == false and get no card. *filament_grams* is
    /// indexed the same way; a missing or zero weight is simply not shown.
    /// *read_readiness* reads the printer and returns its Readiness. The dialog
    /// calls it once it is on screen, when the user asks, and again when Start
    /// Print or Upload is pressed; it shows the reason, disables the button it applies
    /// to, and does not close on a refusal. It only informs: the send itself
    /// reads the printer again and decides.
    U1SendDialog(wxWindow                               *parent,
                 const boost::filesystem::path          &default_name,
                 const std::vector<U1PrintTask::Filament> &plate_filaments,
                 const U1PrintTask::PrinterState          &printer,
                 const wxString                           &project_name,
                 int                                       plate_number,
                 const wxString                           &estimated_time,
                 const wxBitmap                           &thumbnail,
                 const std::vector<double>                &filament_grams,
                 const wxString                           &printer_address,
                 std::function<ReadinessRead()>            read_readiness = {});

    /// The base of the name the file gets on the printer: the export's name, or
    /// what the user typed over it, with characters a printer cannot take
    /// replaced, ending in .gcode. The send adds the job's identifier to it.
    boost::filesystem::path filename() const;
    bool                    start_print() const { return m_start_print; }
    U1PrintTask::Options    options() const;

protected:
    void on_dpi_changed(const wxRect &suggested_rect) override;

private:
    void on_confirm(bool start_print);
    /// Shows what the current mapping means: mappings onto an empty toolhead or a different material under
    /// the cards, the printer's readiness in its section, and Start Print / Upload enabled to match.
    void update_state();
    /// Reads the printer again (blocks briefly, on the GUI thread) and notes the time.
    void refresh_readiness();
    /// Sizes the dialog to its content, limited to the screen it is on.
    void fit_to_screen();
    /// The file name is changed where it is shown: a click turns it into a text field.
    void begin_name_edit();
    void end_name_edit(bool keep);
    std::vector<int> mapping() const;

    struct Row
    {
        size_t           filament;
        U1FilamentCard *card;
    };

    std::vector<U1PrintTask::Filament> m_plate_filaments;
    std::vector<U1PrintTask::Filament> m_toolheads;
    std::vector<Row>                   m_rows;
    std::function<ReadinessRead()>     m_read_readiness;
    bool                               m_checking          = false; ///< the first read has not happened yet
    wxString                           m_name;                      ///< file name as shown and sent, already made safe
    ::Label                           *m_name_label        = nullptr;
    ::TextInput                       *m_name_edit         = nullptr;
    bool                               m_ending_name_edit  = false;
    Readiness                          m_readiness;
    wxString                           m_checked_at;
    ::Label                           *m_mapping_note      = nullptr;
    ::Label                           *m_status            = nullptr;
    ::Button                          *m_upload            = nullptr;
    ::Button                          *m_print             = nullptr;
    wxScrolledWindow                  *m_scroll            = nullptr;
    bool                               m_laid_out          = false;
    ::CheckBox                        *m_bed_level         = nullptr;
    ::CheckBox                        *m_flow_calibrate    = nullptr;
    ::CheckBox                        *m_time_lapse        = nullptr;
    ::CheckBox                        *m_shaper_calibrate  = nullptr;
    bool                               m_start_print       = true;
};

} // namespace GUI
} // namespace Slic3r

#endif // slic3r_U1SendDialog_hpp_
