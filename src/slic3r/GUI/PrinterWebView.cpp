#include "PrinterWebView.hpp"

#include "PrinterWebViewHandler.hpp"
#include "I18N.hpp"

#include "slic3r/GUI/PrinterWebView.hpp"
#include "slic3r/GUI/GUI_App.hpp"
#include "slic3r/GUI/MainFrame.hpp"
#include <slic3r/GUI/Widgets/WebView.hpp>
#include <slic3r/GUI/Widgets/WebViewHostDialog.hpp>

#include "libslic3r_version.h"
#include <libslic3r/PrintConfig.hpp>
#include "libslic3r/Utils.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "slic3r/Utils/CameraRelay.hpp"

#include <boost/log/trivial.hpp>
#include <boost/filesystem/path.hpp>

#include <nlohmann/json.hpp>

#include <wx/event.h>
#include <wx/panel.h>
#include <wx/gdicmn.h>
#include <wx/log.h>
#include <wx/sizer.h>
#include <wx/string.h>
#include <wx/stdpaths.h>
#include <wx/toolbar.h>
#include <wx/uri.h>
#include <wx/utils.h>
#include <wx/webview.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <set>
#include <string>
#include <utility>

class wxWindow;

#ifdef __linux__
#include <webkit2/webkit2.h>
#endif

