package org.jellyfin.mobile.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.UUID

/**
 * Builds the device information JSON that is passed to the web client.
 */
internal fun buildDeviceInformationJson(apiClient: ApiClient): String {
    val deviceInfo = apiClient.deviceInfo
    val clientInfo = apiClient.clientInfo

    return buildJsonObject {
        put("deviceId", deviceInfo.id.toString())
        // normalize the name by removing special characters
        // and making sure it's at least 1 character long
        // otherwise the webui will fail to send it to the server
        val name = AuthorizationHeaderBuilder.encodeParameterValue(deviceInfo.name).padStart(1)
        put("deviceName", name)
        put("appName", clientInfo.name)
        put("appVersion", clientInfo.version)
    }.toString()
}

/**
 * Parses the item ids from the JSON array of files sent by the web client.
 *
 * @throws IllegalArgumentException if [args] is not a valid JSON array of file objects or contains an invalid item id.
 */
internal fun parseDownloadItemIds(args: String): Set<UUID> {
    val files = Json.parseToJsonElement(args).jsonArray
    val itemIds = mutableSetOf<UUID>()

    files.forEach { element ->
        val file = element.jsonObject
        val itemId = file["itemId"]?.jsonPrimitive?.contentOrNull?.toUUID()

        if (itemId != null) {
            itemIds.add(itemId)
        }
    }

    return itemIds
}
