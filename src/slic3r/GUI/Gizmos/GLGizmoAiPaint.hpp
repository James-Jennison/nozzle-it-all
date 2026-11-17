#pragma once

// Include GLGizmoBase.hpp before I18N.hpp as it includes some libigl code, which overrides our localization "L" macro.
#include "GLGizmoBase.hpp"
#include "GLGizmoPainterBase.hpp"

#include "libslic3r/SemanticPaint.hpp"

#include <chrono>
#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace Slic3r {
class ModelObject;
class ModelVolume;
namespace GUI {

class MeshRaycaster;

// The draft of an AI auto-paint answer, shown on the object on the plate. The draft lives in this tool only: it is
// drawn over the object and written to the model by Apply and by nothing else, so a save or a slice while it shows
// never carries it. The view turns freely; a click on the model picks the part under it.
class GLGizmoAiPaint : public GLGizmoBase
{
public:
    // What the send window hands over: the object, its parts joined into one mesh (volume transforms applied, each
    // part a run of faces from first_face), the numbered segments and the filament suggested for each (0 = keep).
    struct Draft
    {
        int                                      object_idx{-1};
        const ModelObject                       *object{nullptr};
        indexed_triangle_set                     mesh;
        std::vector<size_t>                      first_face;
        SemanticPaint::Segmentation              seg;
        std::vector<SemanticPaint::FilamentInfo> filaments;
        std::vector<int>                         suggested;
    };

    GLGizmoAiPaint(GLCanvas3D &parent, const std::string &icon_filename, unsigned int sprite_id);
    ~GLGizmoAiPaint() override;

    // Takes a draft to show; the tool is opened right after.
    void start(Draft draft);

    // Escape drops the draft, as Discard does.
    void on_esc_key_down() { close(); }

    bool on_mouse(const wxMouseEvent &mouse_event) override;
    void data_changed(bool is_serializing) override;

protected:
    bool               on_init() override { return true; }
    std::string        on_get_name() const override;
    // Its button is with the painting tools. Pressed with an object selected, it opens the window that asks; the
    // answer comes back here as a draft.
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
    void             pick(const Vec2d &mouse_position);
    void             apply();
    void             close();

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
