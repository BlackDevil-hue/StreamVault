package com.example.ui.screens.browser

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    onSendToDownloader: (String) -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by remember { mutableStateOf(YOUTUBE_HOME) }
    var currentUrl by remember { mutableStateOf(YOUTUBE_HOME) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    fun syncNavigation(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (!request.isForMainFrame && BrowserAdBlocker.shouldBlock(request.url)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !request.url.scheme.equals("https", ignoreCase = true) &&
                        !request.url.scheme.equals("http", ignoreCase = true)

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    progress = 0
                    loadError = null
                    url?.let {
                        currentUrl = it
                        address = it
                    }
                    syncNavigation(view)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    progress = 100
                    url?.let {
                        currentUrl = it
                        address = it
                    }
                    pageTitle = view.title.orEmpty()
                    syncNavigation(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        isLoading = false
                        loadError = error.description?.toString() ?: "Page could not be loaded."
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                    isLoading = false
                    loadError = "Secure connection failed."
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress.coerceIn(0, 100)
                    isLoading = newProgress < 100
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    fullscreenView?.let { oldView -> (oldView.parent as? ViewGroup)?.removeView(oldView) }
                    fullscreenView = view
                    fullscreenCallback = callback
                }

                override fun onHideCustomView() {
                    val shownView = fullscreenView
                    fullscreenView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                    (shownView?.parent as? ViewGroup)?.removeView(shownView)
                }
            }
            loadUrl(YOUTUBE_HOME)
        }
    }

    fun loadAddress() {
        val input = address.trim()
        if (input.isBlank()) {
            loadError = "Enter a website or search term."
            return
        }
        val candidate = when {
            input.startsWith("https://", ignoreCase = true) || input.startsWith("http://", ignoreCase = true) -> input
            input.contains(' ') || !input.contains('.') -> "https://www.youtube.com/results?search_query=${Uri.encode(input)}"
            else -> "https://$input"
        }
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
        if (parsed == null || parsed.host.isNullOrBlank() || parsed.scheme !in setOf("http", "https")) {
            loadError = "Enter a valid website or search term."
            return
        }
        keyboard?.hide()
        address = parsed.toString()
        webView.loadUrl(address)
    }

    fun exitFullscreen() {
        val view = fullscreenView
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    BackHandler(enabled = fullscreenView != null || canGoBack) {
        if (fullscreenView != null) exitFullscreen() else if (webView.canGoBack()) webView.goBack()
    }

    DisposableEffect(webView) {
        onDispose {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            fullscreenView = null
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("YouTube browser")
                            Text(
                                pageTitle.ifBlank { currentUrl.toHostLabel() },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Home")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = {
                Button(
                    onClick = { onSendToDownloader(currentUrl) },
                    enabled = currentUrl.startsWith("https://") || currentUrl.startsWith("http://"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(52.dp)
                        .testTag("browser_send_to_downloader"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send page to downloader")
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = { webView.goBack() }, enabled = canGoBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Previous page")
                    }
                    IconButton(onClick = { webView.goForward() }, enabled = canGoForward) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Next page")
                    }
                    IconButton(onClick = { webView.loadUrl(YOUTUBE_HOME) }) {
                        Icon(Icons.Rounded.Home, contentDescription = "YouTube home")
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.weight(1f).testTag("browser_address_field"),
                        singleLine = true,
                        placeholder = { Text("Search YouTube or enter a URL") },
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { loadAddress() })
                    )
                    IconButton(
                        onClick = { if (isLoading) webView.stopLoading() else webView.reload(); isLoading = false },
                        enabled = currentUrl.isNotBlank()
                    ) {
                        Icon(
                            if (isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                            contentDescription = if (isLoading) "Stop loading" else "Reload page"
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        "Ad & tracker filtering enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                loadError?.let { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { loadError = null; webView.reload() }) { Text("Retry") }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        fullscreenView?.let { view ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                IconButton(
                    onClick = { exitFullscreen() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Exit full screen", tint = Color.White)
                }
            }
        }
    }
}

private fun String.toHostLabel(): String = runCatching { Uri.parse(this).host }.getOrNull().orEmpty()

private const val YOUTUBE_HOME = "https://www.youtube.com"
