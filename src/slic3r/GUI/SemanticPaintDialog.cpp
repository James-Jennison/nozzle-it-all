#include "SemanticPaintDialog.hpp"

#include "GUI.hpp"
#include "GUI_App.hpp"
#include "GUI_ObjectList.hpp"
#include "GUI_Utils.hpp"
#include "GLCanvas3D.hpp"
#include "Gizmos/GLGizmoAiPaint.hpp"
#include "Gizmos/GLGizmosManager.hpp"
#include "I18N.hpp"
#include "MainFrame.hpp"
#include "MsgDialog.hpp"
#include "Plater.hpp"
#include "Preferences.hpp"

#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/SemanticPaint.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "slic3r/Utils/SemanticPaintClient.hpp"

#include "Widgets/Button.hpp"
#include "Widgets/DialogButtons.hpp"
#include "Widgets/Label.hpp"
#include "Widgets/StateColor.hpp"
#include "Widgets/TextInput.hpp"
#include "format.hpp"

#include <wx/bitmap.h>
#include <wx/dcmemory.h>
#include <wx/display.h>
#include <wx/image.h>
#include <wx/settings.h>
#include <wx/scrolwin.h>
#include <wx/sizer.h>
#include <wx/statbmp.h>
#include <wx/stattext.h>
#include <wx/textctrl.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <thread>
#include <vector>

