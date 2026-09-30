package com.agentx.app.agent.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate that decides whether a turn may pull in project context on its own.
 * Only recognisable small talk may not; everything else may.
 */
class ConversationalTurnTest {

    @Test
    fun `greetings and pleasantries do not require the workspace`() {
        val conversational = listOf(
            "Hi",
            "hi!",
            "Hi!!!",
            "HELLO",
            "hey",
            "Hello, how are you?",
            "hi there",
            "hello agent",
            "Good morning",
            "Thanks!",
            "thank you so much",
            "ok",
            "cool, thanks",
            "what can you do?",
            "bye",
        )
        conversational.forEach { prompt ->
            assertFalse(ConversationalTurn.requiresWorkspace(prompt), "\"$prompt\" should be conversational")
        }
    }

    @Test
    fun `project requests require the workspace`() {
        val project = listOf(
            "Find where authentication is implemented.",
            "Fix the login bug in src/Auth.kt",
            "Hi, can you fix the failing build?",
            "What is Supabase?",
            "summarise the repository structure",
            "run the tests",
            "read package.json",
            "explain this code",
            "hello, please refactor the coder module",
        )
        project.forEach { prompt ->
            assertTrue(ConversationalTurn.requiresWorkspace(prompt), "\"$prompt\" should be a task")
        }
    }

    @Test
    fun `an empty message needs no workspace`() {
        assertFalse(ConversationalTurn.requiresWorkspace(""))
        assertFalse(ConversationalTurn.requiresWorkspace("   "))
        assertFalse(ConversationalTurn.requiresWorkspace("!!!"))
    }

    @Test
    fun `punctuation and casing do not change the decision`() {
        assertFalse(ConversationalTurn.requiresWorkspace("  Hi,  there!!  "))
        assertTrue(ConversationalTurn.requiresWorkspace("  FIX THE BUILD  "))
    }

    @Test
    fun `a long message is never treated as small talk`() {
        val long = "hi " + "please ".repeat(10)
        assertTrue(ConversationalTurn.requiresWorkspace(long))
    }
}
