package dev.forge.ide.ui.ide.data

import android.os.Bundle
import android.webkit.WebView
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the single WebView used to manage a model runtime — the "Model Runner".
 *
 * This is a focused runtime browser, not a general-purpose one: navigation is
 * restricted to the notebook host and the Google sign-in hosts, and the screen
 * never inspects page content. It exists so the user can start and watch the
 * runtime that serves the model.
 *
 * It is also deliberately **not** on the agent's request path. The agent talks to
 * the model endpoint through the Model Gateway; the browser is a control surface.
 * Because of that, the retained instance can outlive the screen: the state is
 * [persistent] for the process, [saveState]/[restoreState] carry it across
 * configuration changes, and closing the screen never stops the model.
 */
interface ModelRunnerBrowserHost {

    /** Whether the platform allowed a browser to be created at all. */
    val available: Boolean

    /**
     * The last navigation this host refused, so the UI can explain it instead of
     * appearing broken. Null when nothing has been blocked.
     */
    val blockedNavigation: StateFlow<String?>

    /**
     * Returns the retained WebView, creating it (and loading [url]) the first time
     * it is asked for [presetId]. [outputMarker] is the preset's marker, so a page
     * that logs an endpoint with it can be forwarded to endpoint discovery without
     * hardcoding a marker or a URL here. Returns null when no browser is available.
     */
    fun view(presetId: String, url: String, outputMarker: String): WebView?

    /** Marks that the screen showing [presetId] is gone. The browser itself stays. */
    fun onSessionDetached(presetId: String)

    /** Releases the WebView. Only the hosting activity should call this. */
    fun release()

    fun saveState(out: Bundle)

    fun restoreState(state: Bundle?)

    /** Opens [url] in the user's own browser, for runtimes an embedded WebView cannot host. */
    fun openExternally(url: String)

    fun clearBlockedNavigation()
}
