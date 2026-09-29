package com.agentx.app.workspace.process

import com.agentx.app.workspace.WorkspaceError
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory [ProcessRuntime] for tests. Never touches a real OS process. */
class FakeProcessRuntime : ProcessRuntime {

    val started = mutableListOf<FakeStartedProcess>()
    var lastSpec: ProcessSpec? = null
    var failStart: WorkspaceError? = null

    override fun start(spec: ProcessSpec): StartedProcess {
        failStart?.let { throw ProcessStartException(it) }
        lastSpec = spec
        val process = FakeStartedProcess()
        started += process
        return process
    }
}

class FakeStartedProcess : StartedProcess {

    private val aliveFlag = AtomicBoolean(true)
    private val stdinBytes = ByteArrayOutputStream()
    private val stdoutPipe = StreamPipe()
    private val stderrPipe = StreamPipe()
    private val exitLatch = CountDownLatch(1)
    private var code: Int = 0
    val signals = mutableListOf<ProcessSignal>()

    val stdinText: String
        get() = stdinBytes.toString(Charsets.UTF_8)

    fun emitStdout(text: String) = stdoutPipe.write(text)

    fun emitStderr(text: String) = stderrPipe.write(text)

    fun complete(exitCode: Int) {
        if (!aliveFlag.compareAndSet(true, false)) return
        code = exitCode
        stdoutPipe.finish()
        stderrPipe.finish()
        exitLatch.countDown()
    }

    override val alive: Boolean get() = aliveFlag.get()

    override val stdout: InputStream get() = stdoutPipe

    override val stderr: InputStream get() = stderrPipe

    override fun write(bytes: ByteArray) {
        stdinBytes.write(bytes)
    }

    override fun closeStdin() {
        signals += ProcessSignal.EOF
    }

    override fun sendSignal(signal: ProcessSignal) {
        signals += signal
        when (signal) {
            ProcessSignal.INTERRUPT -> complete(130)
            ProcessSignal.EOF -> Unit
            ProcessSignal.TERMINATE -> complete(143)
            ProcessSignal.KILL -> complete(137)
        }
    }

    override fun destroy(force: Boolean) {
        sendSignal(if (force) ProcessSignal.KILL else ProcessSignal.TERMINATE)
    }

    override fun waitFor(): Int {
        try {
            exitLatch.await()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        return code
    }

    override fun waitFor(timeoutMillis: Long): Int? {
        val finished = exitLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return if (finished) code else null
    }

    override fun readStdout(buffer: ByteArray): Int = stdout.read(buffer)

    override fun readStderr(buffer: ByteArray): Int = stderr.read(buffer)
}

private class StreamPipe : InputStream() {

    private val queue = LinkedBlockingQueue<Int>()
    private val finished = AtomicBoolean(false)

    fun write(text: String) {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            queue.put(byte.toInt() and 0xFF)
        }
    }

    fun finish() {
        if (finished.compareAndSet(false, true)) {
            queue.put(-1)
        }
    }

    override fun read(): Int = queue.take()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        val first = queue.take()
        if (first < 0) return -1
        buffer[offset] = first.toByte()
        var count = 1
        while (count < length) {
            val next = queue.poll() ?: break
            if (next < 0) {
                queue.put(-1)
                break
            }
            buffer[offset + count] = next.toByte()
            count += 1
        }
        return count
    }
}
