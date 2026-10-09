package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.manager.ModelConnectionKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fresh-install role → model path, driven exactly as the application drives it.
 *
 * The reported incident was a fresh install that failed every conversation with:
 *
 * ```
 * MODEL_NOT_CONNECTED role=MAIN provider=openai-compatible defaultConnection=openai-compatible
 * model=devstral-24b reason=CONNECTION_NOT_CONNECTED fallbackAvailable=false explicit=false
 * ```
 *
 * `explicit=false` is the tell: MAIN was not following an assignment the user made. It
 * follows its built-in policy default, which names the local execution domain because the
 * role's policy says "MAIN runs on the local model" — not because anyone bound a
 * connection. Reading that domain as the user's own assignment made the resolver take its
 * assignment-failure branch and name a connection, and a model, the user had never
 * created; failing on it is what made a fresh install look like a broken pinned
 * assignment.
 *
 * These tests use the production pair — the real [AgentRoleModelRegistry] over a store, and
 * the real [AgentModelResolver] built the way `AgentModule` builds it (built-in defaults,
 * connections read live from the Model Manager, preferences read live from the registry) —
 * so they assert what a running agent actually receives, not a helper's output.
 * `AgentCoreTest` drives the same wiring through a real orchestrator run.
 *
 * Covered here: a fresh install with nothing configured; MAIN on its built-in default with
 * no user assignment; a user-pinned assignment whose connection is missing; a domain-pinned
 * assignment whose connection is missing and must not cross domains; a valid connected
 * assignment; a saved-but-disconnected connection; startup initialization and preference
 * restoration; and the `explicit` / `defaultConnection` / `fallback` diagnostics.
 */
class FreshInstallRoleModelResolutionTest {

    private val localFamily = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL

    private fun connection(
        providerId: String,
        model: String,
        connectionId: String = providerId,
        domain: ModelConnectionKind = testDomain(providerId),
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$connectionId.example/v1",
        model = model,
        connectionId = connectionId,
        connectionKind = domain,
    )

    /** The local model runtime MAIN is meant to run on. */
    private val localRuntime = connection(localFamily, AgentModelIds.DEVSTRAL_24B, "local-devstral")

    /**
     * The *active* configuration the session happens to be running on. Deliberately an API
     * one: it must never stand in for a local role, which is the cross-domain substitution
     * the domain constraint exists to prevent.
     */
    private val apiActive = connection(
        AgentModelProviders.FREELMAPI,
        AgentModelIds.FREELLMAPI_GEMINI,
        "free-llm-gateway",
    )

