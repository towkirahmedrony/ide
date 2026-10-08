package com.agentx.app.model.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cancelling a run against a live event stream, over a real socket and the real
 * [UrlConnectionHttpTransport].
 *
 * The contract the agent loop depends on is the one pinned down here: once a run is
 * cancelled, no further frame reaches the caller, the in-flight call ends instead of
 * staying active, and the cancellation is not dressed up as a provider failure. The
 * endpoint is a plain socket this test controls, so "the endpoint sent more frames
 * afterwards" is a fact rather than an assumption.
 */
class StreamCancellationTest {

    @Test
    fun `cancelling a run stops the stream from delivering later frames`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val firstFrameDelivered = CountDownLatch(1)
        val releaseTail = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<String>()

        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val out = socket.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Connection: close\r\n" +
                                "\r\n"
                            ).toByteArray(),
                    )
                    // One answer chunk reaches the caller ...
                    out.write(frame("Hel"))
                    out.flush()
                    // ... then the endpoint holds the stream open, so the reader is parked
                    // on its next read while the run is cancelled.
                    releaseTail.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    repeat(TAIL_FRAMES) { index -> out.write(frame("tail-$index")) }
                    out.flush()
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val transport = UrlConnectionHttpTransport()
        val run = launch(Dispatchers.Default) {
            transport.executeStreaming(
                HttpRequestSpec(
                    method = "POST",
                    url = "http://127.0.0.1:${server.localPort}/v1/chat/completions",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{}",
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = READ_TIMEOUT_MILLIS,
                ),
            ) { line ->
                if (line.startsWith("data:")) {
                    delivered += line
                    firstFrameDelivered.countDown()
                }
            }
        }

        try {
            assertTrue(
                firstFrameDelivered.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the stream must deliver its first frame",
            )
            // The first frame is with the caller, and the call is waiting for more.
            delay(20)
            run.cancel()
            // The endpoint now sends five more frames into the cancelled run.
            releaseTail.countDown()

            withTimeout(JOIN_TIMEOUT_MILLIS) { run.join() }

            assertTrue(run.isCancelled, "a cancelled run must end rather than stay active")
            assertEquals(
                listOf(FIRST_FRAME),
                delivered.toList(),
                "no frame that arrives after the cancellation may be delivered",
            )
        } finally {
            releaseTail.countDown()
            run.cancel()
            server.close()
        }
    }

    private fun frame(content: String): ByteArray =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$content\"}}]}\n\n".toByteArray()

    private companion object {
        const val FIRST_FRAME = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}"
        const val TAIL_FRAMES = 5
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val RELEASE_TIMEOUT_SECONDS = 10L
        const val JOIN_TIMEOUT_MILLIS = 5_000L

        /**
         * Deliberately far longer than the join timeout: a call that hangs waiting for
         * the socket must fail on the join timeout rather than on a read timeout, so a
         * broken cancellation is reported as one.
         */
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
