package org.jellyfin.mobile.downloads.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

/**
 * A fake HTTP origin with real range semantics (Range, If-Range, 206, 416) whose object, validator and
 * failure behaviour a test can change between requests. It stands in for the server's
 * PhysicalFile(enableRangeProcessing) download route.
 */
class RangeServer(var content: ByteArray, var etag: String? = "\"v1\"") : Dispatcher() {
    /** If set, the next response delivers only this many body bytes and then drops the connection. */
    var cutNextResponseAfter: Int? = null

    /** If true, the server ignores Range headers and always answers 200. */
    var ignoreRange = false

    /** If set, the next response has this status and no body. */
    var failNextWith: Int? = null

    val requests = mutableListOf<RecordedRequest>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        failNextWith?.let { code ->
            failNextWith = null
            return MockResponse().setResponseCode(code)
        }
        val range = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        val ifRange = request.getHeader("If-Range")
        val honourRange = range != null && !ignoreRange && (ifRange == null || ifRange == etag)
        val response = MockResponse()
        etag?.let { response.setHeader("ETag", it) }
        if (honourRange && range!! >= content.size) {
            return response.setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}")
        }
        val start = if (honourRange) range!!.toInt() else 0
        val slice = content.copyOfRange(start, content.size)
        if (honourRange) {
            response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
        } else {
            response.setResponseCode(200)
        }
        val cut = cutNextResponseAfter
        cutNextResponseAfter = null
        return if (cut != null && cut < slice.size) {
            response.setBody(Buffer().write(slice.copyOf(cut)))
                .setHeader("Content-Length", slice.size)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        } else {
            response.setBody(Buffer().write(slice))
        }
    }
}
