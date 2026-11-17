#include "SemanticPaintDialog.hpp"

#include "GLCanvas3D.hpp"
#include "GUI_App.hpp"
#include "GUI_ObjectList.hpp"
#include "Gizmos/GLGizmosManager.hpp"
#include "Plater.hpp"

namespace Slic3r {
namespace GUI {

bool can_run_semantic_paint()
{
    return wxGetApp().obj_list()->get_selected_obj_idx() >= 0;
}

void run_semantic_paint()
{
    GLCanvas3D      *canvas = wxGetApp().plater()->get_view3D_canvas3D();
    GLGizmosManager &gizmos = canvas->get_gizmos_manager();
    // Opening the tool that is open would close it.
    if (gizmos.get_current_type() != GLGizmosManager::EType::AiPaint)
        gizmos.open_gizmo(GLGizmosManager::EType::AiPaint);
    canvas->set_as_dirty();
}

} // namespace GUI
} // namespace Slic3r
