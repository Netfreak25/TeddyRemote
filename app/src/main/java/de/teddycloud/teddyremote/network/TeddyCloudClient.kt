package de.teddycloud.teddyremote.network

import de.teddycloud.teddyremote.model.BoxesResponse
import de.teddycloud.teddyremote.model.CommandResponse
import de.teddycloud.teddyremote.model.ConnectionProfile
import de.teddycloud.teddyremote.model.PlaylistTrack
import de.teddycloud.teddyremote.model.TonieMetadata
import de.teddycloud.teddyremote.model.TonieboxCatalogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.net.URI
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

private val networkJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

class TeddyCloudClient private constructor(
    private val profile: ConnectionProfile,
    private val api: TeddyCloudApi,
    private val apiHttpClient: OkHttpClient,
) {
    suspend fun getBoxes(): BoxesResponse =
        networkJson.decodeFromString(api.getBoxes().string())

    suspend fun getBoxGeneration(overlay: String): Int =
        api.getSetting(BOX_GENERATION_SETTING, overlay).string().trim().toIntOrNull() ?: 0

    suspend fun getRingBrightness(overlay: String): Int? =
        api.getSetting(RING_BRIGHTNESS_SETTING, overlay).string().trim().toIntOrNull()?.coerceIn(0, 100)

    suspend fun getBedtimeRingBrightness(overlay: String): Int? =
        api.getSetting(BEDTIME_RING_BRIGHTNESS_SETTING, overlay).string().trim().toIntOrNull()?.coerceIn(0, 100)

    suspend fun setRingBrightness(overlay: String, brightness: Int) {
        api.setSetting(
            RING_BRIGHTNESS_SETTING,
            overlay,
            brightness.coerceIn(0, 100).toString().toRequestBody(TEXT_PLAIN),
        ).close()
    }

    suspend fun setBedtimeRingBrightness(overlay: String, brightness: Int) {
        api.setSetting(
            BEDTIME_RING_BRIGHTNESS_SETTING,
            overlay,
            brightness.coerceIn(0, 100).toString().toRequestBody(TEXT_PLAIN),
        ).close()
    }

    suspend fun playback(overlay: String, action: String, chapter: Int? = null): CommandResponse {
        val body = if (action == "setPosition") {
            """{"action":"setPosition","chapter":${chapter ?: 0},"ms":0}"""
        } else {
            """{"action":"$action"}"""
        }
        return parseCommand(api.playback(overlay, body.toRequestBody(JSON_MEDIA)))
    }

    suspend fun setVolume(overlay: String, level: Int): CommandResponse =
        parseCommand(api.volume(overlay, """{"level":${level.coerceIn(0, 10)}}""".toRequestBody(JSON_MEDIA)))

    suspend fun ping(overlay: String): CommandResponse = parseCommand(api.ping(overlay))

    suspend fun setBedtime(overlay: String, durationSeconds: Int): CommandResponse {
        require(durationSeconds in BEDTIME_DURATION_MIN..BEDTIME_DURATION_MAX) {
            "Bedtime-Dauer muss zwischen 300 und 86400 Sekunden liegen"
        }
        return parseCommand(
            api.bedtime(
                overlay,
                """{"state":"on","duration":$durationSeconds}""".toRequestBody(JSON_MEDIA),
            ),
        )
    }

    suspend fun cancelBedtime(overlay: String): CommandResponse =
        parseCommand(api.bedtime(overlay, """{"state":"off"}""".toRequestBody(JSON_MEDIA)))

    suspend fun sleep(overlay: String): CommandResponse =
        parseCommand(api.sleep(overlay, "{}".toRequestBody(JSON_MEDIA)))

    suspend fun getTonieMetadata(overlay: String, ruid: String, contentVersion: Long?): TonieMetadata {
        val root = networkJson.parseToJsonElement(api.getTagInfo(ruid, overlay, contentVersion).string()).jsonObject
        val tag = root["tagInfo"]?.jsonObject ?: JsonObject(emptyMap())
        val info = tag["tonieInfo"]?.jsonObject ?: JsonObject(emptyMap())
        val sourceInfo = tag["sourceInfo"]?.jsonObject ?: JsonObject(emptyMap())
        val playlist = tag["playlist"]?.jsonObject
        val title = info.string("title")
            ?: info.string("episode")
            ?: sourceInfo.string("title")
            ?: sourceInfo.string("episode")
            ?: playlist.string("title")
            ?: tag.string("model")
            ?: "Tonie"
        val subtitle = info.string("series") ?: sourceInfo.string("series") ?: ""
        val tracks = listOf(
            playlist?.get("tracks")?.asStrings().orEmpty(),
            sourceInfo["tracks"]?.asStrings().orEmpty(),
            info["tracks"]?.asStrings().orEmpty(),
        ).firstOrNull(List<String>::isNotEmpty).orEmpty()
        val durations = playlist?.get("durations")?.asLongs().orEmpty()
        val trackStarts = tag["trackSeconds"]?.asLongs().orEmpty()
        val count = maxOf(
            playlist?.get("chapterCount")?.jsonPrimitive?.intOrNull ?: 0,
            tracks.size,
            trackStarts.size,
        )
        val normalizedTracks = (0 until count.coerceAtLeast(tracks.size)).map { index ->
            PlaylistTrack(
                index = index,
                title = tracks.getOrNull(index)?.takeIf(String::isNotBlank) ?: "Kapitel ${index + 1}",
                durationSeconds = durations.getOrNull(index)
                    ?: chapterDurationFromStarts(trackStarts, index),
            )
        }
        return TonieMetadata(
            ruid = ruid.uppercase(),
            title = title,
            subtitle = subtitle,
            pictureUrl = resolveUrl(info.string("picture") ?: sourceInfo.string("picture") ?: tag.string("picture")),
            playlist = normalizedTracks,
        )
    }

    suspend fun getTonieboxCatalog(): List<TonieboxCatalogEntry> {
        val payloads = listOf(
            runCatching { api.getTonieboxCatalog().string() }.getOrNull(),
            runCatching { api.getCustomTonieboxCatalog().string() }.getOrNull(),
        )
        return payloads.filterNotNull().flatMap(::parseCatalog).distinctBy { it.id.lowercase() }
    }

    fun resolveUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { URI(profile.apiBaseUrl).resolve(value).toString() }.getOrNull()
    }

    /**
     * Loads an image through the same pinned connection as the API when it is
     * hosted by TeddyCloud. External artwork keeps Android's normal trust chain.
     */
    fun downloadImage(url: String): ByteArray? {
        val target = runCatching { URI(url) }.getOrNull() ?: return null
        val client = if (sameOrigin(target, URI(profile.apiBaseUrl))) apiHttpClient else externalImageHttpClient
        return runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > MAX_IMAGE_BYTES) return@use null
                body.bytes().takeIf { it.size <= MAX_IMAGE_BYTES }
            }
        }.getOrNull()
    }

    private fun parseCatalog(raw: String): List<TonieboxCatalogEntry> {
        val root = runCatching { networkJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["tonieboxes"] as? JsonArray
                ?: root["boxes"] as? JsonArray
                ?: JsonArray(root.values.toList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { item ->
            runCatching { networkJson.decodeFromJsonElement(TonieboxCatalogEntry.serializer(), item) }.getOrNull()
        }.filter { it.id.isNotBlank() }
    }

    private fun parseCommand(body: okhttp3.ResponseBody): CommandResponse =
        networkJson.decodeFromString(body.use { it.string() })

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
        private const val BOX_GENERATION_SETTING = "toniebox.boxGeneration"
        private const val RING_BRIGHTNESS_SETTING = "toniebox2.lightring_brightness"
        private const val BEDTIME_RING_BRIGHTNESS_SETTING = "toniebox2.bedtime_lightring_brightness"
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        private const val BEDTIME_DURATION_MIN = 300
        private const val BEDTIME_DURATION_MAX = 86_400
        private val externalImageHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        fun create(profile: ConnectionProfile): TeddyCloudClient {
            val normalized = profile.normalized()
            val builder = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
            if (normalized.apiBaseUrl.startsWith("https://", ignoreCase = true)) {
                val tls = createTlsMaterial(normalized.apiCertificateFingerprint)
                builder.sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
                normalized.apiCertificateFingerprint?.let { pin ->
                    builder.hostnameVerifier { _, session ->
                        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                        leaf != null && fingerprintMatchesPin(pin, leaf.sha256Fingerprint())
                    }
                }
            }
            val httpClient = builder.build()
            val retrofit = Retrofit.Builder()
                .baseUrl(normalized.apiBaseUrl)
                .client(httpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
            return TeddyCloudClient(normalized, retrofit.create(TeddyCloudApi::class.java), httpClient)
        }

        private fun sameOrigin(left: URI, right: URI): Boolean =
            left.scheme.equals(right.scheme, ignoreCase = true) &&
                left.host.equals(right.host, ignoreCase = true) &&
                effectivePort(left) == effectivePort(right)

        private fun effectivePort(uri: URI): Int = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
    }
}

private fun JsonObject?.string(name: String): String? =
    this?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonElement.asStrings(): List<String> =
    runCatching { jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())

private fun JsonElement.asLongs(): List<Long> =
    runCatching { jsonArray.mapNotNull { it.jsonPrimitive.longOrNull } }.getOrDefault(emptyList())

private fun chapterDurationFromStarts(starts: List<Long>, index: Int): Long? {
    val start = starts.getOrNull(index) ?: return null
    val next = starts.getOrNull(index + 1) ?: return null
    return (next - start).takeIf { it >= 0 }
}