    private fun registry(store: AgentRoleModelStore = InMemoryAgentRoleModelStore()) =
        AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))

    /** The resolver `AgentModule` builds: built-in defaults, live connections, live prefs. */
    private fun resolver(
        registry: AgentRoleModelRegistry,
        vararg connections: ModelConfig,
    ) = AgentModelResolver(
        preferences = AgentModelPreferences.DEFAULT,
        connections = { connections.associateBy { it.connectionId } },
        livePreferences = { registry.preferences() },
    )

    // --- the fresh install --------------------------------------------------

    @Test
    fun `a fresh install reports MAIN as unconfigured, never as a phantom connection`() = runBlocking {
        // Nothing was ever saved, and the Model Manager publishes no connection at all.
        val registry = registry()
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry).resolveForRole(AgentRole.MAIN, default = apiActive)
        }

        // The state Settings already shows for this role, and the state the runtime uses
        // for "no model is configured" — not a connection the user created and lost.
        assertEquals(AgentErrorCode.NOT_CONFIGURED, failure.error.code)
        assertEquals("NO_CONNECTION_CONFIGURED", failure.error.details["reason"])
        assertEquals("MODEL_NOT_CONFIGURED", failure.error.details["cause"])
        assertEquals("default", failure.error.details["assignment"])
        assertEquals("false", failure.error.details["explicit"])

        // The default's own target is named as a default, and no connection record is
        // claimed: the reported incident named one that never existed.
        assertEquals(localFamily, failure.error.details["provider"])
        assertEquals(localFamily, failure.error.details["defaultConnection"])
        assertEquals(AgentModelIds.DEVSTRAL_24B, failure.error.details["model"])
        assertEquals("0", failure.error.details["connectionsInDomain"])
        assertNull(failure.error.details["connection"])
        assertFalse(failure.error.details.containsKey("candidates"))

        // Truthful and actionable: it says where the user fixes it.
        assertTrue(failure.error.message.contains("Settings"), failure.error.message)
        assertTrue(failure.error.message.contains("explicit=false"), failure.error.message)
    }

    @Test
    fun `MAIN on its built-in default is a default, and never takes a same-family API connection`() =
        runBlocking {
            val registry = registry()
            // A remote OpenAI-compatible endpoint is connected. It shares MAIN's provider
            // family but belongs to the other execution domain, so it is unusable for MAIN.
            val remoteOpenAiCompatible = connection(
                localFamily,
                AgentModelIds.QWEN_CODER,
                "remote-oai",
                ModelConnectionKind.API,
            )

            val failure = assertFailsWith<AgentModelResolutionException> {
                resolver(registry, remoteOpenAiCompatible).resolveForRole(AgentRole.MAIN, default = apiActive)
            }

            // No user assignment exists anywhere, for the resolver or for Settings.
            assertNull(registry.override(AgentRole.MAIN))
            assertEquals(AgentErrorCode.NOT_CONFIGURED, failure.error.code)
            assertEquals("default", failure.error.details["assignment"])
            assertEquals("false", failure.error.details["explicit"])
            assertEquals("LOCAL_CUSTOM", failure.error.details["domain"])
            // The connection that does exist is counted as what it is: present, same
            // family, wrong domain — reported, and never selected.
            assertEquals("0", failure.error.details["connectionsInDomain"])
            assertEquals("1", failure.error.details["sameFamilyOtherDomain"])
        }

    // --- the user's own assignment still fails as designed -------------------

    @Test
    fun `a user-pinned MAIN assignment with a missing connection still fails as designed`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        runBlocking {
            registry(store).save(
                AgentRole.MAIN,
                localFamily,
                AgentModelIds.DEVSTRAL_24B,
                connectionId = "my-local-runtime",
            )
        }

        // A restart: the app reloads the saved assignment at startup.
        val registry = registry(store)
        registry.load()
        assertEquals("my-local-runtime", registry.override(AgentRole.MAIN)?.connectionId)

        // The saved connection is not addressable right now, and a *different* local
        // connection of the same family is. The assignment names its own connection, so it
        // fails rather than being answered by its sibling.
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry, localRuntime).resolveForRole(AgentRole.MAIN, default = apiActive)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("CONNECTION_NOT_CONNECTED", failure.error.details["reason"])
        assertEquals("MODEL_NOT_CONNECTED", failure.error.details["cause"])
        assertEquals("user", failure.error.details["assignment"])
        assertEquals("true", failure.error.details["explicit"])
        assertEquals("my-local-runtime", failure.error.details["connection"])
        assertEquals(AgentModelIds.DEVSTRAL_24B, failure.error.details["model"])
        // The identity is preserved, so the assignment can be repaired deliberately.
        assertEquals("my-local-runtime", registry.override(AgentRole.MAIN)?.connectionId)
        assertTrue(failure.error.message.contains("connection=my-local-runtime"), failure.error.message)
    }

    @Test
    fun `a domain-pinned MAIN assignment never crosses into the other domain`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        // Saved from a model to a provider family, with no saved connection identity: the
        // assignment is identified by its provider identity *and* its domain.
        runBlocking { registry(store).save(AgentRole.MAIN, localFamily, AgentModelIds.DEVSTRAL_24B) }

        val registry = registry(store)
        registry.load()
        val remoteOpenAiCompatible = connection(
            localFamily,
            AgentModelIds.QWEN_CODER,
            "remote-oai",
            ModelConnectionKind.API,
        )

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry, remoteOpenAiCompatible).resolveForRole(AgentRole.MAIN, default = remoteOpenAiCompatible)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("CONNECTION_NOT_CONNECTED", failure.error.details["reason"])
        assertEquals("user", failure.error.details["assignment"])
        assertEquals(localFamily, failure.error.details["provider"])
        assertEquals("LOCAL_CUSTOM", failure.error.details["domain"])
        // The connection that exists is in the other domain: reported as unusable for this
        // assignment, and never selected.
        assertEquals("1", failure.error.details["sameFamilyOtherDomain"])
    }

    @Test
    fun `a saved assignment whose connection is disconnected is never answered by a sibling`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        runBlocking {
            registry(store).save(
                AgentRole.MAIN,
                localFamily,
                AgentModelIds.DEVSTRAL_24B,
                connectionId = "my-local-runtime",
            )
        }
        val registry = registry(store)
        registry.load()

        // Another connection of the same family sits in the same domain, so only the saved
        // identity distinguishes them — and the saved one is not addressable.
        val sibling = connection(localFamily, "qwen2.5-coder-14b", "other-local")
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry, sibling).resolveForRole(AgentRole.MAIN, default = apiActive)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("user", failure.error.details["assignment"])
        assertEquals("my-local-runtime", failure.error.details["connection"])
        // The sibling is not reported as the target and is never resolved to.
        assertFalse(failure.error.message.contains("connection=other-local"), failure.error.message)
    }

    // --- a valid assignment ------------------------------------------------

    @Test
    fun `MAIN runs on its connected local model and not on the active API model`() = runBlocking {
        val registry = registry()
        val result = resolver(registry, localRuntime).resolveForRole(AgentRole.MAIN, default = apiActive)

        assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
        assertEquals(localFamily, result.config.providerId)
        assertEquals(AgentModelIds.DEVSTRAL_24B, result.config.model)
        assertEquals("local-devstral", result.config.connectionId)
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, result.config.connectionKind)
        assertTrue(result.config.connectionId != apiActive.connectionId)
    }

    // --- startup -----------------------------------------------------------

    @Test
    fun `startup restores the saved assignment and a reset returns to the built-in default`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        runBlocking {
            registry(store).save(
                AgentRole.MAIN,
                localFamily,
                AgentModelIds.DEVSTRAL_24B,
                connectionId = localRuntime.connectionId,
            )
        }

        // What `MainActivity` does at startup, and again when the app returns to the
        // foreground: load the saved assignments over the built-in mapping.
        val registry = registry(store)
        registry.load()
        val restored = resolver(registry, localRuntime).resolveForRole(AgentRole.MAIN, default = apiActive)
        assertTrue(restored.eligible, restored.errorOrNull()?.message.orEmpty())
        assertEquals(localRuntime.connectionId, restored.config.connectionId)

        // A cleared entry falls back to the built-in default rather than leaving the role
        // unconfigured — and that default is a policy, not an assignment.
        val default = registry.default(AgentRole.MAIN)!!
        assertEquals(localFamily, default.providerId)
        assertEquals(AgentModelIds.DEVSTRAL_24B, default.model)
        assertFalse(default.explicit)

        registry.reset(AgentRole.MAIN)
        assertNull(registry.override(AgentRole.MAIN))
        assertEquals(default, registry.preferences()[AgentRole.MAIN])
    }

    // --- diagnostics -------------------------------------------------------

    @Test
    fun `explicit, defaultConnection and fallback are reported truthfully`() = runBlocking {
        // (a) the built-in default: no connection claimed, no assignment implied.
        val defaultFailure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry()).resolveForRole(AgentRole.MAIN, default = apiActive)
        }.error
        assertEquals("false", defaultFailure.details["explicit"])
        assertEquals("default", defaultFailure.details["assignment"])
        assertEquals("false", defaultFailure.details["fallbackAvailable"])
        assertEquals(localFamily, defaultFailure.details["defaultConnection"])
        assertTrue(defaultFailure.message.contains("defaultConnection=$localFamily"), defaultFailure.message)

        // (b) the user's own assignment by provider family: still authoritative, and named
        // as a connection — the built-in default above is not.
        val familyFailure = assertFailsWith<AgentModelResolutionException> {
            AgentModelResolver(
                preferences = AgentModelPreferences.DEFAULT.with(
                    AgentRole.MAIN,
                    RoleModelPreference(localFamily, AgentModelIds.DEVSTRAL_24B, explicit = true),
                ),
                connections = { emptyMap() },
            ).resolveForRole(AgentRole.MAIN, default = apiActive)
        }.error
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, familyFailure.code)
        assertEquals("true", familyFailure.details["explicit"])
        assertEquals("user", familyFailure.details["assignment"])
        assertEquals(localFamily, familyFailure.details["connection"])
        assertFalse(
            familyFailure.message.contains("defaultConnection="),
            familyFailure.message,
        )

        val namedFailure = assertFailsWith<AgentModelResolutionException> {
            AgentModelResolver(
                preferences = AgentModelPreferences.DEFAULT.with(
                    AgentRole.MAIN,
                    RoleModelPreference(
                        localFamily,
                        AgentModelIds.DEVSTRAL_24B,
                        connectionId = "my-local-runtime",
                        explicit = true,
                    ),
                ),
                connections = { emptyMap() },
            ).resolveForRole(AgentRole.MAIN, default = apiActive)
        }.error
        assertEquals("true", namedFailure.details["explicit"])
        assertEquals("user", namedFailure.details["assignment"])
        assertEquals("my-local-runtime", namedFailure.details["connection"])
        assertTrue(namedFailure.message.contains("connection=my-local-runtime"), namedFailure.message)

        // (c) an intentional fallback is reported as available on the unconfigured default,
        // because it is what decides whether the role can run on another model at all. The
        // resolver itself still never selects one.
        val withFallback = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { emptyMap() },
            intentionalFallback = { true },
        )
        val fallbackFailure = assertFailsWith<AgentModelResolutionException> {
            withFallback.resolveForRole(AgentRole.MAIN, default = apiActive)
        }.error
        assertEquals("true", fallbackFailure.details["fallbackAvailable"])
        assertTrue(fallbackFailure.message.contains("fallbackAvailable=true"), fallbackFailure.message)
        assertEquals(AgentErrorCode.NOT_CONFIGURED, fallbackFailure.code)
    }

    @Test
    fun `an ambiguous set of connections in the role's domain is reported as unnamed candidates`() =
        runBlocking {
            // Two connections of the required identity and domain, neither named. Choosing
            // one would route by the order they happen to be listed in, so the role fails
            // and reports which connections it could be bound to.
            val a = connection(localFamily, AgentModelIds.DEVSTRAL_24B, "local-a")
            val b = connection(localFamily, "qwen2.5-coder-14b", "local-b")

            val failure = assertFailsWith<AgentModelResolutionException> {
                resolver(registry(), a, b).resolveForRole(AgentRole.MAIN, default = apiActive)
            }.error

            assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.code)
            assertEquals("CONNECTION_AMBIGUOUS", failure.details["reason"])
            assertEquals("local-a,local-b", failure.details["candidates"])
            // Still the built-in default, so it must not be described as the user's pinned
            // assignment — that is what `explicit` used to claim unconditionally here.
            assertEquals("false", failure.details["explicit"])
            assertEquals("default", failure.details["assignment"])
        }
}