namespace Slic3r {
namespace GUI {

namespace SP = Slic3r::SemanticPaint;

namespace {

std::vector<SP::FilamentInfo> current_filaments()
{
    std::vector<SP::FilamentInfo> out;
    const DynamicPrintConfig &cfg    = wxGetApp().preset_bundle->project_config;
    const auto *colours = cfg.option<ConfigOptionStrings>("filament_colour");
    const auto *types   = cfg.option<ConfigOptionStrings>("filament_type");
    const size_t n = colours ? colours->values.size() : 0;
    for (size_t i = 0; i < n; ++i) {
        SP::FilamentInfo f;
        f.index     = int(i) + 1;
        f.color_hex = colours->values[i];
        f.type      = types && i < types->values.size() ? types->values[i] : std::string();
        out.push_back(f);
    }
    return out;
}

wxImage to_wx(const SP::Image &img, int display)
{
    wxImage w(img.width, img.height);
    std::copy(img.rgb.begin(), img.rgb.end(), w.GetData());
    if (display != img.width)
        w.Rescale(display, display, wxIMAGE_QUALITY_HIGH);
    return w;
}

// The hint is kept while the window is closed for a visit to the AI settings, and dropped once something was sent or the window is cancelled.
std::string s_kept_hint;

// What the window shows before anything is sent: the hint, the exact pictures, where they go and the exact text.
// Where they go is set up in Preferences > AI services, which the window opens on request (it then ends with wxID_PREFERENCES).
class SemanticPaintDialog : public DPIDialog
{
public:
    SemanticPaintDialog(wxWindow *parent, const std::string &object_name, SP::Segmentation seg, std::vector<SP::Image> images,
                        std::vector<std::string> view_names, std::vector<SP::FilamentInfo> filaments, bool has_paint)
        : DPIDialog(parent ? parent : static_cast<wxWindow *>(wxGetApp().mainframe), wxID_ANY, _L("AI auto-paint"), wxDefaultPosition, wxDefaultSize,
                    wxCAPTION | wxCLOSE_BOX | wxRESIZE_BORDER)
        , m_object_name(object_name)
        , m_seg(std::move(seg))
        , m_view_names(std::move(view_names))
        , m_filaments(std::move(filaments))
        , m_settings(semantic_paint_load_settings(*wxGetApp().app_config))
        , m_alive(std::make_shared<std::atomic<bool>>(true))
    {
        for (const SP::Image &img : images)
            m_png.push_back(SP::encode_png(img));

        SetBackgroundColour(*wxWHITE);
        SetFont(Label::Body_14);
        const int gap   = FromDIP(8);
        const int edge  = FromDIP(12);
        const int width = FromDIP(430);

        auto *sizer = new wxBoxSizer(wxVERTICAL);
        // Everything above the bottom buttons scrolls, so the window also fits a small screen at a large text size.
        m_body = new wxScrolledWindow(this, wxID_ANY);
        m_body->SetBackgroundColour(*wxWHITE);
        m_body->SetScrollRate(0, FromDIP(12));
        auto      *body = new wxBoxSizer(wxVERTICAL);
        const auto text = [width](wxWindow *parent, const wxString &words) { return new Label(parent, words, LB_AUTO_WRAP, wxSize(width, -1)); };

        body->Add(text(m_body, _L("These are the exact pictures and the exact text that will be sent. Nothing is sent until you press Send.")), 0,
                  wxEXPAND | wxALL, edge);
        body->Add(text(m_body, _L("The numbered parts are found from the model's shape. They are not guaranteed to be its meaningful parts, so "
                                  "look at the pictures before sending. The answer is shown as a draft on the model itself, to check and "
                                  "correct before applying.")),
                  0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, edge);
        if (has_paint)
            body->Add(text(m_body, _L("This object already has colour painting. In the draft, a part given a filament loses the painting it "
                                      "has, and a part kept as it is keeps it.")),
                      0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, edge);

        auto *hint_row = new wxBoxSizer(wxHORIZONTAL);
        hint_row->Add(new Label(m_body, _L("Hint")), 0, wxALIGN_CENTER_VERTICAL | wxRIGHT, edge);
        m_hint = new ::TextInput(m_body, wxEmptyString, wxEmptyString, wxEmptyString, wxDefaultPosition, wxSize(FromDIP(290), -1));
        m_hint->GetTextCtrl()->SetHint(_L("Optional, e.g. \"make it a festive Christmas tree\""));
        m_hint->GetTextCtrl()->SetValue(from_u8(s_kept_hint));
        hint_row->Add(m_hint, 1, wxEXPAND);
        body->Add(hint_row, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);

        auto *images_sizer = new wxGridSizer(3, gap, gap);
        for (size_t i = 0; i < images.size(); ++i) {
            auto *box = new wxBoxSizer(wxVERTICAL);
            // A bitmap's size is in real pixels, so the scaled size is passed, not a DIP value.
            box->Add(new wxStaticBitmap(m_body, wxID_ANY, wxBitmap(to_wx(images[i], FromDIP(120)))), 0, wxALIGN_CENTER);
            box->Add(new Label(m_body, from_u8(m_view_names[i])), 0, wxALIGN_CENTER);
            images_sizer->Add(box, 0, wxALIGN_CENTER);
        }
        body->Add(images_sizer, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        m_recipient = text(m_body, wxEmptyString);
        body->Add(m_recipient, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, edge);
        body->Add(new Label(m_body, Label::Head_14, _L("The text that is sent")), 0, wxLEFT | wxRIGHT, edge);
        m_prompt = new wxTextCtrl(m_body, wxID_ANY, wxEmptyString, wxDefaultPosition, wxSize(width, FromDIP(110)),
                                  wxTE_MULTILINE | wxTE_READONLY | wxTE_DONTWRAP);
        body->Add(m_prompt, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, edge);
        if (m_settings.provider == "command")
            body->Add(text(m_body, _L("The pictures are written to a temporary folder and their file names are added to this text.")), 0,
                      wxEXPAND | wxLEFT | wxRIGHT | wxTOP, edge);
        body->AddSpacer(edge);

        m_body->SetSizer(body);
        sizer->Add(m_body, 1, wxEXPAND);
        // Below the scrolled part and always in view: what is going on or why Send cannot be pressed, then the buttons.
        m_status = text(this, wxEmptyString);
        sizer->Add(m_status, 0, wxLEFT | wxRIGHT | wxTOP | wxEXPAND, edge);
        auto *buttons = new DialogButtons(this, {L("AI settings"), L("Send"), "Cancel"}, _L("Send"), 1);
        m_settings_button = buttons->GetButtonFromIndex(0);
        m_send            = buttons->GetButtonFromIndex(1);
        buttons->GetCANCEL()->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { EndModal(wxID_CANCEL); });
        sizer->Add(buttons, 0, wxEXPAND);

        m_hint->GetTextCtrl()->Bind(wxEVT_TEXT, [this](wxCommandEvent &event) {
            m_prompt->ChangeValue(from_u8(prompt()));
            event.Skip();
        });
        m_settings_button->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) {
            if (!m_busy)
                EndModal(wxID_PREFERENCES);
        });
        m_send->Bind(wxEVT_BUTTON, [this](wxCommandEvent &) { on_send(); });

