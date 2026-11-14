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

#include <boost/log/trivial.hpp>
#include <boost/filesystem/path.hpp>

#include <nlohmann/json.hpp>

#include <wx/event.h>
#include <wx/panel.h>
#include <wx/gdicmn.h>
#include <wx/log.h>
#include <wx/sizer.h>
#include <wx/string.h>
#include <wx/base64.h>
#include <wx/longlong.h>
#include <wx/timer.h>
#include <wx/toolbar.h>
#include <wx/uri.h>
#include <wx/utils.h>
#include <wx/webview.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <future>
#include <memory>
#include <string>
#include <utility>

class wxWindow;

#ifdef __linux__
#include <webkit2/webkit2.h>
#endif

namespace Slic3r {
namespace GUI {

#ifdef __linux__
// WebKitGTK does not give a continuous camera stream (multipart/x-mixed-replace) to a page's script: fetch()
// of one is answered with its headers and then fails ("Load failed"), from the page's own address too, while
// the same stream plays as a picture element. Fluidd's and Mainsail's "MJPEG stream" camera reads the stream
// by fetch(), in a worker, so on the printer's own page the camera stays dark in this web view although it
// plays in Firefox or Chrome. Copying a picture element that plays the stream is no way round it: the engine
// refuses the stream to a picture element that asks the camera's leave to be copied, and a camera on its own
// port needs that leave.
// A fetch answered with such a stream is given the same stream in the same format, read by the application
// itself (PrinterWebView::start_page_camera, the reader the Monitor side's camera uses) and handed into the
// page picture by picture. The application reads only from the printer the page came from; for a camera
// elsewhere, or when the application's stream fails, the pictures are fetched one at a time from the camera's
// still-picture address instead. A still that is slow in coming is given up and asked for again, and the last
// picture is handed over again meanwhile: ustreamer now and then holds one back for ten seconds, and the page
// gives a silent stream up before that. Every other fetch is passed through untouched.
static void inject_mjpeg_fetch_workaround(wxWebView *webView)
{
    webView->AddUserScript(R"JS((function () {
  'use strict';
  // Page only: the application's way in and out. It reads the stream at *address* and calls __niaCameraPicture
  // with each picture; a stream nobody vouches for is stopped by the application.
  const post = function (what) { try { window.webkit.messageHandlers.wx.postMessage(what); } catch (e) {} };
  const receivers = {};
  let relay = null;
  const fromApp = function (address, deliver, fail) {
    const id = Date.now() + '-' + String(Math.random()).slice(2);
    let over = false, got = false, alive = 0, patience = 0;
    const stop = function () {
      if (over) return;
      over = true; clearInterval(alive); clearTimeout(patience); delete receivers[id];
      post('nia-camera-stop:' + id);
    };
    receivers[id] = {deliver: function (picture) { got = true; if (!over) deliver(picture); }, fail: function (why) { stop(); fail(why); }};
    alive = setInterval(function () { post('nia-camera-alive:' + id); }, 2000);
    patience = setTimeout(function () { if (!got && !over) { stop(); fail('the application sent no picture in 5 s'); } }, 5000);
    post('nia-camera-start:' + id + ' ' + address);
    return stop;
  };
  // Runs in the page and, through the Worker wrapper below, in the page's workers: `self` is either. A worker
  // has no picture elements; it asks the page for the pictures, on a channel only this script uses.
  const install = function (fromApp) {
    if (self.__niaCameraFetch || !self.fetch || !self.ReadableStream) return;
    self.__niaCameraFetch = true;
    const nativeFetch = self.fetch.bind(self);
    // What happens to a replaced stream is told to the application, which writes it to its log. A worker has
    // no way to the application of its own, so it tells the page.
    let told = 0;
    const tell = function (what) {
      if (++told > 60) return;
      try {
        if (self.webkit && self.webkit.messageHandlers && self.webkit.messageHandlers.wx) self.webkit.messageHandlers.wx.postMessage('nia-camera:' + what);
        else { if (!self.__niaTell) self.__niaTell = new BroadcastChannel('nia-camera'); self.__niaTell.postMessage(String(what)); }
      } catch (e) {}
    };
    // mjpg-streamer: ?action=stream -> ?action=snapshot. ustreamer, camera-streamer: /stream -> /snapshot.
    function stillFor(address) {
      try {
        const url = new URL(address);
        if (url.searchParams.get('action') === 'stream') { url.searchParams.set('action', 'snapshot'); return url; }
        const m = /\/stream(\.mjpe?g)?$/.exec(url.pathname);
        if (m) { url.pathname = url.pathname.slice(0, -m[0].length) + (m[1] ? '/snapshot.jpg' : '/snapshot'); return url; }
      } catch (e) {}
      return null;
    }
    // Worker only: the application's pictures by way of the page. Returns what stops them.
    const viaPage = function (address, deliver, fail) {
      let channel, alive = 0, patience = 0, over = false, got = false;
      const id = Date.now() + '-' + String(Math.random()).slice(2);
      const stop = function () {
        if (over) return;
        over = true; clearInterval(alive); clearTimeout(patience);
        try { channel.postMessage({stop: id}); channel.close(); } catch (e) {}
      };
      try { channel = new BroadcastChannel('nia-camera-relay'); } catch (e) { setTimeout(function () { fail('no channel to the page'); }, 0); return stop; }
      channel.onmessage = function (e) {
        const m = e.data;
        if (over || !m || m.id !== id) return;
        if (m.picture) { got = true; deliver(m.picture); }
        else if (m.failed) { stop(); fail(m.failed); }
      };
      // A worker can be ended without a word; the page stops a stream nobody vouches for.
      alive = setInterval(function () { try { channel.postMessage({alive: id}); } catch (e) {} }, 2000);
      patience = setTimeout(function () { if (!got && !over) { stop(); fail('the application sent no picture in 5 s'); } }, 5000);
      channel.postMessage({start: id, address: address});
      return stop;
    };
    // The pictures one at a time from the still address. Returns what stops them.
    const stills = function (still, signal, deliver, fail) {
      let over = false, last = null, failures = 0;
      const one = function () {
        still.searchParams.set('_', String(Date.now()));
        const patience = new AbortController(), giveUp = setTimeout(function () { patience.abort(); }, last ? 700 : 12000);
        if (signal) signal.addEventListener('abort', function () { patience.abort(); });
        return nativeFetch(still.href, {mode: 'cors', cache: 'no-store', signal: patience.signal}).then(function (r) {
          if (!r.ok) throw new Error('status ' + r.status);
          return r.arrayBuffer();
        }).finally(function () { clearTimeout(giveUp); });
      };
      const round = function () {
        if (over) return;
        one().then(function (b) { failures = 0; last = b; if (!over) deliver(b.slice(0)); setTimeout(round, 100); },
                   function (e) {
                     if (over) return;
                     if (!last || ++failures > 40) { over = true; fail('no still picture: ' + (e && e.message)); return; }
                     deliver(last.slice(0)); setTimeout(round, 50); // the same picture again, so the stream is not silent
                   });
      };
      round();
      return function () { over = true; };
    };
    // The answer given in place of the stream: the same pictures, in the stream's own format.
    const instead = function (address, signal) {
      const text = new TextEncoder(), boundary = 'niaframe', name = address.split('?')[0];
      let latest = null, waiting = null, over = false, pictures = 0, since = Date.now(), stopSource = function () {}, hold = null;
      const wake = function () { if (waiting) { const go = waiting; waiting = null; go(); } };
      const deliver = function (picture) { latest = picture; wake(); };
      const end = function () { if (over) return; over = true; stopSource(); wake(); };
      const broken = function (why) {
        tell(name + ': ' + why + '; no pictures to give');
        end(); try { hold.error(new TypeError(String(why))); } catch (e) {}
      };
      const fromStills = function (why) {
        const still = stillFor(address);
        if (over) return;
        if (!still) return broken(why);
        tell(name + ': ' + why + '; taking still pictures from ' + still.href.split('?')[0]);
        stopSource = stills(still, signal, deliver, broken);
      };
      const source = {
        start: function (controller) {
          hold = controller;
          stopSource = fromApp ? fromApp(address, deliver, fromStills) : viaPage(address, deliver, fromStills);
          if (signal) signal.addEventListener('abort', function () { if (!over) { end(); try { controller.close(); } catch (e) {} } });
        },
        pull: function (controller) {
          if (over) return;
          return new Promise(function (go) { if (latest) go(); else waiting = go; }).then(function () {
            if (over || !latest) return;
            const picture = latest; latest = null;
            // One piece per picture: its part header, the picture, the line break that ends the part.
            const head = text.encode('--' + boundary + '\r\nContent-Type: image/jpeg\r\nContent-Length: ' + picture.byteLength + '\r\n\r\n');
            const piece = new Uint8Array(head.length + picture.byteLength + 2);
            piece.set(head, 0); piece.set(new Uint8Array(picture), head.length); piece.set([13, 10], head.length + picture.byteLength);
            controller.enqueue(piece);
            pictures++;
            if (pictures === 1 || Date.now() - since > 10000) { tell(name + ': picture ' + pictures + ' handed over (' + picture.byteLength + ' bytes)'); since = Date.now(); }
          });
        },
        cancel: function (why) { tell(name + ': the reader gave the stream up after ' + pictures + ' pictures' + (why ? ': ' + why : '')); end(); }
      };
      // A byte stream where the engine has them, so that a byte reader is accepted as on a real answer.
      let body, kind = 'byte stream';
      try { body = new ReadableStream(Object.assign({type: 'bytes'}, source)); }
      catch (e) { kind = 'plain stream (' + (e && e.message) + ')'; body = new ReadableStream(source); }
      tell(name + ': camera stream replaced, ' + kind + ', pictures asked of the application');
      return new Response(body, {status: 200, headers: {'Content-Type': 'multipart/x-mixed-replace;boundary=' + boundary}});
    };
    // The engine answers such a request with its headers and then fails the body, so the answer's type
    // decides, not a failure: a continuous stream is replaced before it is read.
    self.fetch = function (input, init) {
      const tried = nativeFetch(input, init);
      return tried.then(function (answer) {
        const type = (answer.headers && answer.headers.get('content-type')) || '';
        if (!/^multipart\/x-mixed-replace/i.test(type)) return answer;
        let address = answer.url;
        if (!address) { try { address = new URL(typeof input === 'string' ? input : (input && input.url) || String(input), self.location.href).href; } catch (e) { return answer; } }
        const signal = (init && init.signal) || (input && input.signal) || undefined;
        try { if (answer.body) answer.body.cancel().catch(function () {}); } catch (e) {}
        return instead(address, signal);
      });
    };
  };
  if (typeof window === 'undefined') { install(); return; }
  install(fromApp);
  // What the workers tell arrives here and is passed on to the application.
  try {
    const heard = new BroadcastChannel('nia-camera');
    heard.onmessage = function (e) {
      try { window.webkit.messageHandlers.wx.postMessage('nia-camera:worker: ' + e.data); } catch (x) {}
    };
  } catch (e) {}
  // The application's pictures arrive here, for the page's own reader or for the worker that asked.
  window.__niaCameraPicture = function (id, base64) {
    try {
      const raw = atob(base64), picture = new Uint8Array(raw.length);
      for (let i = 0; i < raw.length; i++) picture[i] = raw.charCodeAt(i);
      if (receivers[id]) receivers[id].deliver(picture.buffer);
      else if (relay) relay.postMessage({id: id, picture: picture.buffer});
    } catch (e) {}
  };
  window.__niaCameraFailed = function (id, why) {
    if (receivers[id]) receivers[id].fail(String(why));
    else if (relay) relay.postMessage({id: id, failed: String(why)});
  };
  // What the workers ask is passed on to the application, word for word.
  try {
    relay = new BroadcastChannel('nia-camera-relay');
    relay.onmessage = function (e) {
      const m = e.data;
      if (!m) return;
      if (m.start) post('nia-camera-start:' + m.start + ' ' + m.address);
      else if (m.alive) post('nia-camera-alive:' + m.alive);
      else if (m.stop) post('nia-camera-stop:' + m.stop);
    };
  } catch (e) {}
  // A script added to this web view does not run in the page's workers, and Fluidd reads its camera in one.
  // A worker started from a file is started through a few lines that install the same replacement and then
  // load that file, as a module where it was asked for as one. Workers made from other sources are left alone,
  // and so is any worker this cannot wrap.
  const NativeWorker = window.Worker;
  if (!NativeWorker || window.__niaCameraWorker) return;
  window.__niaCameraWorker = true;
  const source = '(' + install.toString() + ')();';
  const Wrapped = function (url, options) {
    try {
      const real = new URL(String(url), location.href);
      if (real.protocol === 'http:' || real.protocol === 'https:') {
        const module = options && options.type === 'module';
        const boot = source + '\n' + (module ? 'import ' + JSON.stringify(real.href) + ';' : 'importScripts(' + JSON.stringify(real.href) + ');');
        const address = URL.createObjectURL(new Blob([boot], {type: 'text/javascript'}));
        const worker = new NativeWorker(address, options);
        setTimeout(function () { URL.revokeObjectURL(address); }, 60000);
        return worker;
      }
    } catch (e) {}
    return new NativeWorker(url, options);
  };
  Wrapped.prototype = NativeWorker.prototype;
  window.Worker = Wrapped;
})();)JS");
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

PrinterWebView::PrinterWebView(wxWindow *parent)
        : wxPanel(parent, wxID_ANY, wxDefaultPosition, wxDefaultSize)
    , m_browser(nullptr)
    , m_zoomFactor(100)
    , m_apikey()
    , m_apikey_sent(false)
    , m_url_deferred()
    , m_handler(std::make_unique<PrinterWebViewHandler>(*this))
 {

    wxBoxSizer* topsizer = new wxBoxSizer(wxVERTICAL);

    m_camera_watch.Bind(wxEVT_TIMER, [this](wxTimerEvent &) { watch_page_cameras(); });

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
    BOOST_LOG_TRIVIAL(info) << __FUNCTION__ << " Start";
    SetEvtHandlerEnabled(false);
    m_handler.reset();
    m_alive->store(false);
    m_camera_watch.Stop();
    m_page_cameras.clear();

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
    inject_mjpeg_fetch_workaround(m_browser);

    auto cookiesPath = boost::filesystem::path(data_dir() + "/cache/cookies.db");
    auto wv = static_cast<WebKitWebView*>(m_browser->GetNativeBackend());
    auto wv_ctx = webkit_web_view_get_context(wv);
    auto cookieManager = webkit_web_context_get_cookie_manager(wv_ctx);
    webkit_cookie_manager_set_persistent_storage(cookieManager, cookiesPath.c_str(), WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
#endif

    m_browser->Bind(wxEVT_WEBVIEW_ERROR, &PrinterWebView::OnError, this);
    m_browser->Bind(wxEVT_WEBVIEW_LOADED, &PrinterWebView::OnLoaded, this);
    m_browser->Bind(wxEVT_WEBVIEW_NEWWINDOW, &PrinterWebView::OnNewWindow, this);
    m_browser->Bind(wxEVT_WEBVIEW_SCRIPT_MESSAGE_RECEIVED, &PrinterWebView::OnScriptMessage, this);
    update_mode();
}

void PrinterWebView::reset_browser()
{
    stop_page_cameras(); // their page goes with the browser
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
    m_apikey = apikey;
    m_host_type = host_type;
    m_apikey_sent = false;
    m_url_loaded = false;
    m_handler = create_printer_webview_handler(*this);
    stop_page_cameras(); // the page that asked for them is being left

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
    inject_mjpeg_fetch_workaround(m_browser);
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

// A stream's reader takes up to a second to stop, so it is stopped off the main thread. The stopping is
// kept track of and waited for when the panel is destroyed: no thread of it outlives the panel.
void PrinterWebView::retire(std::unique_ptr<MjpegStream> stream)
{
    m_retiring.erase(std::remove_if(m_retiring.begin(), m_retiring.end(),
                                    [](const std::future<void> &done) { return done.wait_for(std::chrono::seconds(0)) == std::future_status::ready; }),
                     m_retiring.end());
    if (stream)
        m_retiring.push_back(std::async(std::launch::async, [stream = std::move(stream)]() mutable { stream.reset(); }));
}

// "http://host:port" of an address, the port written out, for comparing where two addresses lead.
static std::string origin_of(const wxURI &address)
{
    const wxString scheme = address.GetScheme().Lower();
    const wxString port   = address.HasPort() ? address.GetPort() : wxString(scheme == "https" ? "443" : "80");
    return (scheme + "://" + address.GetServer().Lower() + ":" + port).utf8_string();
}

// A call into the page's camera replacement. Neither argument may hold a quote: the name is checked where it
// arrives, the other is base64 or a sentence written here.
static wxString page_call(const char *function, const std::string &id, const wxString &argument)
{
    return wxString::Format("window.%s&&window.%s('%s','%s')", function, function, wxString::FromUTF8(id), argument);
}

void PrinterWebView::start_page_camera(const std::string &id, const std::string &address)
{
    // The name goes back into the page inside a script, so it is digits and dashes or it is not used.
    if (id.empty() || id.size() > 40 || !std::all_of(id.begin(), id.end(), [](char c) { return (c >= '0' && c <= '9') || c == '-'; }))
        return;
    const auto refuse = [this, id](const std::string &why) {
        BOOST_LOG_TRIVIAL(warning) << "Printer page camera: not read by the application: " << why;
        // Not on the stack of the page's message: see the orca-wxwidgets skill, lifetime rule.
        wxGetApp().CallAfter([this, alive = m_alive, id, why] {
            if (alive->load() && !wxGetApp().is_closing() && m_browser != nullptr)
                m_browser->RunScriptAsync(page_call("__niaCameraFailed", id, wxString::FromUTF8(why)));
        });
    };
    // The page may name any address. The application reads only from the printer the page itself came from,
    // follows no redirect from there, and, where the camera is not the page's own origin (another port), only
    // a camera whose answer gives the page leave to read it, as a browser would require of it: the page gets
    // nothing through the application that a browser would not let its script read.
    const wxURI page(m_browser->GetCurrentURL()), camera(wxString::FromUTF8(address));
    if ((camera.GetScheme() != "http" && camera.GetScheme() != "https") || camera.GetServer().empty() || camera.HasUserInfo() ||
        !camera.GetServer().IsSameAs(page.GetServer(), false))
        return refuse("the camera is not on the printer this page came from");
    MjpegStream::Limits limits;
    limits.redirects = false;
    if (origin_of(camera) != origin_of(page))
        limits.leave_for_origin = origin_of(page);
    if (m_page_cameras.count(id) != 0)
        return;
    if (m_page_cameras.size() >= 4)
        return refuse("four camera streams are being read already");

    // One picture on its way to the page at a time; a picture that arrives meanwhile is dropped.
    auto pending = std::make_shared<std::atomic<bool>>(false);
    PageCamera &camera_read = m_page_cameras[id];
    camera_read.vouched     = wxGetUTCTimeMillis();
    camera_read.stream      = std::make_unique<MjpegStream>(address, [this, alive = m_alive, pending, id](std::string jpeg) {
        if (pending->exchange(true))
            return;
        wxGetApp().CallAfter([this, alive, pending, id, jpeg = std::move(jpeg)] {
            pending->store(false);
            if (!alive->load() || wxGetApp().is_closing() || m_browser == nullptr || m_page_cameras.count(id) == 0)
                return;
            m_browser->RunScriptAsync(page_call("__niaCameraPicture", id, wxBase64Encode(jpeg.data(), jpeg.size())));
        });
    }, 10, limits);
    BOOST_LOG_TRIVIAL(warning) << "Printer page camera: the application reads " << address.substr(0, address.find('?')) << " for the page";
    if (!m_camera_watch.IsRunning())
        m_camera_watch.Start(2000);
}

void PrinterWebView::stop_page_camera(const std::string &id)
{
    if (const auto camera = m_page_cameras.find(id); camera != m_page_cameras.end()) {
        retire(std::move(camera->second.stream));
        m_page_cameras.erase(camera);
    }
    if (m_page_cameras.empty())
        m_camera_watch.Stop();
}

void PrinterWebView::stop_page_cameras()
{
    for (auto &camera : m_page_cameras)
        retire(std::move(camera.second.stream));
    m_page_cameras.clear();
    m_camera_watch.Stop();
}

// A page that was reloaded or whose worker was ended says nothing; a stream it no longer vouches for is
// stopped. A stream the camera ended is reported to the page, which then falls back on still pictures.
void PrinterWebView::watch_page_cameras()
{
    const wxLongLong now = wxGetUTCTimeMillis();
    for (auto camera = m_page_cameras.begin(); camera != m_page_cameras.end();) {
        const bool ended = camera->second.stream->ended();
        if (!ended && now - camera->second.vouched < 7000) {
            ++camera;
            continue;
        }
        if (ended && m_browser != nullptr)
            m_browser->RunScriptAsync(page_call("__niaCameraFailed", camera->first,
                                                camera->second.stream->refused() ? "the camera gave this page no leave to read it" : "the camera ended the stream"));
        retire(std::move(camera->second.stream));
        camera = m_page_cameras.erase(camera);
    }
    if (m_page_cameras.empty())
        m_camera_watch.Stop();
}

void PrinterWebView::OnScriptMessage(wxWebViewEvent& evt)
{
  // What the camera replacement (inject_mjpeg_fetch_workaround) reports goes to the log and nowhere else.
  if (const wxString text = evt.GetString(); text.StartsWith("nia-camera:")) {
    BOOST_LOG_TRIVIAL(warning) << "Printer page camera: " << text.Mid(11).Left(300).utf8_string();
    return;
  }
  if (wxString rest; evt.GetString().StartsWith("nia-camera-start:", &rest)) {
    start_page_camera(rest.BeforeFirst(' ').utf8_string(), rest.AfterFirst(' ').utf8_string());
    return;
  } else if (evt.GetString().StartsWith("nia-camera-alive:", &rest)) {
    if (const auto camera = m_page_cameras.find(rest.utf8_string()); camera != m_page_cameras.end())
      camera->second.vouched = wxGetUTCTimeMillis();
    return;
  } else if (evt.GetString().StartsWith("nia-camera-stop:", &rest)) {
    stop_page_camera(rest.utf8_string());
    return;
  }
  if (m_handler != nullptr)
    m_handler->on_script_message(evt);
}


} // GUI
} // Slic3r
