package org.jellyfin.mobile.live

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.core.MediaValidator
import org.jellyfin.mobile.downloads.core.OfflineStorage
import org.jellyfin.mobile.downloads.core.RangeDownloader
import org.jellyfin.mobile.downloads.core.StorageSpace
import org.jellyfin.mobile.downloads.progress.ApplyResult
import org.jellyfin.mobile.downloads.progress.BaselineResult
import org.jellyfin.mobile.downloads.progress.FrozenOperation
import org.jellyfin.mobile.downloads.progress.HttpProgressApi
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Opt-in end-to-end check of the app's download and progress code against a real SlopTank server built from
 * the c203-progress-receipts branch. It runs only in its own Gradle task, `./gradlew :app:liveServerTest`, which
 * fails unless C203_LIVE_SERVER, C203_LIVE_USER, C203_LIVE_PASSWORD and C203_LIVE_ITEM are set; the default
 * unit test run excludes it.
 */
class LiveServerFlowTest {
    private val baseUrl = requireNotNull(System.getenv("C203_LIVE_SERVER")).trimEnd('/')
    private val itemId = UUID.fromString(requireNotNull(System.getenv("C203_LIVE_ITEM")))
    private val deviceId = "c203-live-" + UUID.randomUUID().toString().take(8)
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val identity = "MediaBrowser Client=\"c203-live\", Device=\"jvm\", DeviceId=\"$deviceId\", Version=\"1.0\""
    private val token: String = run {
        val body = """{"Username":"${System.getenv("C203_LIVE_USER")}","Pw":"${System.getenv("C203_LIVE_PASSWORD")}"}"""
        val request = Request.Builder().url("$baseUrl/Users/AuthenticateByName")
            .header("Authorization", identity)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            json.parseToJsonElement(response.body!!.string()).jsonObject["AccessToken"]!!.jsonPrimitive.content
        }
    }
    private val authorization = "$identity, Token=\"$token\""
    private var recorded: Pair<Long, String?>? = null

    @Test
    fun `a real download pauses, resumes with a range request and validates`() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("c203-live").toFile()
        val storage = OfflineStorage(root, StorageSpace { Long.MAX_VALUE / 4 }, { }, reserveBytes = 0)
        val downloader = RangeDownloader(client, storage)
        val part = File(root, ".media.mp4.part")
        val request = RangeDownloader.Request(
            "$baseUrl/Items/$itemId/Download",
            mapOf("Authorization" to authorization),
            part,
            null,
            -1
        )

        var total = -1L
        var validator: String? = null
        val pausing = object : RangeDownloader.Listener {
            override suspend fun onStart(total: Long, validator: String?) {
                // What the app persists before writing: the object's size and validator.
                this@LiveServerFlowTest.recorded = total to validator
            }

            override suspend fun shouldContinue() = part.length() < PAUSE_AFTER
        }
        runCatching { downloader.download(request, pausing) }.isFailure shouldBe true
        recorded!!.let { (t, v) ->
            total = t
            validator = v
        }
        println("paused after ${part.length()} of $total bytes, server validator=$validator")

        val held = part.length()
        var firstProgress = -1L
        val recorder = object : RangeDownloader.Listener {
            override suspend fun onProgress(downloaded: Long, total: Long) {
                if (firstProgress < 0) firstProgress = downloaded
            }
        }
        val result = downloader.download(request.copy(validator = validator, knownTotal = total), recorder)
        result.total shouldBe total
        MediaValidator.validate(part, DownloadFileType.ITEM, total) shouldBe MediaValidator.Result.Valid
        // A 206 continuation reports progress beyond the held bytes; a restart from zero would not.
        (firstProgress > held) shouldBe true
        println("resumed from byte $held (first progress $firstProgress), complete and valid: ${part.length()} bytes")
    }

    @Test
    fun `offline completion syncs exactly once and a stale baseline conflicts`() = runBlocking {
        val api = HttpProgressApi(client, baseUrl, authorization, deviceId)
        val baseline = api.baseline(itemId)
        baseline.shouldBeInstanceOf<BaselineResult.Ok>()
        val before = baseline.progress
        println("baseline revision=${before.revision} playCount=${before.playCount}")

        val completion = FrozenOperation(UUID.randomUUID(), 1, before.revision, 0, true, 1, null)
        val applied = api.apply(itemId, baseline.serverIdentity, completion)
        applied.shouldBeInstanceOf<ApplyResult.Applied>()
        applied.replayed shouldBe false
        val replay = api.apply(itemId, baseline.serverIdentity, completion)
        replay.shouldBeInstanceOf<ApplyResult.Applied>()
        replay.replayed shouldBe true
        val after = (api.baseline(itemId) as BaselineResult.Ok).progress
        after.playCount shouldBe before.playCount + 1
        after.played shouldBe true
        println("applied once, replayed once, playCount ${before.playCount} -> ${after.playCount}")

        val stale = FrozenOperation(UUID.randomUUID(), 2, before.revision, 12_345, false, 0, null)
        val conflict = api.apply(itemId, baseline.serverIdentity, stale)
        conflict.shouldBeInstanceOf<ApplyResult.Conflict>()
        (api.baseline(itemId) as BaselineResult.Ok).progress.positionTicks shouldBe after.positionTicks
        println("stale operation refused with conflict; server progress unchanged")
    }

    private companion object {
        const val PAUSE_AFTER = 3L * 1024 * 1024
    }
}
