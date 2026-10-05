package com.agentx.app.model.android

import android.content.Context
import android.content.SharedPreferences
import com.agentx.app.model.ratelimit.RateLimitProfile
import com.agentx.app.model.ratelimit.RateLimitProfileCodec
import com.agentx.app.model.ratelimit.RateLimitProfileStore

/**
 * Persists configured rate-limit profiles in app-private [SharedPreferences] — the
 * same mechanism the model presets and the discovered catalog already use, so no
 * second database is introduced.
 *
 * A profile is configuration, not a credential: a provider/catalog identity and a
 * ceiling. [RateLimitProfileCodec] has no field for a key, a header or an endpoint,
 * so nothing secret can be written here — which is what makes an ordinary
 * preference file an acceptable home for it. Credentials stay in
 * [KeystoreModelSecretStore].
 *
 * [snapshot] reads the same store synchronously. That is what lets a configured
 * quota be in force for the first request after a restart instead of after the
 * first suspending load completes: admission control runs on the request path and
 * cannot wait for it.
 */
class SharedPreferencesRateLimitProfileStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : RateLimitProfileStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun load(): List<RateLimitProfile> = snapshot()

    override fun snapshot(): List<RateLimitProfile> =
        prefs.getString(KEY_PROFILES, null)
            ?.let { stored -> RateLimitProfileCodec.decodeAll(stored) }
            // A record that cannot be read is treated as absent and the next save
            // rewrites it, rather than crashing startup or inventing a limit.
            .orEmpty()

    override suspend fun save(profiles: List<RateLimitProfile>) {
        prefs.edit()
            .putString(KEY_PROFILES, RateLimitProfileCodec.encodeAll(profiles))
            .apply()
    }

    companion object {
        /** Alongside the model presets; the keys do not collide. */
        const val DEFAULT_PREFERENCES_NAME: String = "forge.models"

        private const val KEY_PROFILES = "models.ratelimits"
    }
}
