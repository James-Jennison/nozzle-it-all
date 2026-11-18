#pragma once

// Include GLGizmoBase.hpp before I18N.hpp as it includes some libigl code, which overrides our localization "L" macro.
#include "GLGizmoBase.hpp"
#include "GLGizmoPainterBase.hpp"

#include "libslic3r/SemanticPaint.hpp"
#include "slic3r/Utils/SemanticPaintClient.hpp"

#include <wx/string.h>

#include <atomic>
#include <chrono>
#include <cstddef>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace Slic3r {
class ModelObject;
class ModelVolume;
namespace GUI {

class GLTexture;
class MeshRaycaster;

// AI auto-paint, all of it in one panel beside the plate, in three steps: a hint and where the question goes; the
// exact pictures and text, sent only by its Send button; then the answer as a draft on the object itself. The draft
// lives in this tool only: it is drawn over the object and written to the model by Apply and by nothing else, so a
// save or a slice while it shows never carries it. The view turns freely; a click on the model picks the part under it.
class GLGizmoAiPaint : public GLGizmoBase
{
public:
    enum class Step { Ask, Review, CloseUps, Draft };
    enum class Action { Nothing, Prepare, Send, Back, StopWaiting, Settings, Apply, Close, SkipCloseUps };

    // What is prepared for sending and, once answered, the draft: the object, its parts joined into one mesh (volume
    // transforms applied, each part a run of faces from first_face), the numbered segments, the pictures and text
    // that are sent, and the filament suggested for each segment (0 = keep).
    struct Draft
    {
        int                                      object_idx{-1};
        const ModelObject                       *object{nullptr};
        indexed_triangle_set                     mesh;
        std::vector<size_t>                      first_face;
        SemanticPaint::Segmentation              seg;
        std::vector<SemanticPaint::FilamentInfo> filaments;
        std::vector<SemanticPaint::Image>        images;     // the views, in the order they are sent
        std::vector<std::string>                 view_names;
        std::vector<std::vector<int>>            labelled;   // per view, the segments it numbers
        std::shared_ptr<const std::vector<std::string>> png; // the views as they are sent
        std::string                              prompt;     // the text as it is sent
        std::vector<int>                         suggested;
        // From the answer, per part and possibly empty: the name it gave the part and whether it said it was unsure;
        // and what it took the object to be. Shown to the user as plain text, never acted on.
        std::vector<std::string>                 names;
        std::vector<char>                        unsure;
        std::string                              subject;
        // What the service had to say, e.g. what of the request it could not do; and where the marked features
        // (spots) begin among the segments: they come after the numbered ones and carry no number in the pictures.
        std::string                              notes;
        int                                      not_placed{0}; // marks that lay on nothing
        int                                      left_out{0};   // filaments given to the surface around a feature, not taken
        size_t                                   first_spot{0};
        // For the second question, when the first answer marked features that have pieces of their own: the parts as
        // split before any feature was marked, the first answer, the close-ups, and their pictures and text as sent.
        SemanticPaint::Segmentation                     base_seg;
        SemanticPaint::Suggestion                       first;
        std::string                                     hint; // the standing guidance and the user's own words, as sent
        std::vector<SemanticPaint::CloseUp>             closeups;
        std::vector<SemanticPaint::Image>               close_images;
        std::vector<std::string>                        close_names;
        std::shared_ptr<const std::vector<std::string>> close_png;
        std::string                                     close_prompt;
        // The marked features as they are painted, in order: round patches, drawn with the round brush's own means.
        std::vector<SemanticPaint::SpotPatch>    patches;
        // Which part every face belonged to before the features were marked: what shows under a feature kept as it is.
        std::vector<int>                         face_part;
    };

    GLGizmoAiPaint(GLCanvas3D &parent, const std::string &icon_filename, unsigned int sprite_id);
    ~GLGizmoAiPaint() override;
    // How far from the plate's right edge the application's notices are to stay while the panel is open: they are drawn
    // beside it and not over its buttons. 0 when there is no panel.
    float notices_right_margin() const;

    // Escape closes the tool and drops the draft, as Discard does.
    void on_esc_key_down() { close(); }

