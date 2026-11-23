package com.example.ui.screens.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

private class ManagedWebViewState {
    var webView: WebView? = null
    var isDisposed: Boolean = false
}

private data class YouTubeDestination(
    val label: String,
    val url: String,
    val icon: ImageVector
)

private val quickDestinations = listOf(
    YouTubeDestination("Home", "https://m.youtube.com", Icons.Rounded.Home),
    YouTubeDestination("Shorts", "https://m.youtube.com/shorts", Icons.Rounded.PlayArrow),
    YouTubeDestination("Trending", "https://m.youtube.com/feed/trending", Icons.Rounded.Whatshot),
    YouTubeDestination("Music", "https://music.youtube.com", Icons.Rounded.MusicNote),
    YouTubeDestination("Subscriptions", "https://m.youtube.com/feed/subscriptions", Icons.Rounded.Subscriptions),
    YouTubeDestination("Library", "https://m.youtube.com/feed/library", Icons.Rounded.Bookmark)
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String? = null,
    onBack: () -> Unit,
    onSendToDownloader: (String) -> Unit,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current
    val browserSettings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val startUrl = remember(initialUrl) {
        initialUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: YOUTUBE_MOBILE_HOME
    }

    val adBlockerEnabled by rememberUpdatedState(browserSettings.browserAdBlockerEnabled)
    var address by remember(startUrl) { mutableStateOf(startUrl) }
    var isEditingAddress by remember { mutableStateOf(false) }
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var blockedRequestsCount by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var overflowExpanded by remember { mutableStateOf(false) }

    var webViewGeneration by remember { mutableIntStateOf(0) }
    var initialUrlForInstance by remember(startUrl) { mutableStateOf(startUrl) }
    var rendererCrashCount by remember { mutableIntStateOf(0) }
    var rendererCrashedOut by remember { mutableStateOf(false) }
    var useSoftwareLayerFallback by remember { mutableStateOf(false) }
    var pendingRendererRecovery by remember { mutableStateOf(false) }

    val defaultUserAgent = remember(context) { WebSettings.getDefaultUserAgent(context) }

    fun syncNavigation(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    fun applyPageUrl(url: String?, title: String?) {
        val cleanUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        currentUrl = cleanUrl
        if (!isEditingAddress) {
            address = cleanUrl
        }
        if (!title.isNullOrBlank()) {
            pageTitle = title
        }
    }

    fun exitFullscreen() {
        val view = fullscreenView
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        (view?.parent as? ViewGroup)?.removeView(view)
    }

    fun disposeWebViewInstance(holder: ManagedWebViewState) {
        if (holder.isDisposed) return
        holder.isDisposed = true
        val view = holder.webView ?: return
        runCatching {
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeJavascriptInterface("StreamVaultBridge")
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.removeAllViews()
            view.destroy()
        }
    }

    val webViewHolder = remember(webViewGeneration) {
        val state = ManagedWebViewState()
        val instance = WebView(context).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
            if (useSoftwareLayerFallback) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = if (browserSettings.browserDesktopMode) {
                DESKTOP_USER_AGENT
            } else {
                defaultUserAgent
            }
            runCatching {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            val bridge = object {
                @JavascriptInterface
                fun onUrlUpdate(newUrl: String?, newTitle: String?) {
                    post {
                        if (state.isDisposed) return@post
                        applyPageUrl(newUrl, newTitle)
                        syncNavigation(this@apply)
                    }
                }
            }
            addJavascriptInterface(bridge, "StreamVaultBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (state.isDisposed) return null
                    if (adBlockerEnabled && !request.isForMainFrame && BrowserAdBlocker.shouldBlock(request.url)) {
                        view.post {
                            if (!state.isDisposed) blockedRequestsCount++
                        }
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

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (state.isDisposed) return
                    super.doUpdateVisitedHistory(view, url, isReload)
                    applyPageUrl(url, view.title)
                    syncNavigation(view)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (state.isDisposed) return
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    progress = 0
                    loadError = null
                    applyPageUrl(url, view.title)
                    syncNavigation(view)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (state.isDisposed) return
                    super.onPageFinished(view, url)
                    isLoading = false
                    progress = 100
                    applyPageUrl(url, view.title)
                    syncNavigation(view)
                    if (url != null && BrowserAdBlocker.isYouTubeHost(url)) {
                        view.evaluateJavascript(
                            BrowserAdBlocker.buildHelperScript(adBlockerEnabled),
                            null
                        )
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (state.isDisposed) return
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        isLoading = false
                        loadError = error.description?.toString() ?: "Page could not be loaded."
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError
                ) {
                    handler.cancel()
                    if (state.isDisposed) return
                    isLoading = false
                    loadError = "Secure connection failed."
                }

                @RequiresApi(Build.VERSION_CODES.O)
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    exitFullscreen()
                    disposeWebViewInstance(state)
                    isLoading = false
                    canGoBack = false
                    canGoForward = false

                    val didCrash = detail.didCrash()
                    if (didCrash) {
                        useSoftwareLayerFallback = true
                    }
                    initialUrlForInstance = currentUrl
                        .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?: YOUTUBE_MOBILE_HOME

                    val nextCrashCount = rendererCrashCount + 1
                    rendererCrashCount = nextCrashCount
                    if (nextCrashCount <= MAX_RENDERER_RECOVERY_ATTEMPTS) {
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            webViewGeneration++
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (didCrash) {
                                        "Browser recovered in safe graphics mode"
                                    } else {
                                        "Browser tab reloaded after system cleanup"
                                    }
                                )
                            }
                        } else {
                            pendingRendererRecovery = true
                        }
                    } else {
                        rendererCrashedOut = true
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (state.isDisposed) return
                    progress = newProgress.coerceIn(0, 100)
                    isLoading = newProgress < 100
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (state.isDisposed) return
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank()) pageTitle = title
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (state.isDisposed) return
                    (view.parent as? ViewGroup)?.removeView(view)
                    fullscreenView?.let { oldView -> (oldView.parent as? ViewGroup)?.removeView(oldView) }
                    fullscreenView = view
                    fullscreenCallback = callback
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
            loadUrl(initialUrlForInstance)
        }
        state.webView = instance
        state
    }
    val webView = webViewHolder.webView!!

    // Apply desktop vs mobile user-agent and reload when toggled
    var appliedDesktopMode by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(browserSettings.browserDesktopMode, webViewHolder) {
        if (webViewHolder.isDisposed) return@LaunchedEffect
        val desktop = browserSettings.browserDesktopMode
        val desiredUa = if (desktop) DESKTOP_USER_AGENT else defaultUserAgent
        if (webView.settings.userAgentString != desiredUa) {
            webView.settings.userAgentString = desiredUa
        }
        if (appliedDesktopMode != null && appliedDesktopMode != desktop && !rendererCrashedOut) {
            val target = if (desktop) {
                currentUrl.replace("://m.youtube.com", "://www.youtube.com")
            } else {
                currentUrl.replace("://www.youtube.com", "://m.youtube.com")
            }
            webView.loadUrl(target)
        }
        appliedDesktopMode = desktop
    }

    // Update cosmetic ad script live if user toggles ad blocker inside BrowserScreen
    LaunchedEffect(browserSettings.browserAdBlockerEnabled, webViewHolder) {
        if (!webViewHolder.isDisposed && !rendererCrashedOut && BrowserAdBlocker.isYouTubeHost(currentUrl)) {
            webView.evaluateJavascript(
                BrowserAdBlocker.buildHelperScript(browserSettings.browserAdBlockerEnabled),
                null
            )
        }
    }

    // Pause WebView timers/media when app enters background and run deferred renderer recovery on resume
    DisposableEffect(lifecycleOwner, webViewHolder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!webViewHolder.isDisposed && !rendererCrashedOut) {
                        webView.onPause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (pendingRendererRecovery) {
                        pendingRendererRecovery = false
                        webViewGeneration++
                    } else if (!webViewHolder.isDisposed && !rendererCrashedOut) {
                        webView.onResume()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun recoverWithUrl(targetUrl: String) {
        rendererCrashCount = 0
        rendererCrashedOut = false
        pendingRendererRecovery = false
        loadError = null
        initialUrlForInstance = targetUrl
        address = targetUrl
        currentUrl = targetUrl
        webViewGeneration++
    }

    fun loadAddress(rawInput: String = address) {
        val input = rawInput.trim()
        if (input.isBlank()) {
            loadError = "Enter a website or search term."
            return
        }
        val baseHost = if (browserSettings.browserDesktopMode) "www.youtube.com" else "m.youtube.com"
        val candidate = when {
            input.startsWith("https://", ignoreCase = true) || input.startsWith("http://", ignoreCase = true) -> input
            input.contains(' ') || !input.contains('.') -> "https://$baseHost/results?search_query=${Uri.encode(input)}"
            else -> "https://$input"
        }
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
        if (parsed == null || parsed.host.isNullOrBlank() || parsed.scheme !in setOf("http", "https")) {
            loadError = "Enter a valid website or search term."
            return
        }
        keyboard?.hide()
        isEditingAddress = false
        val targetUrl = parsed.toString()
        address = targetUrl
        if (webViewHolder.isDisposed || rendererCrashedOut) {
            recoverWithUrl(targetUrl)
        } else {
            rendererCrashCount = 0
            webView.loadUrl(targetUrl)
        }
    }

    BackHandler(enabled = fullscreenView != null || canGoBack) {
        if (fullscreenView != null) {
            exitFullscreen()
        } else if (!webViewHolder.isDisposed && webView.canGoBack()) {
            webView.goBack()
        }
    }

    DisposableEffect(webViewHolder) {
        onDispose {
            exitFullscreen()
            disposeWebViewInstance(webViewHolder)
        }
    }

    val mediaTarget = remember(currentUrl) { BrowserAdBlocker.detectMediaTarget(currentUrl) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = pageTitle.ifBlank { "YouTube Browser" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentUrl.toHostLabel().ifBlank { "youtube.com" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Home")
                        }
                    },
                    actions = {
                        Surface(
                            onClick = { viewModel.setAdBlockerEnabled(!browserSettings.browserAdBlockerEnabled) },
                            shape = RoundedCornerShape(999.dp),
                            color = if (browserSettings.browserAdBlockerEnabled) {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Shield,
                                    contentDescription = "Toggle ad filter",
                                    modifier = Modifier.size(14.dp),
                                    tint = if (browserSettings.browserAdBlockerEnabled) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = if (browserSettings.browserAdBlockerEnabled) {
                                        if (blockedRequestsCount > 0) "$blockedRequestsCount blocked" else "Shield On"
                                    } else {
                                        "Shield Off"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (browserSettings.browserAdBlockerEnabled) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.setDesktopMode(!browserSettings.browserDesktopMode) },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (browserSettings.browserDesktopMode) {
                                    Icons.Rounded.DesktopWindows
                                } else {
                                    Icons.Rounded.PhoneAndroid
                                },
                                contentDescription = if (browserSettings.browserDesktopMode) {
                                    "Switch to mobile view"
                                } else {
                                    "Switch to desktop view"
                                },
                                tint = if (browserSettings.browserDesktopMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { overflowExpanded = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Browser options")
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Current URL") },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        overflowExpanded = false
                                        copyToClipboard(context, currentUrl)
                                        scope.launch { snackbarHostState.showSnackbar("Link copied to clipboard") }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open in External Browser") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        overflowExpanded = false
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear Cookies & Cache") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                    onClick = {
                                        overflowExpanded = false
                                        runCatching {
                                            CookieManager.getInstance().removeAllCookies(null)
                                            CookieManager.getInstance().flush()
                                            WebStorage.getInstance().deleteAllData()
                                            if (!webViewHolder.isDisposed && !rendererCrashedOut) {
                                                webView.clearCache(true)
                                                webView.reload()
                                            } else {
                                                recoverWithUrl(currentUrl.ifBlank { YOUTUBE_MOBILE_HOME })
                                            }
                                        }
                                        scope.launch { snackbarHostState.showSnackbar("Browser cookies & cache cleared") }
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedIconButton(
                                onClick = {
                                    copyToClipboard(context, currentUrl)
                                    scope.launch { snackbarHostState.showSnackbar("URL copied") }
                                },
                                enabled = currentUrl.startsWith("http"),
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy link")
                            }

                            Button(
                                onClick = { onSendToDownloader(currentUrl) },
                                enabled = currentUrl.startsWith("https://") || currentUrl.startsWith("http://"),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("browser_send_to_downloader"),
                                shape = RoundedCornerShape(15.dp),
                                colors = if (mediaTarget.isDirectMedia) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(
                                        text = mediaTarget.ctaLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (mediaTarget.isDirectMedia) {
                                        Text(
                                            text = mediaTarget.badgeLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Omnibox + Navigation Controls
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { isEditingAddress = it.isFocused }
                                    .testTag("browser_address_field"),
                                singleLine = true,
                                placeholder = { Text("Search YouTube or enter URL") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (currentUrl.startsWith("https://")) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isEditingAddress && address.isNotEmpty()) {
                                            IconButton(onClick = { address = "" }) {
                                                Icon(Icons.Rounded.Clear, contentDescription = "Clear address")
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                if (webViewHolder.isDisposed || rendererCrashedOut) {
                                                    recoverWithUrl(currentUrl.ifBlank { YOUTUBE_MOBILE_HOME })
                                                } else if (isLoading) {
                                                    webView.stopLoading()
                                                    isLoading = false
                                                } else {
                                                    rendererCrashCount = 0
                                                    webView.reload()
                                                }
                                            }
                                        ) {
                                            Icon(
                                                if (isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                                                contentDescription = if (isLoading) "Stop loading" else "Reload page"
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = { loadAddress() })
                            )
                        }

                        // Nav buttons + Quick YouTube Destination Chips
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            if (!webViewHolder.isDisposed && !rendererCrashedOut) {
                                                webView.goBack()
                                            }
                                        },
                                        enabled = canGoBack && !rendererCrashedOut,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Previous page")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (!webViewHolder.isDisposed && !rendererCrashedOut) {
                                                webView.goForward()
                                            }
                                        },
                                        enabled = canGoForward && !rendererCrashedOut,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Next page")
                                    }
                                }
                            }
                            items(quickDestinations) { dest ->
                                val targetUrl = if (browserSettings.browserDesktopMode) {
                                    dest.url.replace("://m.youtube.com", "://www.youtube.com")
                                } else {
                                    dest.url
                                }
                                val selected = currentUrl.trimEnd('/') == targetUrl.trimEnd('/')
                                FilterChip(
                                    selected = selected,
                                    onClick = { loadAddress(targetUrl) },
                                    leadingIcon = {
                                        Icon(dest.icon, contentDescription = null, modifier = Modifier.size(15.dp))
                                    },
                                    label = { Text(dest.label, style = MaterialTheme.typography.labelMedium) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

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
                            Text(
                                message,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = {
                                    loadError = null
                                    if (webViewHolder.isDisposed || rendererCrashedOut) {
                                        recoverWithUrl(currentUrl.ifBlank { YOUTUBE_MOBILE_HOME })
                                    } else {
                                        webView.reload()
                                    }
                                }
                            ) { Text("Retry") }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (rendererCrashedOut) {
                        RendererCrashRecoveryCard(
                            useSafeGraphicsMode = useSoftwareLayerFallback,
                            onReloadPage = {
                                recoverWithUrl(currentUrl.ifBlank { YOUTUBE_MOBILE_HOME })
                            },
                            onGoHome = {
                                val homeUrl = if (browserSettings.browserDesktopMode) {
                                    YOUTUBE_DESKTOP_HOME
                                } else {
                                    YOUTUBE_MOBILE_HOME
                                }
                                recoverWithUrl(homeUrl)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        key(webViewHolder) {
                            AndroidView(
                                factory = {
                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                    webView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        fullscreenView?.let { view ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                IconButton(
                    onClick = { exitFullscreen() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Exit full screen", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun RendererCrashRecoveryCard(
    useSafeGraphicsMode: Boolean,
    onReloadPage: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(14.dp)
                            .size(28.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Text(
                    text = "Browser page stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (useSafeGraphicsMode) {
                        "The web renderer stopped on this page. Safe graphics mode is enabled for the next load."
                    } else {
                        "The web renderer closed unexpectedly to free system resources."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onGoHome,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_recovery_home"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Home")
                    }
                    Button(
                        onClick = onReloadPage,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_recovery_reload"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reload")
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("YouTube URL", text))
}

private fun String.toHostLabel(): String = runCatching { Uri.parse(this).host }.getOrNull().orEmpty()

private const val MAX_RENDERER_RECOVERY_ATTEMPTS = 2
private const val YOUTUBE_MOBILE_HOME = "https://m.youtube.com"
private const val YOUTUBE_DESKTOP_HOME = "https://www.youtube.com"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
