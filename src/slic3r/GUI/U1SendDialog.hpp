#ifndef slic3r_U1SendDialog_hpp_
#define slic3r_U1SendDialog_hpp_

#include "GUI_Utils.hpp"
#include "libslic3r/U1PrintTask.hpp"

#include <boost/filesystem/path.hpp>
#include <wx/bitmap.h>
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
/// what is being printed, the printer, filament -> toolhead mapping, and the
/// printer's print options, each in its own outlined section.
class U1SendDialog : public DPIDialog
{
public:
    /// *plate_filaments* is indexed by slicer filament; entries the plate does
    /// not use have present == false and get no card. *filament_grams* is
    /// indexed the same way; a missing or zero weight is simply not shown.
    /// *readiness* says why the file cannot be sent (second argument false) or
    /// started (true) with a filament -> toolhead map, empty when it can; the
    /// dialog shows the reason and disables the button it applies to.
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
                 std::function<wxString(const std::vector<int> &, bool)> readiness = {});

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
    std::vector<int> mapping() const;

    struct Row
    {
        size_t           filament;
        U1FilamentCard *card;
    };

    std::vector<U1PrintTask::Filament> m_plate_filaments;
    std::vector<U1PrintTask::Filament> m_toolheads;
    std::vector<Row>                   m_rows;
    std::function<wxString(const std::vector<int> &, bool)> m_readiness;
    ::Label                           *m_mapping_note      = nullptr;
    ::Label                           *m_status            = nullptr;
    ::Button                          *m_upload            = nullptr;
    ::Button                          *m_print             = nullptr;
    ::TextInput                       *m_filename          = nullptr;
    ::CheckBox                        *m_bed_level         = nullptr;
    ::CheckBox                        *m_flow_calibrate    = nullptr;
    ::CheckBox                        *m_time_lapse        = nullptr;
    ::CheckBox                        *m_shaper_calibrate  = nullptr;
    bool                               m_start_print       = true;
};

} // namespace GUI
} // namespace Slic3r

#endif // slic3r_U1SendDialog_hpp_
