package com.github.iamr8.nugetextended.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HelperClientTest {

    /** A fake helper on the other end of two OS pipes (no thread-liveness checks, unlike Piped streams). */
    private class Fake {
        private val toClient = Pipe.open()
        private val fromClient = Pipe.open()
        val clientInput = Channels.newInputStream(toClient.source())
        val clientOutput = Channels.newOutputStream(fromClient.sink())
        private val writer = Channels.newOutputStream(toClient.sink()).bufferedWriter(UTF_8)
        private val reader = BufferedReader(InputStreamReader(Channels.newInputStream(fromClient.source()), UTF_8))

        fun say(line: String) { writer.write(line); writer.newLine(); writer.flush() }
        fun next(): JsonObject = JsonParser.parseString(reader.readLine()).asJsonObject
        fun hangUp() = writer.close()
    }

    private val fake = Fake()
    private val client = HelperClient(fake.clientInput, fake.clientOutput)

    @After
    fun tearDown() {
        runCatching { fake.hangUp() } // already closed in some tests
    }

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block)

    private fun <T> CompletableFuture<T>.failure(): Throwable = try {
        get(10, TimeUnit.SECONDS); fail("expected a failure"); throw IllegalStateException()
    } catch (e: ExecutionException) { e.cause!! }

    @Test
    fun helloIsParsed() {
        fake.say("""{"event":"hello","data":{"protocol":1,"helperVersion":"0.2.0","sdkVersion":"10.0.401","sdkPath":"/sdk","runtime":"10.0.12"}}""")
        val hello = client.awaitHello(5_000)
        assertEquals(1, hello.protocol)
        assertEquals("10.0.401", hello.sdkVersion)
    }

    @Test
    fun fatalLineFailsHello() {
        fake.say("""{"event":"fatal","data":{"message":"No .NET SDK found. Install the .NET 6 SDK or later."}}""")
        try {
            client.awaitHello(5_000); fail()
        } catch (e: HelperFatalException) {
            assertEquals("No .NET SDK found. Install the .NET 6 SDK or later.", e.message)
        }
    }

    @Test
    fun helloWithNoDataFailsHelloInsteadOfReturningNull() {
        fake.say("""{"event":"hello"}""")
        try {
            client.awaitHello(5_000); fail()
        } catch (_: HelperFatalException) {
        }
    }

    @Test
    fun requestReturnsResultReportsProgressAndDropsStaleIds() {
        val progress = mutableListOf<String>()
        val call = async { client.request("scan", mapOf("x" to 1), ScanResult::class.java, 5_000, onProgress = { progress += it }) }
        val req = fake.next()
        assertEquals("scan", req.get("method").asString)
        val id = req.get("id").asInt
        fake.say("""{"id":999,"result":{"stale":["old"]}}""")
        fake.say("""{"event":"progress","id":$id,"data":{"text":"Checked 3 package(s)"}}""")
        fake.say("""{"id":$id,"result":{"stale":["/r/A.csproj"]}}""")

        assertEquals(listOf("/r/A.csproj"), call.get(10, TimeUnit.SECONDS).stale)
        assertEquals(listOf("Checked 3 package(s)"), progress)
    }

    @Test
    fun userErrorBecomesUserException() {
        val call = async { client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 5_000) }
        val id = fake.next().get("id").asInt
        fake.say("""{"id":$id,"error":{"kind":"user","message":"Package source 'x' failed.","details":"401"}}""")

        val e = call.failure()
        assertTrue(e is HelperUserException)
        assertEquals("401", (e as HelperUserException).details)
    }

    @Test
    fun errorKindsMapToTheRightException() {
        fun errorFor(kind: String): Throwable {
            val call = async { client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 5_000) }
            val id = fake.next().get("id").asInt
            fake.say("""{"id":$id,"error":{"kind":"$kind","message":"m","details":null}}""")
            return call.failure()
        }
        assertTrue(errorFor("user") is HelperUserException)
        assertTrue(errorFor("busy") is HelperUserException)
        assertTrue(errorFor("bug") is HelperBugException)
        assertTrue(errorFor("something-unknown") is HelperBugException)
        assertTrue(errorFor("cancelled") is HelperCancelledException)
    }

    @Test
    fun cancelSendsCancelAndThrows() {
        val cancelled = AtomicBoolean(false)
        val call = async { client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 60_000, isCancelled = { cancelled.get() }) }
        val id = fake.next().get("id").asInt
        cancelled.set(true)

        val cancel = fake.next()
        assertEquals("cancel", cancel.get("method").asString)
        assertEquals(id, cancel.getAsJsonObject("params").get("targetId").asInt)
        fake.say("""{"id":$id,"result":null,"error":{"kind":"cancelled","message":"Cancelled.","details":null}}""")

        assertTrue(call.failure() is HelperCancelledException)
        assertFalse(client.isClosed)
    }

    @Test
    fun cancelGraceExpiresClosesTheClient() {
        val localFake = Fake()
        val shortGraceClient = HelperClient(localFake.clientInput, localFake.clientOutput, cancelGraceMs = 100)
        val cancelled = AtomicBoolean(false)
        val call = async {
            shortGraceClient.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 60_000, isCancelled = { cancelled.get() })
        }
        localFake.next()
        cancelled.set(true)
        assertEquals("cancel", localFake.next().get("method").asString) // never answered

        assertTrue(call.failure() is HelperCancelledException)
        assertTrue(shortGraceClient.isClosed)
        runCatching { localFake.hangUp() }
    }

    @Test
    fun closedStreamFailsPendingRequest() {
        val call = async { client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 60_000) }
        fake.next()
        fake.hangUp()

        assertTrue(call.failure() is HelperCrashedException)
        try {
            client.request("ping", emptyMap<String, Any>(), JsonObject::class.java, 1_000); fail()
        } catch (_: HelperCrashedException) {
        }
    }

    @Test
    fun progressKeepsTheRequestAlive() {
        val call = async { client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 3_000) }
        val id = fake.next().get("id").asInt
        repeat(8) {
            Thread.sleep(500)
            fake.say("""{"event":"progress","id":$id,"data":{"text":"still working"}}""")
        }
        fake.say("""{"id":$id,"result":{}}""")
        call.get(10, TimeUnit.SECONDS) // 4000 ms total, but never 3000 ms of silence between events
    }

    @Test
    fun silenceTimesOut() {
        val localFake = Fake()
        val quickGraceClient = HelperClient(localFake.clientInput, localFake.clientOutput, cancelGraceMs = 50)
        val call = async { quickGraceClient.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 300) }
        localFake.next()

        assertTrue(call.failure() is HelperTimeoutException)
        assertEquals("cancel", localFake.next().get("method").asString)
        runCatching { localFake.hangUp() }
    }

    @Test
    fun lateProgressAfterResultIsIgnored() {
        val progress = mutableListOf<String>()
        val call = async { client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 5_000, onProgress = { progress += it }) }
        val id = fake.next().get("id").asInt
        // Back to back, like the helper can do: the late line must not reach onProgress.
        fake.say("""{"id":$id,"result":{}}""")
        fake.say("""{"event":"progress","id":$id,"data":{"text":"late"}}""")
        call.get(10, TimeUnit.SECONDS)

        // Synchronize without sleeping: the reader is single-threaded and the pipe is FIFO, so once
        // a second request's response comes back, the late progress line above is already handled.
        val call2 = async { client.request("ping", emptyMap<String, Any>(), JsonObject::class.java, 5_000) }
        val id2 = fake.next().get("id").asInt
        fake.say("""{"id":$id2,"result":{}}""")
        call2.get(10, TimeUnit.SECONDS)

        assertEquals(emptyList<String>(), progress)
    }

    @Test
    fun readerSurvivesMalformedLineAndThrowingOnProgress() {
        val call = async {
            client.request("scan", emptyMap<String, Any>(), ScanResult::class.java, 5_000, onProgress = { throw RuntimeException("boom") })
        }
        val id = fake.next().get("id").asInt
        fake.say("""not json at all""")
        fake.say("""{"event":"progress","id":$id,"data":{"text":"x"}}""")
        fake.say("""{"id":$id,"result":{}}""")

        call.get(10, TimeUnit.SECONDS) // the reader thread must still be alive to deliver this
    }

    @Test
    fun closeSendsShutdown() {
        client.close()
        assertEquals("shutdown", fake.next().get("method").asString)
    }
}
