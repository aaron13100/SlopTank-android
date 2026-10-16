package org.jellyfin.mobile.downloads.progress

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

class HttpProgressApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = HttpProgressApi(OkHttpClient(), server.url("/").toString(), "MediaBrowser Token=\"t\"", "dev-1")
    private val itemId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val state = """{"ItemId":"$itemId","ServerId":"srv","Revision":4,""" +
        """"PositionTicks":10,"Played":true,"PlayCount":2}"""
    private val operation = FrozenOperation(UUID.randomUUID(), 3, 4, 10, true, 1, "2026-10-16T12:00:00Z")

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun `baseline reads the revision and server identity`() {
        server.enqueue(json(200, state))
        runBlocking { api.baseline(itemId) } shouldBe BaselineResult.Ok("srv", ServerProgress(4, 10, true, 2))
        val request = server.takeRequest()
        request.path shouldBe "/UserItems/$itemId/PlaybackProgress"
        request.getHeader("Authorization") shouldBe "MediaBrowser Token=\"t\""
    }

    @Test
    fun `server identity is the public system id, the same id the progress state carries`() {
        server.enqueue(json(200, """{"LocalAddress":"http://x","ServerName":"n","Version":"10.11.0","Id":"srv"}"""))
        runBlocking { api.serverIdentity() } shouldBe "srv"
        server.takeRequest().path shouldBe "/System/Info/Public"

        server.enqueue(json(200, """{"ServerName":"n"}"""))
        io.kotest.assertions.throwables.shouldThrow<java.io.IOException> { runBlocking { api.serverIdentity() } }
        server.enqueue(MockResponse().setResponseCode(503))
        io.kotest.assertions.throwables.shouldThrow<java.io.IOException> { runBlocking { api.serverIdentity() } }
    }

    @Test
    fun `operations are sent with every bound identity and outcomes are mapped`() {
        server.enqueue(json(200, """{"Outcome":"Applied","Replayed":true,"State":$state}"""))
        runBlocking { api.apply(itemId, "srv", operation) } shouldBe
            ApplyResult.Applied(ServerProgress(4, 10, true, 2), replayed = true)
        val body = server.takeRequest().body.readUtf8()
        body shouldContain "\"OperationId\":\"${operation.operationId}\""
        body shouldContain "\"ServerId\":\"srv\""
        body shouldContain "\"DeviceId\":\"dev-1\""
        body shouldContain "\"BaseRevision\":4"

        server.enqueue(json(409, """{"Outcome":"Conflict","State":$state}"""))
        runBlocking { api.apply(itemId, "srv", operation) } shouldBe
            ApplyResult.Conflict(ServerProgress(4, 10, true, 2))
        server.enqueue(json(422, """{"Outcome":"PayloadMismatch"}"""))
        runBlocking { api.apply(itemId, "srv", operation) } shouldBe ApplyResult.Refused("HTTP 422")
    }

    @Test
    fun `a missing endpoint on an older server is told apart from a deleted item`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(200, "{}")) // legacy user data route answers: the item exists
        runBlocking { api.apply(itemId, "srv", operation) } shouldBe ApplyResult.Unsupported

        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        runBlocking { api.baseline(itemId) } shouldBe BaselineResult.Gone
    }

    @Test
    fun `rejected credentials stop the sync instead of retrying`() {
        server.enqueue(MockResponse().setResponseCode(401))
        io.kotest.assertions.throwables.shouldThrow<ProgressAuthException> { runBlocking { api.baseline(itemId) } }
    }
}
