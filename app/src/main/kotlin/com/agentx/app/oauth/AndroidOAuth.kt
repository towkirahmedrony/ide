package com.agentx.app.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentx.app.ui.ide.data.OAuthBrowserLauncher

/**
 * Opens the provider's authorization page in the user's own browser.
 *
 * The app never renders a login form and never asks for a provider password: the
 * URL handed over is the provider's official authorization endpoint, carrying only
 * the public client id, the redirect URI, the `state` value and the PKCE
 * challenge. Custom Tabs are deliberately not required, so the flow also works on
 * devices whose default browser is not Chrome; the redirect still comes back to
 * the app through its registered deep link.
 */
class IntentOAuthBrowserLauncher(private val context: Context) : OAuthBrowserLauncher {

    override fun launch(authorizationUrl: String): Boolean {
        if (authorizationUrl.isBlank()) return false
        val uri = runCatching { Uri.parse(authorizationUrl) }.getOrNull() ?: return false
        // Only ever hand an https URL to the browser: an authorization page must be
        // reached over TLS.
        if (uri.scheme?.lowercase() != "https") return false

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (error: android.content.ActivityNotFoundException) {
            false
        } catch (error: SecurityException) {
            false
        }
    }
}