    bool on_mouse(const wxMouseEvent &mouse_event) override;
    void data_changed(bool is_serializing) override;

protected:
    bool               on_init() override { return true; }
    std::string        on_get_name() const override;
    // Its button is with the painting tools. Pressed with an object selected, it opens the panel at its first step.
    bool               on_is_activable() const override;
    void               on_set_state() override;
    CommonGizmosDataID on_get_requirements() const override;
    void               on_render() override;
    void               on_render_input_window(float x, float y, float bottom_limit) override;

private:
    // The object's printable parts as the model has them now, or empty when they no longer match the draft.
    std::vector<ModelVolume *> parts() const;
    // The filament of every face as it would be applied (0 = keep), boundary cleanup included when it is on.
    std::vector<int> faces(size_t *tidied = nullptr) const;
    void             rebuild();
    // Why the first step cannot go on, or empty; and where the question would go.
    wxString         not_ready() const;
    wxString         goes_to() const;
    // Segments the selected object and renders the views: what Send would send. Nothing leaves the computer here.
    void             prepare();
    void             send();
    void             on_reply(const SemanticPaintReply &reply);
    void             stop_waiting();
    void             open_settings();
    void             render_ask(float wrap);
    void             render_review(float wrap);
    void             render_draft(float wrap);
    void             render_closeups(float wrap);
    // The draft from the first answer, with the close-ups' answer worked in when there is one.
    void             finish_draft(const SemanticPaint::CloseUpAnswer *answer);
    void             render_views(float wrap);
    void             render_pictures(float wrap, const std::vector<SemanticPaint::Image> &images, const std::vector<std::string> &names,
                                     std::vector<std::unique_ptr<GLTexture>> &textures);
    void             pick(const Vec2d &mouse_position);
    void             apply();
    void             close();

    Step                                                m_step{Step::Ask};
    std::string                                         m_hint;
    SemanticPaintSettings                               m_settings;
    wxString                                            m_status;          // the last thing that went wrong, or empty
    bool                                                m_busy{false};     // an answer is being waited for
    Action                                              m_action{Action::Nothing}; // what a button asked for, acted on once the panel is drawn
    bool                                                m_show_views{false};
    int                                                 m_settle{0};       // frames still to draw: a panel that changed takes its new size a frame later
    unsigned                                            m_asked{0};        // counts questions, so a late answer to an abandoned one is dropped
    // What a question's thread shares with the tool. The thread hands its answer over while holding `gate`, and the
    // tool's destructor takes `gate` to clear `alive`: a thread is either done handing over or will not hand over.
    struct Shared
    {
        std::mutex       gate;
        bool             alive{true};
        std::atomic<int> running{0}; // questions' threads that have not ended
    };
    std::shared_ptr<Shared>                             m_shared;
    std::shared_ptr<std::atomic<bool>>                  m_alive; // for work the tool hands to itself on the main thread
    std::shared_ptr<std::atomic<bool>>                  m_cancel;
    float                                               m_panel_left{-1.f}; // where the panel begins on the plate, to keep the notices beside it
    std::vector<std::unique_ptr<GLTexture>>             m_textures;        // the views, for the panel
    std::vector<std::unique_ptr<GLTexture>>             m_close_textures;  // the close-ups, for the panel
    std::unique_ptr<Draft>                              m_draft;
    std::vector<int>                                    m_choice;          // per segment, 0 = keep as it is
    std::vector<std::unique_ptr<TriangleSelectorPatch>> m_selectors;       // per part, what is drawn
    std::unique_ptr<MeshRaycaster>                      m_raycaster;
    bool                                                m_tidy{true};
    bool                                                m_show_original{false};
    bool                                                m_dirty{true};
    int                                                 m_selected{-1};    // the segment last picked
    bool                                                m_scroll_to_selected{false};
    bool                                                m_flash_drawn{false};
    std::chrono::steady_clock::time_point               m_flash_until{};
    size_t                                              m_tidied{0};
    size_t                                              m_assigned{0};
    Vec2d                                               m_press{Vec2d::Zero()};
    bool                                                m_pressed{false};
};

} // namespace GUI
} // namespace Slic3r
