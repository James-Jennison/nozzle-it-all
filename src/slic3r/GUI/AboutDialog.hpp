#ifndef slic3r_GUI_AboutDialog_hpp_
#define slic3r_GUI_AboutDialog_hpp_

#include <wx/panel.h>
#include <wx/event.h>
#include <string>
#include <vector>
#include <wx/string.h>
#include <wx/wx.h>
#include <wx/intl.h>
#include <wx/html/htmlwin.h>

#include "GUI_Utils.hpp"
#include "Widgets/Button.hpp"
#include "Widgets/HyperLink.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/StaticLine.hpp"
#include "wxExtensions.hpp"

class wxEvent;
class wxHtmlLinkEvent;
class wxHtmlWindow;
class wxStaticBitmap;
class wxWindow;

namespace Slic3r { 
namespace GUI {

class AboutDialogLogo : public wxPanel
{
public:
    AboutDialogLogo(wxWindow* parent);
    
private:
    ScalableBitmap logo;
    void onRepaint(wxEvent &event);
};



class CopyrightsDialog : public DPIDialog
{
public:
    CopyrightsDialog();
    ~CopyrightsDialog() {}

    struct Entry {
        Entry(const std::string &lib_name, const std::string &copyright, const std::string &link) : 
            lib_name(lib_name), copyright(copyright), link(link) {}

        std::string     lib_name;
        std::string     copyright;
        std::string   	link;
    };

protected:
    void on_dpi_changed(const wxRect &suggested_rect) override;
    
private:
    wxHtmlWindow*   m_html;
    std::vector<Entry> m_entries;

    void onLinkClicked(wxHtmlLinkEvent &event);
    void onCloseDialog(wxEvent &);

    void fill_entries();
    wxString get_html_text();
};



class AboutDialog : public DPIDialog
{
    ScalableBitmap  m_logo_bitmap;
    wxHtmlWindow*   m_html;
    wxStaticBitmap* m_logo;
    int             m_copy_rights_btn_id { wxID_ANY };
public:
    AboutDialog();

protected:
    void on_dpi_changed(const wxRect &suggested_rect) override;
    
private:
#if NOZZLE_BRANDING
    void build_nozzle_layout();
    void apply_nozzle_theme();
    void on_sys_color_changed() override;
    wxPanel* m_nia_card{nullptr};
    StaticLine* m_nia_rule{nullptr};
    HyperLink* m_nia_site{nullptr};
    Button* m_nia_license{nullptr};
    std::vector<std::pair<wxStaticText*, bool>> m_nia_labels;
#endif
    void onLinkClicked(wxHtmlLinkEvent &event);
    void onCloseDialog(wxEvent &);
    void onCopyrightBtn(wxEvent &);
    void onCopyToClipboard(wxEvent&);
};

} // namespace GUI
} // namespace Slic3r

#endif
