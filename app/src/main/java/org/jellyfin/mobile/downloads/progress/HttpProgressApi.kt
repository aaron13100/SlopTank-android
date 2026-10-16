package org.jellyfin.mobile.downloads.progress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/**
 * [ProgressApi] over HTTP against the SlopTank server's conditional progress endpoints
 * (`/UserItems/{itemId}/PlaybackProgress[/Operations]`). Authentication is the account's own header.
 */
class HttpProgressApi(
    private val okHttpClient: OkHttpClient,
    baseUrl: String,
    private val authorization: String,
    private val deviceId: String,
) : ProgressApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun baseline(itemId: UUID): BaselineResult = withContext(Dispatchers.IO) {
        execute(request("/UserItems/$itemId/PlaybackProgress").get().build()).use { response ->
            when (response.code) {
                HTTP_OK -> {
                    val state = decode<StateDto>(response)
                    BaselineResult.Ok(state.serverId, state.toServerProgress())
                }
                HTTP_NOT_FOUND -> if (itemExists(itemId)) BaselineResult.Unsupported else BaselineResult.Gone
                else -> throw IOException("Baseline request answered HTTP ${response.code}")
            }
        }
    }

    override suspend fun apply(itemId: UUID, serverIdentity: String, operation: FrozenOperation): ApplyResult =
        withContext(Dispatchers.IO) {
            val body = JSON.encodeToString(OperationDto.serializer(), operation.toDto(serverIdentity, deviceId))
                .toRequestBody(JSON_MEDIA_TYPE)
            execute(request("/UserItems/$itemId/PlaybackProgress/Operations").post(body).build()).use { response ->
                when (response.code) {
                    HTTP_OK -> decode<ResultDto>(response).let { result ->
                        ApplyResult.Applied(result.requireState(), result.replayed)
                    }
                    HTTP_CONFLICT -> ApplyResult.Conflict(decode<ResultDto>(response).requireState())
                    HTTP_BAD_REQUEST, HTTP_GONE, HTTP_UNPROCESSABLE, HTTP_INSUFFICIENT_STORAGE ->
                        ApplyResult.Refused("HTTP ${response.code}")
                    HTTP_NOT_FOUND -> if (itemExists(itemId)) ApplyResult.Unsupported else ApplyResult.Gone
                    else -> throw IOException("Operation request answered HTTP ${response.code}")
                }
            }
        }

    /** The legacy user data route exists on every server, so it tells a missing item from a missing endpoint. */
    private fun itemExists(itemId: UUID): Boolean =
        execute(request("/UserItems/$itemId/UserData").get().build()).use { response ->
            when (response.code) {
                HTTP_OK -> true
                HTTP_NOT_FOUND -> false
                else -> throw IOException("User data probe answered HTTP ${response.code}")
            }
        }

    private fun request(path: String) = Request.Builder()
        .url(baseUrl + path)
        .header("Authorization", authorization)

    private fun execute(request: Request): Response = okHttpClient.newCall(request).execute()

    private inline fun <reified T> decode(response: Response): T {
        val text = response.body?.string() ?: throw IOException("Empty response")
        return try {
            JSON.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw IOException("Unreadable progress response", e)
        }
    }

    @Serializable
    private data class StateDto(
        @SerialName("ItemId") val itemId: String? = null,
        @SerialName("ServerId") val serverId: String = "",
        @SerialName("Revision") val revision: Long,
        @SerialName("PositionTicks") val positionTicks: Long,
        @SerialName("Played") val played: Boolean,
        @SerialName("PlayCount") val playCount: Int,
    ) {
        fun toServerProgress() = ServerProgress(revision, positionTicks, played, playCount)
    }

    @Serializable
    private data class ResultDto(
        @SerialName("Outcome") val outcome: String,
        @SerialName("Replayed") val replayed: Boolean = false,
        @SerialName("State") val state: StateDto? = null,
    ) {
        fun requireState() = state?.toServerProgress() ?: throw IOException("Outcome $outcome without state")
    }

    @Serializable
    private data class OperationDto(
        @SerialName("OperationId") val operationId: String,
        @SerialName("Sequence") val sequence: Long,
        @SerialName("ServerId") val serverId: String,
        @SerialName("DeviceId") val deviceId: String,
        @SerialName("BaseRevision") val baseRevision: Long,
        @SerialName("PositionTicks") val positionTicks: Long,
        @SerialName("Played") val played: Boolean,
        @SerialName("PlayCountIncrement") val playCountIncrement: Int,
        @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    )

    private fun FrozenOperation.toDto(serverId: String, deviceId: String) = OperationDto(
        operationId = operationId.toString(),
        sequence = sequence,
        serverId = serverId,
        deviceId = deviceId,
        baseRevision = baseRevision,
        positionTicks = positionTicks,
        played = played,
        playCountIncrement = playCountIncrement,
        lastPlayedDate = lastPlayedDate,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_GONE = 410
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_INSUFFICIENT_STORAGE = 507
    }
}