        update_state();
        fit_to_screen();
        SetSizerAndFit(sizer);
        CenterOnParent();
        wxGetApp().UpdateDlgDarkUI(this);
        // The text that is sent is there to be read: full contrast in both themes, though it cannot be edited.
        m_prompt->SetBackgroundColour(StateColor::darkModeColorFor(*wxWHITE));
        m_prompt->SetForegroundColour(StateColor::darkModeColorFor(wxColour("#262E30")));
    }

    ~SemanticPaintDialog() override
    {
        // Every way of closing ends here: the worker is told to stop and waited for.
        m_alive->store(false);
        m_cancel = true;
        if (m_worker.joinable())
            m_worker.join();
    }

    // The filament suggested for each part (0 = keep as it is), once an answer was read.
    const std::vector<int> &suggested() const { return m_suggested; }
    std::string             hint() const { return into_u8(m_hint->GetTextCtrl()->GetValue().Trim().Trim(false)); }

protected:
    void on_dpi_changed(const wxRect &) override
    {
        m_hint->Rescale();
        fit_to_screen();
        GetSizer()->SetSizeHints(this);
        Refresh();
    }

private:
    // The scrolled part is as tall as its content, up to what the screen has room for.
    void fit_to_screen()
    {
        const wxSize content = m_body->GetSizer()->GetMinSize();
        const int    room    = wxDisplay(wxGetApp().mainframe ? static_cast<wxWindow *>(wxGetApp().mainframe) : this).GetClientArea().GetHeight() -
                               FromDIP(320);
        m_body->SetMinSize(wxSize(content.GetWidth() + wxSystemSettings::GetMetric(wxSYS_VSCROLL_X, m_body),
                                  std::min(content.GetHeight(), std::max(FromDIP(200), room))));
        m_body->Layout();
        m_body->FitInside();
    }

    // The question with the standing guidance and the user's own hint; the hint is said to come first.
    std::string prompt() const
    {
        const std::string guidance =
            "Prefer a coherent, natural palette. Segment tints are identification labels, not suggested paint colours. Do not use every "
            "available filament merely because it is available. Use accent colours only when the views clearly support them; do not invent "
            "decorative patches. A geometric segment may cross a semantic feature: when uncertain keep the dominant body colour. Keep "
            "matching features consistent across all views. The user's requested style takes precedence.";
        const std::string words = hint();
        return SP::build_prompt(m_seg, m_filaments, m_object_name, m_view_names, words.empty() ? guidance : guidance + "\n" + words);
    }

    // Where the data would go, the exact text, and whether Send can be pressed, with the reason when not.
    void update_state()
    {
        const SemanticPaintSettings &s       = m_settings;
        const bool                   command = s.provider == "command", openai = s.provider == "openai";
        const wxString               recipient = from_u8(semantic_paint_recipient(s));
        wxString                     goes_to, why;
        if (s.provider.empty())
            why = _L("No AI service is set up, so nothing can be sent. Choose one under AI settings.");
        else if (command && recipient.empty())
            why = _L("No command is set. Enter one under AI settings.");
        else if (openai && recipient.empty())
            why = _L("No server address is set. Enter one under AI settings.");
        else if (openai && s.model.empty())
            why = _L("No model is set. Choose one under AI settings. It must be one that accepts pictures.");
        else if (!semantic_paint_not_ready(s).empty())
            why = _L("A server that takes an API key must be reached over HTTPS, so the key is never sent in the clear. Change this under AI settings.");
        if (command && !recipient.empty())
            goes_to = s.model.empty() ?
                format_wxstr(_L("Goes to: the program \"%1%\" on this computer. Where that program sends it, and which model answers, is "
                                "decided by the program and how you have set it up."), recipient) :
                format_wxstr(_L("Goes to: the program \"%1%\" on this computer, asked for the model \"%2%\". Where that program sends it is "
                                "decided by the program and how you have set it up."), recipient, from_u8(s.model));
        else if (openai && !recipient.empty())
            goes_to = s.model.empty() ? format_wxstr(_L("Goes to: the server at %1%."), recipient) :
                                        format_wxstr(_L("Goes to: the server at %1%, model \"%2%\"."), recipient, from_u8(s.model));
        else
            goes_to = _L("Goes to: nowhere yet.");
        m_recipient->SetLabel(goes_to);
        m_recipient->Wrap(-1);
        m_recipient->Wrap(FromDIP(430));
        m_prompt->ChangeValue(from_u8(prompt()));
        m_can_send = why.empty();
        m_send->Enable(m_can_send && !m_busy);
        set_status(why);
    }

