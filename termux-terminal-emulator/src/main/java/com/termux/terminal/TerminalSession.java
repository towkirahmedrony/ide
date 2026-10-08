package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;
    /** The pty reported EOF/EIO, which means the slave side is gone. */
    private static final int MSG_PTY_CLOSED = 5;

    /**
     * Reported as the exit status when the pty closed without {@link JNI#waitFor} returning a code.
     * Mirrors a shell killed by SIGHUP, which is what a pty hangup sends.
     */
    static final int PTY_CLOSED_EXIT_STATUS = 128 + 1;

    /** Sentinel for "waitpid has not reported yet". No real status can collide with it. */
    private static final int UNREPORTED_EXIT_STATUS = Integer.MIN_VALUE;

    /**
     * How long the reader waits for the waiter's exit status before falling back to the pty-hangup
     * status. A hand-off on the reader thread, not a delay in the user's path: `waitpid` returns as
     * soon as the child is reaped, which is the same event that ended the read.
     */
    private static final long EXIT_HANDOFF_MILLIS = 250L;

    public final String mHandle = UUID.randomUUID().toString();

    /**
     * Written on the launching thread during {@link #initializeEmulator} and read by the UI thread
     * and the emulator callbacks, so it must not be cached. See the pty-close path below, which can
     * observe this session from a third thread.
     */
    volatile TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    volatile int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    volatile int mShellExitStatus;

    /** Set once the exit has been announced, so it is announced exactly once. */
    private volatile boolean mExitDelivered;

    /** Set once the pty has been released, so it is released exactly once. */
    private volatile boolean mCleanedUp;

    /**
     * Serializes the one-time pty launch.
     *
     * `updateSize` is reachable from two threads at once by design: the session manager starts a
     * shell off the main thread while `TerminalView.attachSession` sizes the same session from the
     * UI thread. Without this lock both callers can observe `mEmulator == null` and fork their own
     * shell; the second one then overwrites the pid and the descriptor, so `finishIfRunning` only
     * reaches the second process, the first keeps running against the same `/workspace`, and both
     * readers feed one emulator. One session must own exactly one pty.
     */
    private final Object mLaunchLock = new Object();

    /** The exit status `waitpid` returned, or {@link #UNREPORTED_EXIT_STATUS} until it is known. */
    private volatile int mReportedExitStatus = UNREPORTED_EXIT_STATUS;

    /** Set once input dropped for want of a process has been reported, so typing into a dead
     * session fills the log with one line rather than one line per keystroke. */
    private volatile boolean mInputDropReported;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private volatile int mTerminalFileDescriptor;

    /**
     * Sole owner of the pty master descriptor once the session is initialised, or null before that
     * and after {@link #cleanupResources}. See {@link #initializeEmulator} for why this exists
     * rather than a reflected {@link FileDescriptor}.
     */
    private volatile ParcelFileDescriptor mPtyDescriptor;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        synchronized (mLaunchLock) {
            if (mEmulator == null) {
                startProcessLocked(columns, rows, cellWidthPixels, cellHeightPixels);
            } else {
                JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
                mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            }
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        synchronized (mLaunchLock) {
            // Already launched: the pty exists and only its size can still change. Idempotent, so a
            // second caller can never fork a second shell for this session.
            if (mEmulator != null) {
                JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
                mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
                return;
            }
            startProcessLocked(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** Creates the pty and starts the shell. Callers must hold {@link #mLaunchLock}. */
    private void startProcessLocked(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        mInputDropReported = false;
        mClient.setTerminalShellPid(this, mShellPid);

        // The pty master is addressed as a FileDescriptor obtained through public API.
        //
        // The vendored code instead rebuilt one by reflecting into java.io.FileDescriptor's private
        // field. That reflection is a non-SDK interface, an app targeting a modern SDK is refused
        // it, and the refused path ended in System.exit(1) — which killed the whole process with no
        // crash report and with no session created, so the terminal had nothing to show and nothing
        // to restart. adoptFd() is the supported way to get the same thing.
        //
        // The descriptor is read and written with android.system.Os rather than wrapped in streams,
        // so nothing aliases it: the reader and writer share one fd, and owning it here is what
        // lets cleanupResources() close it exactly once and wake a parked reader.
        final ParcelFileDescriptor pty = ParcelFileDescriptor.adoptFd(mTerminalFileDescriptor);
        mPtyDescriptor = pty;
        final FileDescriptor ptyDescriptor = pty.getFileDescriptor();

        // The waiter starts first so the reader can hand the exit status over instead of announcing
        // a pty hangup that would read as "killed by signal 1" for a clean exit.
        final Thread waiter = new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final int processExitCode = JNI.waitFor(mShellPid);
                // Published before the message is posted, so the reader can use it whether or not
                // the main thread has processed the message yet.
                mReportedExitStatus = processExitCode;
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        };
        waiter.start();

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        // Raw read(2): EOF is 0, not -1 the way FileInputStream reports it.
                        int read = Os.read(ptyDescriptor, buffer, 0, buffer.length);
                        if (read <= 0) break;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (ErrnoException | IOException e) {
                    // EIO is the normal way a pty master read ends once the slave side is gone, and
                    // EBADF is how this session ends it when it is torn down. InterruptedIOException
                    // is the other way Os.read gives up. Anything else cost the user output that
                    // never reached the screen, so it is reported rather than swallowed.
                    if (!isExpectedPtyShutdown(e)) {
                        Logger.logError(mClient, LOG_TAG, "pty output read failed: " + e);
                    }
                } finally {
                    // Wait for the waiter's status before falling back to the pty-hangup one: a clean
                    // `exit 0` must not be reported as "killed by signal 1" merely because this
                    // thread reached the end of the pty first. The wait is bounded because a process
                    // that is never reaped must still end up reported by the fallback below.
                    try {
                        waiter.join(EXIT_HANDOFF_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    final int reportedExitStatus = mReportedExitStatus;
                    if (reportedExitStatus != UNREPORTED_EXIT_STATUS) {
                        mMainThreadHandler.sendMessage(
                            mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, reportedExitStatus));
                    } else {
                        // Nothing is left on the other end of the pty and no waiter status arrived, so
                        // the hangup is all there is to report. Announcing it is what stops a session
                        // whose process never got reaped from looking alive forever; deliverExit()
                        // ignores a second announcement, so a normal exit still wins.
                        mMainThreadHandler.sendEmptyMessage(MSG_PTY_CLOSED);
                    }
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        Os.write(ptyDescriptor, buffer, 0, bytesToWrite);
                    }
                } catch (ErrnoException | IOException e) {
                    // EIO/EBADF here mean the pty was torn down under this thread, which is how an
                    // exit ends it. Anything else means bytes the user typed were never delivered to
                    // the shell, and saying so is the only way that is ever distinguishable from a
                    // command that simply produced no output.
                    if (!isExpectedPtyShutdown(e)) {
                        Logger.logError(mClient, LOG_TAG, "pty input write failed: " + e);
                    }
                }
            }
        }.start();
    }

    /**
     * Whether a pty I/O exception is the normal way one of this session's threads ends.
     *
     * EIO is what a master read or write returns once the slave side is gone — the shell exited —
     * and EBADF is what both directions return once {@link #cleanupResources} has closed the
     * descriptor. Both are expected at the end of every session's life. Reporting them would bury
     * the failures that cost the user data, which is why the two are told apart instead of caught
     * blindly.
     */
    private static boolean isExpectedPtyShutdown(Throwable error) {
        if (!(error instanceof ErrnoException)) return false;
        final int errno = ((ErrnoException) error).errno;
        return errno == OsConstants.EIO || errno == OsConstants.EBADF;
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        // No process means nothing can receive the bytes: before the shell is spawned, and after it
        // has been reaped. Dropping them is right — queueing them would hand them to whatever runs
        // next — but it is stated once per launch, because input that silently goes nowhere is
        // otherwise indistinguishable from a shell that received it and printed nothing.
        if (mShellPid <= 0) {
            if (!mInputDropReported) {
                mInputDropReported = true;
                Logger.logWarn(mClient, LOG_TAG, "input dropped: no process is attached (" + count + " byte(s))");
            }
            return;
        }
        if (!mTerminalToProcessIOQueue.write(data, offset, count) && !mInputDropReported) {
            mInputDropReported = true;
            Logger.logWarn(mClient, LOG_TAG, "input dropped: the input queue is closed (" + count + " byte(s))");
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        // Deliberately `mShellPid > 0` and not isRunning(): isRunning() only means "not reaped
        // yet", which is also true before the process exists. Os.kill(0, SIGKILL) signals the
        // *caller's own process group*, so a session that never launched would have taken this
        // app down with it instead of just being cleaned up.
        if (mShellPid > 0) {
            // The JNI forks with setsid(), so the child is a session leader and its pid is its
            // process-group id. Signalling the group reaches the children PRoot started as well as
            // PRoot itself, and cannot reach this app, which is in a different group. -N addresses
            // the group whose id is N, so if setsid had failed this simply finds no group.
            try {
                Os.kill(-mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                // ESRCH once the group is already gone; the single-process kill below still runs.
            }
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. Safe to call more than once. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            if (mCleanedUp) {
                mShellExitStatus = exitStatus;
                return;
            }
            mCleanedUp = true;
            mShellPid = -1;
            mShellExitStatus = exitStatus;
            mExitDelivered = true;
        }

        // Stop the reader and writer threads, and close the I/O streams.
        //
        // Closing the pty descriptor is what wakes the reader thread if it is parked in read(2):
        // it returns EBADF/EIO and the thread ends, which is also how the session learns the pty
        // is gone. The descriptor has exactly one owner — the ParcelFileDescriptor adopted in
        // initializeEmulator — so it is closed exactly once. The fallback covers a session that
        // adopted nothing (a failed launch), where the raw fd is all there is.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        ParcelFileDescriptor descriptor = mPtyDescriptor;
        mPtyDescriptor = null;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
                // Already gone; nothing left to release.
            }
        } else {
            JNI.close(mTerminalFileDescriptor);
        }
    }

    /**
     * The single place a session's exit is announced.
     *
     * @return true when this call was the one that announced it.
     */
    private boolean deliverExit(int exitStatus) {
        synchronized (this) {
            if (mExitDelivered) {
                // The same exit arriving by the other route. The waiter's status is the real one and
                // the pty-hangup path only synthesises one, so the newer value replaces it instead of
                // being discarded — a clean `exit 0` must not stay reported as a signal.
                mShellExitStatus = exitStatus;
                return false;
            }
            mExitDelivered = true;
        }
        cleanupResources(exitStatus);

        String exitDescription = "\r\n[Process completed";
        if (exitStatus > 0) {
            exitDescription += " (code " + exitStatus + ")";
        } else if (exitStatus < 0) {
            exitDescription += " (signal " + (-exitStatus) + ")";
        }
        exitDescription += " - press Enter]";

        TerminalEmulator emulator = mEmulator;
        // A session that failed to launch never built an emulator; there is no screen to write to
        // and the caller's failure message is what the user sees instead.
        if (emulator != null) {
            byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
            emulator.append(bytesToWrite, bytesToWrite.length);
            notifyScreenUpdate();
        }

        mClient.onSessionFinished(TerminalSession.this);
        return true;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                TerminalEmulator emulator = mEmulator;
                if (emulator != null) {
                    emulator.append(mReceiveBuffer, bytesRead);
                    notifyScreenUpdate();
                }
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                deliverExit((Integer) msg.obj);
            } else if (msg.what == MSG_PTY_CLOSED) {
                deliverExit(PTY_CLOSED_EXIT_STATUS);
            }
        }

    }

}
