package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.spotui.ui.theme.*

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SetupWebPage(title: String, url: String, status: String, busy: Boolean,
    onRetry: () -> Unit, onBack: () -> Unit, onReady: (WebView) -> Unit,
    onPageError: (String) -> Unit, spotifyLayout: Boolean = false, onNavigation: (String) -> Boolean = { false }) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pageMessage by remember { mutableStateOf("Loading sign-in page…") }
    var rendererGone by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            kotlinx.coroutines.delay(25_000)
            pageMessage = "The page is taking longer than expected. Check your connection or tap Retry."
        }
    }
    val currentNavigation by rememberUpdatedState(onNavigation)
    val currentError by rememberUpdatedState(onPageError)
    DisposableEffect(Unit) { onDispose { browser?.stopLoading(); browser?.destroy(); browser = null } }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AppBackground).safeDrawingPadding().imePadding()) {
        Text(title, color = AppText, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        if (!rendererGone) AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            WebView(ctx).apply {
                browser = this
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.WHITE)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        loading = true; pageMessage = "Loading sign-in page…"
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        loading = false; pageMessage = ""
                        if (spotifyLayout) fixSpotifyLoginLayout(view)
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererGone = true; loading = false
                        pageMessage = "The sign-in page stopped responding. Tap Retry to reopen it."
                        currentError(pageMessage)
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        if (request.isForMainFrame) currentNavigation(request.url.toString()) else false
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            loading = false; pageMessage = "The page could not load. Check your connection and retry."
                            currentError(pageMessage)
                        }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) {
                            loading = false; pageMessage = "The service returned an error (${response.statusCode}). Please retry later."
                            currentError(pageMessage)
                        }
                    }
                }
                onReady(this)
                loadUrl(url)
            }
        })
        else Spacer(Modifier.weight(1f))
        if (pageMessage.isNotBlank()) Text(pageMessage, color = AppSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (busy || loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AppPalette)
        Text(status.ifBlank { "Complete the service's sign-in on the page above." }, color = AppSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onBack) { Text("Back to setup", color = AppSecondary) }
            TextButton(onClick = onRetry, enabled = !busy) { Text("Retry", color = AppPalette) }
        }
    }
}

/** Retains Spotify's existing viewport workaround, scoped to Spotify-owned pages. */
private fun fixSpotifyLoginLayout(view: WebView) {
    val host = android.net.Uri.parse(view.url.orEmpty()).host.orEmpty()
    if (host != "spotify.com" && !host.endsWith(".spotify.com")) return
    view.post {
        view.evaluateJavascript("""
            (function() {
                var h = Math.max(1, window.innerHeight || document.documentElement.clientHeight);
                var style = document.getElementById('spotui-login-layout-fix');
                if (!style) { style = document.createElement('style'); style.id = 'spotui-login-layout-fix'; document.head.appendChild(style); }
                style.textContent = 'html,body,#__next{min-height:' + h + 'px!important;height:auto!important;}' +
                    'main{min-height:' + h + 'px!important;height:auto!important;max-height:none!important;position:relative!important;overflow:auto!important;}';
            })();
        """.trimIndent(), null)
    }
}
