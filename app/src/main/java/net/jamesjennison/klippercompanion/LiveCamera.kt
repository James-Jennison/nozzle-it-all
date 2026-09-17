package net.jamesjennison.klippercompanion

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable fun LiveCamera(address: String, camera: Camera) {
    val endpoint = remember(address, camera.stream) { runCatching { Moonraker.cameraUrl(address, camera.stream) }.getOrNull() }
    if(endpoint == null || camera.service != "webrtc-camerastreamer") {
        Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f))
        Text("Live video format unavailable. This version supports camera-streamer WebRTC.", style=MaterialTheme.typography.bodySmall)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if(event==Lifecycle.Event.ON_START)active=true else if(event==Lifecycle.Event.ON_STOP)active=false }
        lifecycle.addObserver(observer);onDispose { lifecycle.removeObserver(observer) }
    }
    var web by remember(endpoint) { mutableStateOf<WebView?>(null) }
    var label by remember(endpoint, active) { mutableStateOf("Connecting live video…") }
    if(active) {
        val url=endpoint.toString()
        val html=remember(endpoint) {
            val origin=endpoint.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
            context.assets.open("camera.html").bufferedReader().use { it.readText() }
                .replace("__ORIGIN__",origin).replace("__ENDPOINT__",JSONObject.quote(url))
        }
        AndroidView(modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f), factory={ ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled=true
                settings.mediaPlaybackRequiresUserGesture=false
                settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.domStorageEnabled=false
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                        if(request.url.toString()==url && request.method=="POST")return null
                        return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                    }
                }
                loadDataWithBaseURL(url,html,"text/html","UTF-8",null)
                web=this
            }
        }, onRelease={ view ->
            web=null
            view.evaluateJavascript("window.stopCamera && window.stopCamera()",null)
            view.stopLoading();view.loadUrl("about:blank");view.onPause();view.removeAllViews();view.destroy()
        })
    }
    LaunchedEffect(web, active) {
        val view=web ?: return@LaunchedEffect
        if(!active)return@LaunchedEffect
        var priorFrames=0L;var priorTime=System.nanoTime()
        while(true){
            delay(1000)
            view.evaluateJavascript("JSON.stringify(window.cameraStats || {})") { raw ->
                runCatching {
                    val parsed=JSONTokener(raw).nextValue() as? String ?: return@runCatching
                    val stats=JSONObject(parsed);val count=stats.optLong("frames");val now=System.nanoTime()
                    val fps=(count-priorFrames).coerceAtLeast(0)*1_000_000_000.0/(now-priorTime).coerceAtLeast(1)
                    label=if(stats.optString("state")=="Playing" && fps>0) String.format(Locale.US,"Live video • %.0f fps",fps) else "Live video • ${stats.optString("state","Connecting")}"
                    priorFrames=count;priorTime=now
                }
            }
        }
    }
    Text(label,style=MaterialTheme.typography.bodySmall)
}