    void set_status(const wxString &text)
    {
        const bool changed = m_status_text != text;
        m_status_text      = text;
        if (!changed)
            return;
        m_status->SetLabel(text);
        m_status->Wrap(-1);
        m_status->Wrap(FromDIP(430));
        // A status of another length needs another height: the window follows, so the text is never cut off.
        if (GetSizer() != nullptr) {
            fit_to_screen();
            GetSizer()->SetSizeHints(this);
            Layout();
        }
    }

    void on_send()
    {
        if (m_busy || !semantic_paint_not_ready(m_settings).empty())
            return;
        const std::string text = prompt();

        m_busy   = true;
        m_cancel = false;
        m_send->Disable();
        m_settings_button->Disable();
        set_status(_L("Waiting for the answer... this can take up to a few minutes."));
        if (m_worker.joinable())
            m_worker.join();
        auto alive = m_alive;
        m_worker   = std::thread([this, alive, s = m_settings, text] {
            SemanticPaintReply r = semantic_paint_query(s, text, m_png, &m_cancel);
            wxGetApp().CallAfter([this, alive, r = std::move(r)] {
                if (alive->load())
                    on_reply(r);
            });
        });
    }

    void on_reply(const SemanticPaintReply &r)
    {
        m_busy = false;
        m_send->Enable(m_can_send);
        m_settings_button->Enable();
        if (!r.ok) {
            set_status(format_wxstr(_L("Failed: %1%"), from_u8(r.error)));
            return;
        }
        const auto parsed = SP::parse_assignments(r.text, m_seg.segments.size(), m_filaments.size());
        if (!parsed) {
            set_status(_L("The answer could not be understood. Try again or choose another model under AI settings."));
            return;
        }
        // The answer goes to the plate: the window closes and the draft is shown on the model itself.
        m_suggested = *parsed;
        EndModal(wxID_OK);
    }

    std::string                        m_object_name;
    SP::Segmentation                   m_seg;
    std::vector<int>                   m_suggested;
    std::vector<std::string>           m_png;
    std::vector<std::string>           m_view_names;
    std::vector<SP::FilamentInfo>      m_filaments;
    SemanticPaintSettings              m_settings;
    std::shared_ptr<std::atomic<bool>> m_alive;
    std::atomic<bool>                  m_cancel{false};
    bool                               m_busy{false};
    bool                               m_can_send{false};
    wxString                           m_status_text{"\x01"}; // not yet set: the first status is always applied
    std::thread                        m_worker;

    wxScrolledWindow *m_body{nullptr};
    ::TextInput      *m_hint{nullptr};
    Label            *m_status{nullptr}, *m_recipient{nullptr};
    wxTextCtrl       *m_prompt{nullptr};
    ::Button         *m_send{nullptr}, *m_settings_button{nullptr};
};

} // namespace

bool can_run_semantic_paint()
{
    return wxGetApp().obj_list()->get_selected_obj_idx() >= 0;
}

