/* A WebKitGTK web-process extension for the printer's own web page.
 *
 * WebKitGTK does not give a continuous camera stream (multipart/x-mixed-replace) to a page's script: fetch()
 * of one is answered with its headers and then fails, while the same stream plays as a picture element.
 * Fluidd's and Mainsail's "MJPEG stream" camera reads the stream by fetch(), in a worker, so the camera
 * stays dark on the printer's page in this web view although it plays in Firefox or Chrome.
 *
 * Nothing is changed in the page or its workers. A request that looks like a camera stream on the page's own
 * host is sent to the application's relay on this computer instead (NIA_CAMERA_RELAY, set by the
 * application; see Slic3r::CameraRelay), with the address asked for and the page's origin. The relay
 * decides whether to read it and under which name the stream is handed back; an address it does not serve
 * is answered with a redirect to the address itself, which this extension leaves alone, so that the page
 * gets what it would have had.
 *
 * Mainsail's stream camera is left as it is. Mainsail draws the stream's pictures in a worker on an
 * off-screen canvas, and with a stream it can read, WebKitGTK 2.52 now and then corrupts its heap doing so
 * and the whole page goes blank (seen about once in five loads on a build without this extension, with the
 * stream served under a type the engine reads). An error in the camera panel is the lesser fault, and it is
 * what Mainsail showed before; its "Adaptive MJPEG" camera plays. A page is taken for Mainsail by its title
 * when its document has loaded, or by its camera worker's file being asked for.
 */
#include <webkit2/webkit-web-extension.h>

#include <string.h>

static gboolean looks_like_stream(GUri *uri)
{
    const char *path  = g_uri_get_path(uri);
    const char *query = g_uri_get_query(uri);
    if (query != NULL && (g_str_has_prefix(query, "action=stream") || strstr(query, "&action=stream") != NULL))
        return TRUE; /* mjpg-streamer */
    if (path == NULL)
        return FALSE;
    /* ustreamer, camera-streamer, crowsnest */
    return g_str_has_suffix(path, "/stream") || g_str_has_suffix(path, "/stream.mjpg") || g_str_has_suffix(path, "/stream.mjpeg") ||
           g_str_has_suffix(path, ".mjpg") || g_str_has_suffix(path, ".mjpeg");
}

#define LEFT_ALONE "nia-camera-left-alone"

static void on_document_loaded(WebKitWebPage *page, gpointer data)
{
    WebKitFrame *frame   = webkit_web_page_get_main_frame(page);
    JSCContext  *context = frame != NULL ? webkit_frame_get_js_context(frame) : NULL;
    gboolean     mainsail = FALSE;
    (void) data;
    if (context != NULL) {
        JSCValue *value = jsc_context_evaluate(context, "document.title", -1);
        if (value != NULL && jsc_value_is_string(value)) {
            char *title = jsc_value_to_string(value);
            char *lower = title != NULL ? g_ascii_strdown(title, -1) : NULL;
            mainsail    = lower != NULL && strstr(lower, "mainsail") != NULL;
            g_free(lower);
            g_free(title);
        }
        if (value != NULL)
            g_object_unref(value);
        g_object_unref(context);
    }
    /* A new document: what the last one was no longer counts. */
    g_object_set_data(G_OBJECT(page), LEFT_ALONE, GINT_TO_POINTER(mainsail ? 1 : 0));
}

static gboolean on_send_request(WebKitWebPage *page, WebKitURIRequest *request, WebKitURIResponse *redirected, gpointer data)
{
    const char *relay = g_getenv("NIA_CAMERA_RELAY");
    const char *address, *page_address;
    GUri       *uri, *page_uri;
    (void) data;

    /* A redirect is followed as it is: the relay sends back what it does not serve. */
    if (relay == NULL || *relay == '\0' || redirected != NULL)
        return FALSE;
    address      = webkit_uri_request_get_uri(request);
    page_address = webkit_web_page_get_uri(page);
    if (address == NULL || page_address == NULL || g_str_has_prefix(address, relay))
        return FALSE;
    if (strstr(address, "mjpegstreamer.worker") != NULL) /* Mainsail's camera worker */
        g_object_set_data(G_OBJECT(page), LEFT_ALONE, GINT_TO_POINTER(1));
    if (GPOINTER_TO_INT(g_object_get_data(G_OBJECT(page), LEFT_ALONE)) != 0)
        return FALSE;
    uri      = g_uri_parse(address, G_URI_FLAGS_NONE, NULL);
    page_uri = g_uri_parse(page_address, G_URI_FLAGS_NONE, NULL);
    if (uri != NULL && page_uri != NULL && g_uri_get_host(uri) != NULL && g_uri_get_host(page_uri) != NULL &&
        (g_strcmp0(g_uri_get_scheme(uri), "http") == 0 || g_strcmp0(g_uri_get_scheme(uri), "https") == 0) &&
        (g_strcmp0(g_uri_get_scheme(page_uri), "http") == 0 || g_strcmp0(g_uri_get_scheme(page_uri), "https") == 0) &&
        g_ascii_strcasecmp(g_uri_get_host(uri), g_uri_get_host(page_uri)) == 0 && looks_like_stream(uri)) {
        char *origin, *asked, *from, *to;
        if (g_uri_get_port(page_uri) > 0)
            origin = g_strdup_printf("%s://%s:%d", g_uri_get_scheme(page_uri), g_uri_get_host(page_uri), g_uri_get_port(page_uri));
        else
            origin = g_strdup_printf("%s://%s", g_uri_get_scheme(page_uri), g_uri_get_host(page_uri));
        asked = g_uri_escape_string(address, NULL, FALSE);
        from  = g_uri_escape_string(origin, NULL, FALSE);
        to    = g_strdup_printf("%s?u=%s&p=%s", relay, asked, from);
        webkit_uri_request_set_uri(request, to);
        g_free(to);
        g_free(from);
        g_free(asked);
        g_free(origin);
    }
    if (uri != NULL)
        g_uri_unref(uri);
    if (page_uri != NULL)
        g_uri_unref(page_uri);
    return FALSE;
}

static void on_page_created(WebKitWebExtension *extension, WebKitWebPage *page, gpointer data)
{
    (void) extension;
    (void) data;
    g_signal_connect(page, "send-request", G_CALLBACK(on_send_request), NULL);
    g_signal_connect(page, "document-loaded", G_CALLBACK(on_document_loaded), NULL);
}

G_MODULE_EXPORT void webkit_web_extension_initialize(WebKitWebExtension *extension)
{
    g_signal_connect(extension, "page-created", G_CALLBACK(on_page_created), NULL);
}
