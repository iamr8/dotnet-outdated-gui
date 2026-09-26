package com.github.iamr8.nugetextended.engine

import com.github.iamr8.nugetextended.PluginText
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

open class HelperException(message: String, val details: String? = null) : RuntimeException(message)

/** The user's solution or environment (bad project, failing feed). Balloon, not a bug report. */
class HelperUserException(message: String, details: String?) : HelperException(message, details)

/** An unexpected exception inside the helper. Bug report. */
class HelperBugException(message: String, details: String?) : HelperException(message, details)

class HelperCancelledException : HelperException("Cancelled.")

class HelperTimeoutException(method: String, idleTimeoutMs: Long) :
    HelperException(
        "The engine sent nothing for ${formatIdle(idleTimeoutMs)} during '$method'. Raise the timeout in settings, or check the package sources.",
    )

class HelperCrashedException(details: String?) : HelperException("The engine stopped.", details)

/** The helper could not start (no SDK, SDK too old, no runtime). Balloon. */
class HelperFatalException(message: String, details: String? = null) : HelperException(message, details)

/** "0 s" reads as no wait at all; show sub-second timeouts in ms instead. */
private fun formatIdle(ms: Long): String = if (ms < 1000) "$ms ms" else "${ms / 1000} s"

/** JSON-lines client for the helper engine. Thread-safe; one reader thread. */
class HelperClient(
    input: InputStream,
    output: OutputStream,
    private val crashDetails: () -> String? = { null },
    /** Long enough for one in-flight multi-TFM evaluation to notice the cancel. */
    private val cancelGraceMs: Long = 5_000,
) {
    private val gson = Gson()
    private val writer = BufferedWriter(OutputStreamWriter(output, UTF_8))
    private val writeLock = Any()
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val hello = CompletableFuture<Hello>()

    @Volatile
    var isClosed = false
        private set

    private class Pending(val onProgress: (String) -> Unit) {
        val response = CompletableFuture<JsonObject>()
        @Volatile var lastActivity = System.nanoTime()
    }

    init {
        thread(isDaemon = true, name = "${PluginText.NAME} engine reader") {
            readLoop(BufferedReader(InputStreamReader(input, UTF_8)))
        }
    }

    fun awaitHello(timeoutMs: Long): Hello = try {
        hello.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (_: TimeoutException) {
        throw HelperFatalException("The engine did not start in ${timeoutMs / 1000} s.", crashDetails())
    }

    /**
     * @param onProgress runs on the reader thread for every progress event of this request. It must
     * not block or do slow work; an exception it throws is swallowed so a bad callback cannot kill
     * the reader thread (and every other in-flight request with it).
     */
    fun <T> request(
        method: String,
        params: Any,
        type: Class<T>,
        idleTimeoutMs: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): T {
        val id = nextId.incrementAndGet()
        val p = Pending(onProgress)
        pending[id] = p
        try {
            if (isClosed) throw HelperCrashedException(crashDetails())
            send(id, method, params)
            while (true) {
                val message = try {
                    p.response.get(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    null
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                } catch (e: InterruptedException) {
                    cancel(id)
                    awaitCancelGrace(p)
                    Thread.currentThread().interrupt()
                    throw HelperCancelledException()
                }
                if (message != null) return parse(message, type)
                if (isCancelled()) {
                    cancel(id)
                    awaitCancelGrace(p)
                    throw HelperCancelledException()
                }
                if (System.nanoTime() - p.lastActivity > TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)) {
                    cancel(id)
                    awaitCancelGrace(p)
                    throw HelperTimeoutException(method, idleTimeoutMs)
                }
            }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        runCatching { send(0, "shutdown", emptyMap<String, Any>()) }
        isClosed = true
    }

    /**
     * After sending `cancel` for [p], gives the helper up to [cancelGraceMs] to send any response
     * for that id - its way of freeing its busy slot - before treating it as stuck. If nothing
     * arrives in time, the client is marked closed, like a crash: the next request fails fast with
     * [HelperCrashedException] instead of hanging, and the caller starts a new helper.
     */
    private fun awaitCancelGrace(p: Pending) {
        try {
            p.response.get(cancelGraceMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            markCrashed()
        } catch (_: InterruptedException) {
            // The helper may still be busy: treat it as stuck, and keep the caller's interrupt.
            Thread.currentThread().interrupt()
            markCrashed()
        } catch (_: Exception) {
            // already failed some other way (e.g. already crashed) - nothing more to do
        }
    }

    private fun markCrashed() {
        isClosed = true
        val crash = HelperCrashedException(crashDetails())
        pending.values.forEach { it.response.completeExceptionally(crash) }
    }

    private fun <T> parse(message: JsonObject, type: Class<T>): T {
        val error = message.get("error")
        if (error != null && !error.isJsonNull) {
            val info = gson.fromJson(error, ErrorInfo::class.java)
            throw when (info.kind) {
                "user", "busy" -> HelperUserException(info.message, info.details)
                "cancelled" -> HelperCancelledException()
                else -> HelperBugException(info.message, info.details)
            }
        }
        return gson.fromJson(message.get("result"), type)
    }

    private fun cancel(targetId: Int) {
        runCatching { send(nextId.incrementAndGet(), "cancel", mapOf("targetId" to targetId)) }
    }

    private fun send(id: Int, method: String, params: Any) {
        val line = gson.toJson(mapOf("id" to id, "method" to method, "params" to params))
        synchronized(writeLock) {
            try {
                writer.write(line)
                writer.newLine()
                writer.flush()
            } catch (_: IOException) {
                isClosed = true
                throw HelperCrashedException(crashDetails())
            }
        }
    }

    private fun readLoop(reader: BufferedReader) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                try {
                    handleLine(line)
                } catch (_: Throwable) {
                    // malformed line, an unexpected shape (bad id, wrong type), or a throwing
                    // onProgress - dropped, the reader keeps going.
                }
            }
        } catch (_: IOException) {
        } finally {
            // The stream ended (helper died) or the loop above exited: nothing more will ever
            // arrive, so close the client and fail hello plus every request still waiting - not
            // just IOException, any exit path here means the same thing.
            markCrashed()
            hello.completeExceptionally(HelperCrashedException(crashDetails()))
        }
    }

    private fun handleLine(line: String) {
        val obj = JsonParser.parseString(line).asJsonObject
        when (obj.string("event")) {
            "hello" -> {
                val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                if (data != null) {
                    hello.complete(gson.fromJson(data, Hello::class.java))
                } else {
                    hello.completeExceptionally(HelperFatalException("The engine's hello had no data.", crashDetails()))
                }
            }
            "fatal" -> hello.completeExceptionally(
                HelperFatalException(obj.getAsJsonObject("data")?.string("message") ?: "The engine could not start.", crashDetails()),
            )
            "progress" -> pending[obj.int("id")]?.let { p ->
                p.lastActivity = System.nanoTime()
                try {
                    p.onProgress(obj.getAsJsonObject("data")?.string("text").orEmpty())
                } catch (_: Throwable) {
                    // a throwing callback must not kill the reader thread
                }
            }
            // no "event": a response. An id no longer pending - already answered, cancelled, timed
            // out, or never ours - is dropped; removing it here (not just in request()'s finally)
            // means a progress line for the same id that arrives right after is dropped too.
            null -> pending.remove(obj.int("id"))?.response?.complete(obj)
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asInt ?: -1

    private companion object {
        const val POLL_MS = 100L
    }
}
