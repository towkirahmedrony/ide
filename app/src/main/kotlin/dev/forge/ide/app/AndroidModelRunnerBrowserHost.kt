package dev.forge.ide.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import dev.forge.ide.model.json.Json
import dev.forge.ide.model.json.JsonCodec
import dev.forge.ide.model.preset.ColabRuntimeConfig
import dev.forge.ide.model.runtime.RuntimeOutputBuffer
import dev.forge.ide.ui.ide.data.ModelRunnerBrowserHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android WebView implementation of the Model Runner browser.
 *
 * Limitations this class is explicit about rather than papering over:
 * - A WebView cannot create or keep a Colab runtime alive; it can only display the
 *   notebook the user drives. Google may also refuse to run Colab in an embedded
 *   WebView at all, which is why [openExternally] exists as a documented fallback.
 * - Notebook *cell* output is rendered in the page, not on the JavaScript console,
 *   so the console bridge only forwards output a notebook explicitly logs. The
 *   reliable path for endpoint discovery is pasting the runtime output in the
 *   Model Runner screen, or configuring the endpoint directly.
 * - The retained instance keeps its session while the process lives; a process
 *   restart relies on WebView cookies (the Google session) plus the saved
 *   navigation state.
 *
 * Navigation is restricted to the notebook host and Google's own hosts. Anything
 * else is refused and reported so the UI can explain it.
 */
class AndroidModelRunnerBrowserHost(
    private val activity: ComponentActivity,
    private val runtimeOutput: RuntimeOutputBuffer,
) : ModelRunnerBrowserHost {

    private var webView: WebView? = null
    private var pendingState: Bundle? = null
    private var appliedMarker: String? = null

    private val blocked = MutableStateFlow<String?>(null)

    override val available: Boolean = true

    override val blockedNavigation: StateFlow<String?> = blocked

    override fun view(presetId: String, url: String, outputMarker: String): WebView? {
        val existing = webView
        if (existing != null) {
            if (outputMarker != appliedMarker) {
                appliedMarker = outputMarker
                existing.evaluateJavascript(consoleBridge(outputMarker), null)
            }
            if (existing.url.isNullOrBlank() && url.isNotBlank()) existing.loadUrl(url)
            return existing
        }

        val created = try {
            WebView(activity)
        } catch (_: Throwable) {
            // No WebView on this device: the screen reports it and offers the fallback.
            return null
        }

        created.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }

        // Colab sign-in needs cookies, including third-party ones.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)

        created.webViewClient = RunnerWebViewClient()
        created.addJavascriptInterface(OutputBridge(), BRIDGE_NAME)
        created.evaluateJavascript(consoleBridge(outputMarker), null)
        appliedMarker = outputMarker
        webView = created

        val restored = pendingState
        if (restored != null) {
            created.restoreState(restored)
            pendingState = null
        } else if (url.isNotBlank()) {
            created.loadUrl(url)
        }
        return created
    }

    override fun onSessionDetached(presetId: String) = Unit

    override fun release() {
        val view = webView ?: return
        webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading() }
        runCatching { view.loadUrl(BLANK_PAGE) }
        runCatching { view.destroy() }
    }

    override fun saveState(out: Bundle) {
        webView?.saveState(out)
    }

    override fun restoreState(state: Bundle?) {
        pendingState = state
    }

    override fun openExternally(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { activity.startActivity(Intent.createChooser(intent, "Open notebook")) }
    }

    override fun clearBlockedNavigation() {
        blocked.value = null
    }

    private inner class RunnerWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (isAllowed(url)) return false
            blocked.value = Uri.parse(url).host ?: url
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            appliedMarker?.let { view.evaluateJavascript(consoleBridge(it), null) }
        }
    }

    /** Receives marker lines the page logs. Only that text ever crosses the bridge. */
    private inner class OutputBridge {

        @JavascriptInterface
        fun onRuntimeOutput(line: String) {
            runtimeOutput.append(line)
        }
    }

    private fun isAllowed(url: String): Boolean {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "about", "data", "blob" -> true
            "https" -> {
                val host = uri.host?.lowercase() ?: return false
                host == ColabRuntimeConfig.NOTEBOOK_HOST ||
                    host == "accounts.google.com" ||
                    host == "google.com" ||
                    host.endsWith(".google.com") ||
                    host.endsWith(".googleusercontent.com")
            }

            else -> false
        }
    }

    /**
     * Forwards only the lines a notebook explicitly logs. The marker comes from the
     * preset, so nothing about a specific model or tunnel is baked in.
     */
    private fun consoleBridge(marker: String): String {
        val escaped = JsonCodec.encode(Json.of(marker))
        return """
            (function () {
              if (window.$HOOK_FLAG) return;
              window.$HOOK_FLAG = true;
              var marker = $escaped;
              var original = console.log.bind(console);
              console.log = function () {
                try {
                  for (var i = 0; i < arguments.length; i++) {
                    var value = String(arguments[i]);
                    if (value.indexOf(marker) >= 0) {
                      window.$BRIDGE_NAME.onRuntimeOutput(value);
                    }
                  }
                } catch (e) { }
                return original.apply(console, arguments);
              };
            })();
        """.trimIndent()
    }

    private companion object {
        const val BRIDGE_NAME = "forgeRuntime"
        const val HOOK_FLAG = "__forgeConsoleHooked"
        const val BLANK_PAGE = "about:blank"
    }
}
