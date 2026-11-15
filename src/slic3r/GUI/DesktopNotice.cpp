#include "DesktopNotice.hpp"

#ifdef __WXGTK__
#include <gio/gio.h>

#include <memory>
#include <string>
#else
#include <wx/notifmsg.h>
#endif

namespace Slic3r::GUI {

#ifdef __WXGTK__
namespace {

struct Notice
{
    std::string title;
    std::string text;
};

void on_session_bus(GObject *, GAsyncResult *result, gpointer data)
{
    const std::unique_ptr<Notice> notice(static_cast<Notice *>(data));
    GDBusConnection              *bus = g_bus_get_finish(result, nullptr);
    if (bus == nullptr)
        return;
    GVariantBuilder actions, hints;
    g_variant_builder_init(&actions, G_VARIANT_TYPE("as"));
    g_variant_builder_init(&hints, G_VARIANT_TYPE("a{sv}"));
    // No reply is waited for: a desktop without the service simply shows nothing.
    g_dbus_connection_call(bus, "org.freedesktop.Notifications", "/org/freedesktop/Notifications", "org.freedesktop.Notifications", "Notify",
                           g_variant_new("(susssasa{sv}i)", "", guint32(0), "", notice->title.c_str(), notice->text.c_str(), &actions, &hints, gint32(-1)),
                           nullptr, G_DBUS_CALL_FLAGS_NONE, 5000, nullptr, nullptr, nullptr);
    g_object_unref(bus);
}

} // namespace
#endif

void desktop_notice(const wxString &title, const wxString &text)
{
#ifdef __WXGTK__
    // wxWidgets is built without libnotify here, and its own stand-in is a window the app would have to place,
    // which a Wayland session does not allow. The desktop's notification service is asked directly.
    g_bus_get(G_BUS_TYPE_SESSION, nullptr, on_session_bus, new Notice{std::string(title.utf8_str()), std::string(text.utf8_str())});
#else
    wxNotificationMessage(title, text).Show();
#endif
}

} // namespace Slic3r::GUI