void run_semantic_paint()
{
    Plater *plater = wxGetApp().plater();
    // Whatever tool is open is closed first, this one included (opened from its button it has no draft yet), and it is
    // opened again only with an answer to show.
    GLGizmosManager &open_tools = plater->get_view3D_canvas3D()->get_gizmos_manager();
    if (open_tools.get_current_type() != GLGizmosManager::EType::Undefined) {
        open_tools.reset_all_states();
        plater->get_view3D_canvas3D()->set_as_dirty();
    }
    const int obj_idx = wxGetApp().obj_list()->get_selected_obj_idx();
    Model &model = plater->model();
    if (obj_idx < 0 || obj_idx >= int(model.objects.size())) {
        show_info(plater, _L("Select an object first."), _L("AI auto-paint"));
        return;
    }
    ModelObject *object = model.objects[obj_idx];
    std::vector<ModelVolume *> parts;
    for (ModelVolume *v : object->volumes)
        if (v->is_model_part())
            parts.push_back(v);
    if (parts.empty())
        return;

    const std::vector<SP::FilamentInfo> filaments = current_filaments();
    if (filaments.size() < 2) {
        show_info(plater, _L("Add at least two filaments first; there is nothing to choose between with one."), _L("AI auto-paint"));
        return;
    }
    // All parts go into one mesh (volume transforms applied, instance ignored). Parts share no vertices, so a segment never
    // spans two parts, and each part owns a contiguous range of faces that is used to split the answer back up.
    indexed_triangle_set       combined;
    std::vector<size_t>        first_face;
    for (ModelVolume *v : parts) {
        TriangleMesh mesh = v->mesh();
        mesh.transform(v->get_matrix());
        const int offset = int(combined.vertices.size());
        first_face.push_back(combined.indices.size());
        combined.vertices.insert(combined.vertices.end(), mesh.its.vertices.begin(), mesh.its.vertices.end());
        for (const stl_triangle_vertex_indices &t : mesh.its.indices)
            combined.indices.emplace_back(t(0) + offset, t(1) + offset, t(2) + offset);
    }
    first_face.push_back(combined.indices.size());

    const SP::Segmentation seg = SP::segment_mesh(combined);
    std::vector<SP::Image>   images;
    std::vector<std::string> names;
    for (const SP::View &v : SP::default_views()) {
        images.push_back(SP::render_view(combined, seg, v));
        names.push_back(v.name);
    }

    const bool has_paint = std::any_of(parts.begin(), parts.end(), [](const ModelVolume *v) { return !v->mmu_segmentation_facets.empty(); });
    std::vector<int> suggested;
    {
        SemanticPaintDialog dlg(plater, object->name, seg, std::move(images), std::move(names), filaments, has_paint);
        const int           answer = dlg.ShowModal();
        s_kept_hint                = answer == wxID_PREFERENCES ? dlg.hint() : std::string();
        if (answer == wxID_PREFERENCES) {
            // The settings are a window of their own. This one is opened again afterwards from the start: the settings can
            // change the whole interface (a language change builds it anew), so nothing gathered above is used again.
            wxGetApp().CallAfter([] {
                wxGetApp().open_preferences(PreferencesTab::AI, "ai_services_provider");
                wxGetApp().CallAfter([] {
                    if (can_run_semantic_paint())
                        run_semantic_paint();
                });
            });
            return;
        }
        if (answer != wxID_OK)
            return;
        suggested = dlg.suggested();
    }

    // The draft is shown on the plate by its own tool, which writes to the model only when the user applies it.
    GLGizmosManager &gizmos = plater->get_view3D_canvas3D()->get_gizmos_manager();
    auto            *tool   = dynamic_cast<GLGizmoAiPaint *>(gizmos.get_gizmo(GLGizmosManager::EType::AiPaint));
    if (tool == nullptr)
        return;
    GLGizmoAiPaint::Draft draft;
    draft.object_idx = obj_idx;
    draft.object     = object;
    draft.mesh       = std::move(combined);
    draft.first_face = std::move(first_face);
    draft.seg        = seg;
    draft.filaments  = filaments;
    draft.suggested  = std::move(suggested);
    tool->start(std::move(draft));
    gizmos.open_gizmo(GLGizmosManager::EType::AiPaint);
    plater->get_view3D_canvas3D()->set_as_dirty();
}

} // namespace GUI
} // namespace Slic3r
