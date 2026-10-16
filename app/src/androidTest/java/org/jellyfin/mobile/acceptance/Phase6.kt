package org.jellyfin.mobile.acceptance

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared setup for the Phase 6 device acceptance sequence. The three steps run as separate instrumentation
 * invocations from `scripts/c203-phase6/run-device-sequence.sh`, which toggles airplane mode and force-stops
 * the app between them; nothing here fakes the network, the server or the app.
 *
 * Instrumentation arguments: c203ServerUrl, c203User, c203Password, c203ItemId.
 */
internal object Phase6 {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val serverUrl: String get() = argument("c203ServerUrl").trimEnd('/')
    val userName: String get() = argument("c203User")
    val password: String get() = argument("c203Password")
    val itemId: UUID get() = argument("c203ItemId").toUUID()

    private fun argument(name: String): String =
        requireNotNull(arguments.getString(name)) { "Missing instrumentation argument -e $name" }

    inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    /** True when the device has a network with internet capability (airplane mode off). */
    fun hasNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Independent of the app's own client: a plain request to the server's public info endpoint. */
    suspend fun serverReachable(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$serverUrl/System/Info/Public").build()
        try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            Log.i(TAG, "Server not reachable: $e")
            false
        }
    }

    /** Signs in with a separate verifier identity, so checks never reuse the app's session. */
    suspend fun verifierAuthorization(): String = withContext(Dispatchers.IO) {
        val identity = "MediaBrowser Client=\"c203-phase6-verifier\", Device=\"verifier\", " +
            "DeviceId=\"c203-phase6-verifier\", Version=\"1.0\""
        val body = """{"Username":"$userName","Pw":"$password"}"""
        val request = Request.Builder().url("$serverUrl/Users/AuthenticateByName")
            .header("Authorization", identity)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val token = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Verifier sign-in answered HTTP ${response.code}" }
            json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("AccessToken").jsonPrimitive.content
        }
        "$identity, Token=\"$token\""
    }

    data class ServerUserData(val playCount: Int, val played: Boolean, val positionTicks: Long)

    /** Reads the item's user data straight from the server REST API. */
    suspend fun serverUserData(authorization: String): ServerUserData = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$serverUrl/UserItems/$itemId/UserData")
            .header("Authorization", authorization)
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "UserData answered HTTP ${response.code}" }
            val data = json.parseToJsonElement(response.body!!.string()).jsonObject
            ServerUserData(
                playCount = data.getValue("PlayCount").jsonPrimitive.int,
                played = data.getValue("Played").jsonPrimitive.boolean,
                positionTicks = data.getValue("PlaybackPositionTicks").jsonPrimitive.content.toLong(),
            )
        }
    }

    /** State handed from one step to the next; it lives in the app's private storage on the device. */
    private val stateFile: File get() = File(context.filesDir, "c203-phase6/state.properties")

    fun writeState(values: Map<String, String>) {
        stateFile.parentFile!!.mkdirs()
        val properties = Properties().apply { putAll(values) }
        stateFile.outputStream().use { properties.store(it, "c203 Phase 6 acceptance state") }
    }

    fun readState(): Map<String, String> {
        check(stateFile.isFile) { "Run Phase6Step1DownloadOnlineTest first" }
        val properties = Properties().apply { stateFile.inputStream().use(::load) }
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    /** Polls [probe] until it returns a value, failing with [what] after [timeout]. */
    suspend fun <T : Any> awaitValue(timeout: Duration, what: String, probe: suspend () -> T?): T =
        withTimeoutOrNull(timeout) {
            var value = probe()
            while (value == null) {
                delay(POLL_INTERVAL)
                value = probe()
            }
            value
        } ?: throw AssertionError("Timed out after $timeout waiting for $what")

    private const val TAG = "C203Phase6"
    private const val PROBE_TIMEOUT_SECONDS = 5L
    private val POLL_INTERVAL = 1.seconds
}
