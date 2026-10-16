package org.jellyfin.mobile.downloads.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.mobile.downloads.ContentRange
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads one HTTP object into a partial file, resuming with HTTP range requests.
 *
 * Guarantees:
 * - A resumed request carries `If-Range` with the validator (strong ETag or Last-Modified) seen when the
 *   partial file was started, so a replaced server object is never spliced onto old bytes: the server
 *   answers 200 with the full new object and the partial file is truncated first.
 * - A 206 is accepted only if its Content-Range starts exactly at the partial length and its total and
 *   validator match; otherwise the partial file is discarded and the download restarts once.
 * - The body must deliver exactly the announced number of bytes; a short body is a resumable
 *   [IOException] that keeps the bytes received so far.
 * - Data is fsynced before returning, and free space is checked before writing.
 */
class RangeDownloader(
    private val okHttpClient: OkHttpClient,
    private val storage: OfflineStorage,
) {
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val part: File,
        /** The validator recorded when [part] was started, if any. */
        val validator: String?,
        /** The total size recorded when [part] was started, if any (-1 if unknown). */
        val knownTotal: Long,
    )

    data class Result(val total: Long, val validator: String?)

    interface Listener {
        /** Called once the object's total size and validator are known, before any byte is written. */
        suspend fun onStart(total: Long, validator: String?) = Unit

        suspend fun onProgress(downloaded: Long, total: Long) = Unit

        /** Return false to stop the transfer (pause, cancel, removal); bytes so far are kept. */
        suspend fun shouldContinue(): Boolean = true
    }

    suspend fun download(request: Request, listener: Listener): Result = withContext(Dispatchers.IO) {
        var validator = request.validator
        var knownTotal = request.knownTotal
        repeat(MAX_RESTARTS + 1) {
            val offset = if (request.part.exists()) request.part.length() else 0L
            // Never resume without a way to prove the object is unchanged.
            val resume = offset > 0 && validator != null && knownTotal > 0
            if (offset > 0 && !resume) truncate(request.part, 0)

            val outcome = execute(buildRequest(request, if (resume) offset else 0L, validator)).use { response ->
                handle(response, request.part, if (resume) offset else 0L, validator, knownTotal, listener)
            }
            when (outcome) {
                is Outcome.Done -> return@withContext outcome.result
                Outcome.Restart -> {
                    truncate(request.part, 0)
                    validator = null
                    knownTotal = -1
                }
            }
        }
        throw IOException("Server kept answering with an inconsistent range for ${request.url}")
    }

    private sealed interface Outcome {
        data class Done(val result: Result) : Outcome
        data object Restart : Outcome
    }

    private fun buildRequest(request: Request, offset: Long, validator: String?): okhttp3.Request =
        okhttp3.Request.Builder().apply {
            url(request.url)
            request.headers.forEach { (name, value) -> header(name, value) }
            if (offset > 0 && validator != null) {
                header("Range", "bytes=$offset-")
                header("If-Range", validator)
            }
        }.build()

    private suspend fun handle(
        response: Response,
        part: File,
        offset: Long,
        validator: String?,
        knownTotal: Long,
        listener: Listener,
    ): Outcome = when (response.code) {
        HTTP_PARTIAL_CONTENT -> handlePartial(response, part, offset, validator, knownTotal, listener)
        HTTP_OK -> handleFull(response, part, listener)
        HTTP_RANGE_NOT_SATISFIABLE -> {
            // Only "already complete" is acceptable: the announced total equals what we hold.
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            if (total != null && offset > 0 && total == offset && total == knownTotal) {
                Outcome.Done(Result(total, validator))
            } else {
                Outcome.Restart
            }
        }
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> throw AuthExpiredException(response.code)
        HTTP_NOT_FOUND, HTTP_GONE -> throw ServerItemGoneException(response.code)
        else -> throw IOException("Unexpected HTTP ${response.code} for ${response.request.url}")
    }

    private suspend fun handlePartial(
        response: Response,
        part: File,
        offset: Long,
        validator: String?,
        knownTotal: Long,
        listener: Listener,
    ): Outcome {
        val range = response.header("Content-Range")?.let { runCatching { ContentRange.fromContentRangeHeader(it) }.getOrNull() }
        val responseValidator = validatorOf(response)
        val sameObject = responseValidator == null || responseValidator == validator
        if (offset <= 0 || range == null || range.start != offset || range.total != knownTotal || !sameObject) {
            return Outcome.Restart
        }
        listener.onStart(range.total, validator)
        write(response, part, offset, range.total, listener)
        return Outcome.Done(Result(range.total, validator))
    }

    private suspend fun handleFull(response: Response, part: File, listener: Listener): Outcome {
        val total = response.body?.contentLength()?.takeIf { it >= 0 }
            ?: throw IOException("Server did not announce the object size")
        val newValidator = validatorOf(response)
        truncate(part, 0)
        listener.onStart(total, newValidator)
        write(response, part, 0, total, listener)
        return Outcome.Done(Result(total, newValidator))
    }

    private suspend fun write(response: Response, part: File, offset: Long, total: Long, listener: Listener) {
        val body = response.body ?: throw IOException("Response has no body")
        val expected = total - offset
        part.parentFile?.let(storage::prepareDir)
        storage.ensureSpaceFor(expected, part.parentFile ?: storage.root)

        var written = 0L
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(offset)
            file.seek(offset)
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var sinceCheck = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (written + read > expected) throw IOException("Server sent more bytes than announced")
                    try {
                        file.write(buffer, 0, read)
                    } catch (e: IOException) {
                        val available = storage.availableBytes(part.parentFile ?: storage.root)
                        if (available < expected - written) throw InsufficientStorageException(expected - written, available, e)
                        throw e
                    }
                    written += read
                    sinceCheck += read
                    if (sinceCheck >= CHECK_INTERVAL_BYTES) {
                        sinceCheck = 0
                        listener.onProgress(offset + written, total)
                        if (!listener.shouldContinue()) {
                            file.fd.sync()
                            throw DownloadInterruptedException()
                        }
                    }
                }
            }
            file.fd.sync()
        }
        listener.onProgress(offset + written, total)
        if (written != expected) throw IOException("Connection closed after ${offset + written} of $total bytes")
    }

    private fun truncate(part: File, length: Long) {
        if (part.exists()) RandomAccessFile(part, "rw").use { it.setLength(length) }
    }

    private suspend fun execute(request: okhttp3.Request): Response = suspendCancellableCoroutine { continuation ->
        val call = okHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val MAX_RESTARTS = 2
        private const val BUFFER_SIZE = 64 * 1024
        private const val CHECK_INTERVAL_BYTES = 512L * 1024

        /** A strong ETag, else Last-Modified; weak ETags cannot prove byte equality. */
        fun validatorOf(response: Response): String? =
            response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified")
    }
}
