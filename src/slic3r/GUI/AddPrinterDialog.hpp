#ifndef slic3r_GUI_AddPrinterDialog_hpp_
#define slic3r_GUI_AddPrinterDialog_hpp_

#include "GUI_Utils.hpp"
#include "slic3r/Utils/PrinterDiscovery.hpp"

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

class Button;
class ComboBox;
class Label;
class TextInput;

namespace Slic3r::GUI {

/// "Add printer" on the Printers side: a printer found on the local network, or one whose address is typed,
/// becomes a printer preset of the person's own that carries the address. That is all a card needs, and it
/// is Orca's own way of knowing a printer. The search and the check only read (PrinterDiscovery); the
/// printer selected for slicing stays as it is.
class AddPrinterDialog final : public DPIDialog
{
public:
    explicit AddPrinterDialog(wxWindow *parent);
    ~AddPrinterDialog() override;

    /// The name of the preset that was made; empty when none was.
    const std::string &added() const { return m_added; }

protected:
    void on_dpi_changed(const wxRect &suggested_rect) override;

private:
    void search();
    /// Asks the typed address what it is and fills in the model and the name from the answer.
    void check_address();
    void took(const PrinterDiscovery::Found &found);
    void add();

    ::Button    *m_search{nullptr};
    ::Label     *m_search_status{nullptr};
    ::ComboBox  *m_found_list{nullptr};
    ::TextInput *m_address{nullptr};
    ::Label     *m_address_status{nullptr};
    ::ComboBox  *m_model{nullptr};
    ::TextInput *m_name{nullptr};
    std::vector<PrinterDiscovery::Found> m_found;
    std::vector<std::string>             m_models; ///< the system printer presets offered, as the list shows them
    std::string                          m_suggested_name; ///< the name last filled in by the dialog, so a typed one is kept
    std::string                          m_added;

    std::shared_ptr<std::atomic<bool>> m_alive; ///< false once this dialog is gone
    std::shared_ptr<std::atomic<bool>> m_stop;  ///< the search gives up
    std::thread                        m_searcher;
};

} // namespace Slic3r::GUI

#endif // slic3r_GUI_AddPrinterDialog_hpp_
