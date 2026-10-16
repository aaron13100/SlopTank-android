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
import java.io.InputStream
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
        var known = Resume(offset = 0, validator = request.validator, total = request.knownTotal)
        repeat(MAX_RESTARTS + 1) {
            val held = if (request.part.exists()) request.part.length() else 0L
            // Never resume without a way to prove the object is unchanged.
            val resume = known.copy(offset = if (known.canResume(held)) held else 0L)
            if (held > 0 && resume.offset == 0L) truncate(request.part, 0)

            val outcome = execute(buildRequest(request, resume)).use { response ->
                handle(response, request.part, resume, listener)
            }
            when (outcome) {
                is Outcome.Done -> return@withContext outcome.result
                Outcome.Restart -> {
                    truncate(request.part, 0)
                    known = Resume(offset = 0, validator = null, total = -1)
                }
            }
        }
        throw IOException("Server kept answering with an inconsistent range for ${request.url}")
    }

    /** What is known about the partial object: bytes held, its validator and its total size. */
    private data class Resume(val offset: Long, val validator: String?, val total: Long) {
        fun canResume(held: Long) = held > 0 && validator != null && total > 0
    }

    private sealed interface Outcome {
        data class Done(val result: Result) : Outcome
        data object Restart : Outcome
    }

    private fun buildRequest(request: Request, resume: Resume): okhttp3.Request =
        okhttp3.Request.Builder().apply {
            url(request.url)
            request.headers.forEach { (name, value) -> header(name, value) }
            if (resume.offset > 0 && resume.validator != null) {
                header("Range", "bytes=${resume.offset}-")
                header("If-Range", resume.validator)
            }
        }.build()

    private suspend fun handle(response: Response, part: File, resume: Resume, listener: Listener): Outcome =
        when (response.code) {
            HTTP_PARTIAL_CONTENT -> handlePartial(response, part, resume, listener)
            HTTP_OK -> handleFull(response, part, listener)
            HTTP_RANGE_NOT_SATISFIABLE -> {
                // Only "already complete" is acceptable: the announced total equals what we hold.
                val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                val complete = total != null && resume.offset > 0 && total == resume.offset
                if (complete && total == resume.total) {
                    Outcome.Done(
                        Result(total, resume.validator)
                    )
                } else {
                    Outcome.Restart
                }
            }
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> throw AuthExpiredException(response.code)
            HTTP_NOT_FOUND, HTTP_GONE -> throw ServerItemGoneException(response.code)
            else -> throw IOException("Unexpected HTTP ${response.code} for ${response.request.url}")
        }

    private suspend fun handlePartial(response: Response, part: File, resume: Resume, listener: Listener): Outcome {
        val range = response.header("Content-Range")
            ?.let { runCatching { ContentRange.fromContentRangeHeader(it) }.getOrNull() }
        val responseValidator = validatorOf(response)
        val sameObject = responseValidator == null || responseValidator == resume.validator
        val rangeMatches = range != null && range.start == resume.offset && range.total == resume.total
        if (resume.offset <= 0 || !rangeMatches || !sameObject) return Outcome.Restart
        listener.onStart(resume.total, resume.validator)
        write(response, part, resume.offset, resume.total, listener)
        return Outcome.Done(Result(resume.total, resume.validator))
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
        val dir = part.parentFile ?: storage.root
        storage.prepareDir(dir)
        storage.ensureSpaceFor(total - offset, dir)
        val written = RandomAccessFile(part, "rw").use { file ->
            file.setLength(offset)
            file.seek(offset)
            val copied = body.byteStream().use { input -> copy(input, file, offset, total, listener) }
            file.fd.sync()
            copied
        }
        listener.onProgress(offset + written, total)
        if (written != total - offset) throw IOException("Connection closed after ${offset + written} of $total bytes")
    }

    /** Copies the body into [file], checking cancellation, space and the user's pause between chunks. */
    private suspend fun copy(
        input: InputStream,
        file: RandomAccessFile,
        offset: Long,
        total: Long,
        listener: Listener
    ): Long {
        val expected = total - offset
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        var sinceCheck = 0L
        var read = input.read(buffer)
        while (read != -1) {
            currentCoroutineContext().ensureActive()
            if (written + read > expected) throw IOException("Server sent more bytes than announced")
            writeChunk(file, buffer, read, expected - written)
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
            read = input.read(buffer)
        }
        return written
    }

    /** Writes one chunk, reporting a full disk as [InsufficientStorageException] rather than a network error. */
    private fun writeChunk(file: RandomAccessFile, buffer: ByteArray, count: Int, remaining: Long) {
        try {
            file.write(buffer, 0, count)
        } catch (e: IOException) {
            val available = storage.availableBytes(storage.root)
            if (available < remaining) throw InsufficientStorageException(remaining, available, e)
            throw e
        }
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