namespace Slic3r {
namespace GUI {

#ifdef __linux__
// The printer page's camera. WebKitGTK does not give a continuous camera stream (multipart/x-mixed-replace)
// to a page's script, and Fluidd's and Mainsail's "MJPEG stream" camera reads the stream by script, so the
// camera stays dark on the printer's own page in this web view although it plays in Firefox or Chrome.
// Nothing is done to the page: the web view's process loads a small extension (webext/nia_web_extension.c)
// that sends the page's camera requests to the application's relay on this computer (Utils/CameraRelay),
// which reads the camera and hands the stream back under a type the engine passes on. This function is
// called with a web view's context before the view is created.
static std::string web_extension_dir()
{
    namespace fs = boost::filesystem;
    boost::system::error_code ec;
    const fs::path program = fs::path(wxStandardPaths::Get().GetExecutablePath().utf8_string()).parent_path();
    // The AppImage's lib directory (resources_dir() is <root>/resources there), then beside a built program.
    for (const fs::path &dir : {fs::path(resources_dir()).parent_path() / "lib" / "nia-web-extensions", program / "nia-web-extensions",
                                program.parent_path() / "nia-web-extensions"})
        if (fs::exists(dir / "libnia_web_extension.so", ec))
            return dir.string();
    return {};
}

static void set_web_extensions_dir(WebKitWebContext *context, gpointer)
{
    static const std::string dir = web_extension_dir();
    if (!dir.empty())
        webkit_web_context_set_web_extensions_directory(context, dir.c_str());
}

void attach_printer_camera_relay(void *web_context)
{
    static bool said = false;
    if (web_context == nullptr)
        return;
    const std::string dir = web_extension_dir(), where = dir.empty() ? std::string() : CameraRelay::instance().start();
    if (!said) {
        said = true;
        if (where.empty())
            BOOST_LOG_TRIVIAL(warning) << "Printer page camera: not available (" << (dir.empty() ? "the web view extension was not found" : "the relay could not listen") << ")";
        else
            BOOST_LOG_TRIVIAL(warning) << "Printer page camera: relay listening on this computer, extension in " << dir;
    }
    if (where.empty())
        return;
    // The web view's process is started later and inherits this; the extension reads it there.
    wxSetEnv("NIA_CAMERA_RELAY", wxString::FromUTF8(where));
    // After wxWidgets' own handler of the same signal, so that this directory is the one that stands.
    g_signal_connect_after(WEBKIT_WEB_CONTEXT(web_context), "initialize-web-extensions", G_CALLBACK(set_web_extensions_dir), nullptr);
}

// Workaround for #7210: WebKitGTK crashes on vue-resize's hidden <object> probe used by
// older Fluidd/Mainsail pages. Swap that <object> for a <div> shim at appendChild time
// and bridge resize events through a fake contentDocument.defaultView so vue-resize keeps
// working. Workaround proposed by @VittC.
static void inject_vue_resize_workaround(wxWebView *webView)
{
    webView->AddUserScript(
        "(function() {"
        "  'use strict';"
        "  function isVueResizeObject(el) {"
        "    return el && el.tagName === 'OBJECT'"
        "        && el.type === 'text/html'"
        "        && el.getAttribute('aria-hidden') === 'true'"
        "        && el.getAttribute('tabindex') === '-1';"
        "  }"
        "  function isResizeObserverParent(p) {"
        "    return p && p.classList && p.classList.contains('resize-observer');"
        "  }"
        "  function makeShim(orig, parentForRO) {"
        "    var shim = document.createElement('div');"
        "    shim.setAttribute('aria-hidden', 'true');"
        "    shim.setAttribute('tabindex', '-1');"
        "    shim.style.display = 'none';"
        "    var fakeWin = document.createElement('div');"
        "    var ro = null;"
        "    var origRemoveEL = fakeWin.removeEventListener.bind(fakeWin);"
        "    fakeWin.removeEventListener = function(type, fn, opts) {"
        "      origRemoveEL(type, fn, opts);"
        "      if (type === 'resize' && ro) { ro.disconnect(); ro = null; }"
        "    };"
        "    Object.defineProperty(shim, 'contentDocument', {"
        "      configurable: true,"
        "      get: function() { return { defaultView: fakeWin }; }"
        "    });"
        "    Object.defineProperty(shim, 'contentWindow', {"
        "      configurable: true,"
        "      get: function() { return fakeWin; }"
        "    });"
        "    if (typeof orig.onload === 'function') { shim.onload = orig.onload; }"
        "    queueMicrotask(function() {"
        "      if (parentForRO && typeof ResizeObserver !== 'undefined') {"
        "        ro = new ResizeObserver(function() {"
        "          fakeWin.dispatchEvent(new Event('resize'));"
        "        });"
        "        ro.observe(parentForRO);"
        "      }"
        "      if (typeof shim.onload === 'function') {"
        "        try { shim.onload(new Event('load')); } catch (e) {}"
        "      }"
        "      shim.dispatchEvent(new Event('load'));"
        "    });"
        "    return shim;"
        "  }"
        "  var origAppend = Node.prototype.appendChild;"
        "  Node.prototype.appendChild = function(child) {"
        "    if (isResizeObserverParent(this) && isVueResizeObject(child)) {"
        "      return origAppend.call(this, makeShim(child, this));"
        "    }"
        "    return origAppend.call(this, child);"
        "  };"
        "  var origInsertBefore = Node.prototype.insertBefore;"
        "  Node.prototype.insertBefore = function(child, ref) {"
        "    if (isResizeObserverParent(this) && isVueResizeObject(child)) {"
        "      return origInsertBefore.call(this, makeShim(child, this), ref);"
        "    }"
        "    return origInsertBefore.call(this, child, ref);"
        "  };"
        "  console.log('[vr-fix] vue-resize WebKitGTK patch active');"
        "})();",
        wxWEBVIEW_INJECT_AT_DOCUMENT_START
    );
}
#else
void attach_printer_camera_relay(void *) {}
#endif

// The strings of the page shown when the printer has no connection set up
// (resources/web/orca/missing_connection.html), keyed by its data-i18n ids.
static void add_page_strings(wxWebView *webView)
{
    const nlohmann::json strings = {
        {"printer_connection", _u8L("Printer Connection")},
        {"printer_connection_setup", _u8L("Please set up your printer connection to view the device.")},
    };
    webView->AddUserScript(wxString::FromUTF8(WebViewHostDialog::localized_strings_script(strings)));
}

// The views that exist, for work queued to the main thread that must not touch one that is gone.
static std::set<const PrinterWebView *> s_live_views;

bool PrinterWebView::is_live(const PrinterWebView *view) { return s_live_views.count(view) != 0; }

PrinterWebView::PrinterWebView(wxWindow *parent)
        : wxPanel(parent, wxID_ANY, wxDefaultPosition, wxDefaultSize)
    , m_browser(nullptr)
    , m_zoomFactor(100)
    , m_apikey()
    , m_apikey_sent(false)
    , m_url_deferred()
    , m_handler(std::make_unique<PrinterWebViewHandler>(*this))
 {
    s_live_views.insert(this);


    wxBoxSizer* topsizer = new wxBoxSizer(wxVERTICAL);

      // Create the webview
    create_browser();
    m_reset_on_show = WebView::NeedsRecreateOnShow();

    SetSizer(topsizer);

    topsizer->Add(m_browser, wxSizerFlags().Expand().Proportion(1));

    // Log backend information
    /* m_browser->GetUserAgent() may lead crash
    if (wxGetApp().get_mode() == comDevelop) {
        wxLogMessage(wxWebView::GetBackendVersionInfo().ToString());
        wxLogMessage("Backend: %s Version: %s", m_browser->GetClassInfo()->GetClassName(),
            wxWebView::GetBackendVersionInfo().ToString());
        wxLogMessage("User Agent: %s", m_browser->GetUserAgent());
    }
    */

    //Connect the idle events
    Bind(wxEVT_CLOSE_WINDOW, &PrinterWebView::OnClose, this);

 }

PrinterWebView::~PrinterWebView()
{
    s_live_views.erase(this);
    BOOST_LOG_TRIVIAL(info) << __FUNCTION__ << " Start";
    SetEvtHandlerEnabled(false);
    m_handler.reset();

    // Destroy the webview
    if(m_browser){
        m_browser->Destroy();
        m_browser = nullptr;
    }


    BOOST_LOG_TRIVIAL(info) << __FUNCTION__ << " End";
}

void PrinterWebView::create_browser()
{
    m_browser = WebView::CreateWebView(this, "");
    add_page_strings(m_browser);

#ifdef __linux__
    inject_vue_resize_workaround(m_browser);

    auto cookiesPath = boost::filesystem::path(data_dir() + "/cache/cookies.db");
    auto wv = static_cast<WebKitWebView*>(m_browser->GetNativeBackend());
    auto wv_ctx = webkit_web_view_get_context(wv);
    auto cookieManager = webkit_web_context_get_cookie_manager(wv_ctx);
    webkit_cookie_manager_set_persistent_storage(cookieManager, cookiesPath.c_str(), WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
    // Mainsail's stream camera can end the page's web process once it has pictures to draw (see
    // webext/nia_web_extension.c), and the page is blank from then on. When a web process ends while the
    // relay is passing this printer's camera on, or was within the last second (the stream ends with the
    // process, a moment before this is told), the page is loaded again and the relay serves
    // that printer no more in this run: the page is back as it was before the relay, with its camera error.
    // A web process that ends at any other time is none of this code's business (one ends, reported the same
    // way, when the view leaves one printer's page for another's) and is left to the engine.
    g_signal_connect(wv, "web-process-terminated", G_CALLBACK(+[](WebKitWebView *, WebKitWebProcessTerminationReason reason, gpointer self) {
                         auto *view = static_cast<PrinterWebView *>(self);
                         wxGetApp().CallAfter([view, reason] {
                             if (wxGetApp().is_closing() || !PrinterWebView::is_live(view))
                                 return;
                             const std::string host = wxURI(view->m_url).GetServer().utf8_string();
                             if (!CameraRelay::instance().had_stream(host, 1)) {
                                 BOOST_LOG_TRIVIAL(warning) << "Printer page camera: a web process ended (reason " << int(reason) << ") with no camera of " << host << " being passed on; nothing done";
                                 return;
                             }
                             BOOST_LOG_TRIVIAL(warning) << "Printer page camera: the page's web process ended (reason " << int(reason) << ") while the camera of " << host
                                                        << " was passed on; the page is loaded again and that camera is no longer passed on";
                             CameraRelay::instance().deny_host(host);
                             if (view->m_browser != nullptr && !view->m_url.empty())
                                 view->m_browser->LoadURL(view->m_url);
                         });
                     }), this);
#endif

    m_browser->Bind(wxEVT_WEBVIEW_ERROR, &PrinterWebView::OnError, this);
    m_browser->Bind(wxEVT_WEBVIEW_LOADED, &PrinterWebView::OnLoaded, this);
    m_browser->Bind(wxEVT_WEBVIEW_NEWWINDOW, &PrinterWebView::OnNewWindow, this);
    m_browser->Bind(wxEVT_WEBVIEW_SCRIPT_MESSAGE_RECEIVED, &PrinterWebView::OnScriptMessage, this);
    update_mode();
}

void PrinterWebView::reset_browser()
{
    m_browser->Destroy(); // also removes it from the sizer
    create_browser();
    GetSizer()->Add(m_browser, wxSizerFlags().Expand().Proportion(1));
    Layout();

    // OnLoaded may have cleared m_url_deferred already, so requeue the last url for Show().
    m_apikey_sent  = false;
    m_url_loaded   = false;
    m_url_deferred = m_url;
}

// Same lookup as create_printer_webview_handler().
static PrintHostType active_host_type()
{
    if (wxGetApp().preset_bundle == nullptr)
        return htOctoPrint;
    return wxGetApp().preset_bundle->printers.get_edited_preset().config.option<ConfigOptionEnum<PrintHostType>>("host_type")->value;
}

void PrinterWebView::load_url(wxString& url, wxString apikey)
{
//    this->Show();
//    this->Raise();
    const PrintHostType host_type = active_host_type();
    // Selecting the tab requests the same page again; navigating would reload it and drop its state.
    if (m_url_loaded && url == m_url && apikey == m_apikey && host_type == m_host_type)
        return;

    m_url = url;
    // The relay passes on cameras of printers whose page is shown here, and of no other machine.
    CameraRelay::instance().allow_host(wxURI(url).GetServer().utf8_string());
    m_apikey = apikey;
    m_host_type = host_type;
    m_apikey_sent = false;
    m_url_loaded = false;
    m_handler = create_printer_webview_handler(*this);

    if (this->IsShown()) {
        //ORCA: m_url_deferred will be cleared on load success
        //m_url_deferred.clear();
        m_browser->LoadURL(url);
    } else {
        m_url_deferred = url;
    }
    //m_browser->SetFocus();
    UpdateState();
}

bool PrinterWebView::Show(bool show)
{
    if (show && std::exchange(m_reset_on_show, false))
        reset_browser();
    if (show && !m_url_deferred.empty()) {
        m_browser->LoadURL(m_url_deferred);
        //ORCA: m_url_deferred will be cleared on load success
        //m_url_deferred.clear();
    }
    return wxPanel::Show(show);
}

void PrinterWebView::reload()
{
    m_browser->Reload();
}

void PrinterWebView::update_mode()
{
    m_browser->EnableAccessToDevTools(wxGetApp().app_config->get_bool("developer_mode"));
}

/**
 * Method that retrieves the current state from the web control and updates the
 * GUI the reflect this current state.
 */
void PrinterWebView::UpdateState() {
  // SetTitle(m_browser->GetCurrentTitle());

}

void PrinterWebView::OnClose(wxCloseEvent& evt)
{
    this->Hide();
}

void PrinterWebView::SendAPIKey()
{
    if (m_apikey_sent || m_apikey.IsEmpty())
        return;
    m_apikey_sent   = true;
    wxString script = wxString::Format(R"(
    // Check if window.fetch exists before overriding
    if (window.fetch) {
        const originalFetch = window.fetch;
        window.fetch = function(input, init = {}) {
            init.headers = init.headers || {};
            init.headers['X-API-Key'] = '%s';
            return originalFetch(input, init);
        };
    }
)",
                                       m_apikey);
    m_browser->RemoveAllUserScripts();
    
    // RemoveAllUserScripts causes WebView to forget about our script message handler, 
    // so re-add it here.
    m_browser->RemoveScriptMessageHandler("wx");
    // Mark before adding: on WKWebView the add yields in RunScript() after registering "wx",
    // and CreateWebView()'s deferred add, if it runs in that yield, would register it again.
    WebView::MarkScriptMessageHandlerAdded(m_browser);
    if (!m_browser->AddScriptMessageHandler("wx"))
        wxLogError("Could not add script message handler");

#ifdef __linux__
    // Re-inject the vue-resize/WebKitGTK workaround that RemoveAllUserScripts just cleared.
    inject_vue_resize_workaround(m_browser);
#endif
    add_page_strings(m_browser);

    m_browser->AddUserScript(script);
    m_browser->Reload();
}

void PrinterWebView::OnError(wxWebViewEvent &evt)
{
    auto e = "unknown error";
    switch (evt.GetInt()) {
      case wxWEBVIEW_NAV_ERR_CONNECTION:
        e = "wxWEBVIEW_NAV_ERR_CONNECTION";
        break;
      case wxWEBVIEW_NAV_ERR_CERTIFICATE:
        e = "wxWEBVIEW_NAV_ERR_CERTIFICATE";
        break;
      case wxWEBVIEW_NAV_ERR_AUTH:
        e = "wxWEBVIEW_NAV_ERR_AUTH";
        break;
      case wxWEBVIEW_NAV_ERR_SECURITY:
        e = "wxWEBVIEW_NAV_ERR_SECURITY";
        break;
      case wxWEBVIEW_NAV_ERR_NOT_FOUND:
        e = "wxWEBVIEW_NAV_ERR_NOT_FOUND";
        break;
      case wxWEBVIEW_NAV_ERR_REQUEST:
        e = "wxWEBVIEW_NAV_ERR_REQUEST";
        break;
      case wxWEBVIEW_NAV_ERR_USER_CANCELLED:
        e = "wxWEBVIEW_NAV_ERR_USER_CANCELLED";
        break;
      case wxWEBVIEW_NAV_ERR_OTHER:
        e = "wxWEBVIEW_NAV_ERR_OTHER";
        break;
      }
    BOOST_LOG_TRIVIAL(info) << __FUNCTION__<< boost::format(": error loading page %1% %2% %3% %4%") %evt.GetURL() %evt.GetTarget() %e %evt.GetString();
}

void PrinterWebView::OnLoaded(wxWebViewEvent& evt)
{
    // The web view's initial empty document (about:blank on WebKit) is not the requested page.
    if (evt.GetURL().IsEmpty() || evt.GetURL() == "about:blank")
        return;
    //ORCA: url loaded successfully, safe to clear
    m_url_deferred.clear();
    m_url_loaded = true;
    SendAPIKey();
  
    if (m_handler != nullptr) {
        m_handler->on_loaded(evt);
        return;
    }
}

void PrinterWebView::OnNewWindow(wxWebViewEvent& evt)
{
  const wxString url = evt.GetURL();
  if (!url.empty())
    wxLaunchDefaultBrowser(url);
  evt.Veto();
}

void PrinterWebView::OnScriptMessage(wxWebViewEvent& evt)
{
  if (m_handler != nullptr)
    m_handler->on_script_message(evt);
}


} // GUI
} // Slic3r
