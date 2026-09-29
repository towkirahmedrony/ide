package com.agentx.app.termux

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * What the terminal surface is told about a session.
 *
 * The vendored `TerminalSessionClient`/`TerminalViewClient` interfaces are large and mostly
 * about Termux's own UI. The IDE implements them through these two small ports so the wiring
 * stays reviewable and the rest of the app never touches the Termux types.
 */
interface TermuxTerminalHost {

    /** The emulator produced output; the view must redraw. */
    fun onScreenUpdated(session: TerminalSession)

    /** The shell exited. The session manager drops it and the UI offers a restart. */
    fun onSessionFinished(session: TerminalSession)

    fun onTitleChanged(session: TerminalSession)

    fun onColorsChanged(session: TerminalSession)

    /** A program asked to copy text (OSC 52). */
    fun onCopyTextToClipboard(text: String)

    /** A program asked the terminal to paste. */
    fun onPasteTextFromClipboard(session: TerminalSession?)

    fun onBell()

    /** The pty pid is known; used for the "running" badge and for diagnostics. */
    fun onShellPid(session: TerminalSession, pid: Int)
}

/** What the terminal view needs to ask the IDE. */
interface TermuxViewHost {

    /** Pinch zoom. Return the scale that was actually applied. */
    fun onScale(scale: Float): Float

    fun onSingleTapUp(event: MotionEvent)

    /** Long press; returning true means the IDE handled it (for example opened a menu). */
    fun onLongPress(event: MotionEvent): Boolean

    /** Extra-key state from the on-screen keyboard bar. */
    fun readControlKey(): Boolean

    fun readAltKey(): Boolean

    fun readShiftKey(): Boolean

    fun readFnKey(): Boolean

    /** Selection mode started or stopped, so the toolbar can switch. */
    fun onCopyModeChanged(copyMode: Boolean)
}

/**
 * `TerminalSessionClient` for the IDE.
 *
 * Every callback is forwarded to the currently attached host and nothing is swallowed: dropping
 * [onScreenUpdated] would freeze the display, and dropping [onSessionFinished] would leave a
 * dead session looking alive.
 *
 * The host is resolved per callback because a session outlives the screen that created it: the
 * Terminal tab can be left and re-entered, or recreated after a rotation, while the shell keeps
 * running. [hostProvider] returning null simply means nothing is watching right now.
 */
class TermuxSessionClient(
    private val hostProvider: () -> TermuxTerminalHost?,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        hostProvider()?.onScreenUpdated(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        hostProvider()?.onTitleChanged(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        hostProvider()?.onSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        hostProvider()?.onCopyTextToClipboard(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        hostProvider()?.onPasteTextFromClipboard(session)
    }

    override fun onBell(session: TerminalSession) {
        hostProvider()?.onBell()
    }

    override fun onColorsChanged(session: TerminalSession) {
        hostProvider()?.onColorsChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Cursor visibility belongs to the emulator; the view reads it while drawing.
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        hostProvider()?.onShellPid(session, pid)
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) = Log.e(tag, message)

    override fun logWarn(tag: String, message: String) = Log.w(tag, message)

    override fun logInfo(tag: String, message: String) = Log.i(tag, message)

    override fun logDebug(tag: String, message: String) = Log.d(tag, message)

    override fun logVerbose(tag: String, message: String) = Log.v(tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Log.e(tag, message, e)

    override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "", e)
}

/**
 * `TerminalViewClient` for the IDE.
 *
 * The key handlers deliberately return `false`. `TerminalView` then runs its own handling,
 * which is what produces the real terminal behaviour: Ctrl+C, Ctrl+D, Tab, the arrow keys,
 * Alt combinations and the application-cursor-mode sequences a program like `vim` expects.
 * Intercepting here would replace that with a hand-rolled approximation.
 */
class TermuxViewClient(
    private val host: TermuxViewHost,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = host.onScale(scale)

    override fun onSingleTapUp(e: MotionEvent) = host.onSingleTapUp(e)

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = host.onCopyModeChanged(copyMode)

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = host.onLongPress(event)

    override fun readControlKey(): Boolean = host.readControlKey()

    override fun readAltKey(): Boolean = host.readAltKey()

    override fun readShiftKey(): Boolean = host.readShiftKey()

    override fun readFnKey(): Boolean = host.readFnKey()

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        // The view has a live emulator; nothing to do until output arrives.
    }

    override fun logError(tag: String, message: String) = Log.e(tag, message)

    override fun logWarn(tag: String, message: String) = Log.w(tag, message)

    override fun logInfo(tag: String, message: String) = Log.i(tag, message)

    override fun logDebug(tag: String, message: String) = Log.d(tag, message)

    override fun logVerbose(tag: String, message: String) = Log.v(tag, message)

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Log.e(tag, message, e)

    override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "", e)
}
