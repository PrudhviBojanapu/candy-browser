package dev.sk2andy.materialbrowser.browser

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object YouTubeExtractor {
    private const val TAG = "YouTubeExtractor"

    data class YouTubeStream(
        val url: String,
        val mimeType: String,
        val quality: String,
        val isAudio: Boolean,
        val isVideoOnly: Boolean = false,
        val sizeBytes: Long? = null,
    )

    data class ExtractionResult(
        val title: String,
        val streams: List<YouTubeStream>,
    )

    private data class ClientConfig(
        val name: String,
        val version: String,
        val userAgent: String,
        val buildPayloadContext: (JSONObject) -> Unit,
    )

    // Priority clients that return direct streams without signature deciphering or login requirements.
    private val CLIENT_CONFIGS = listOf(
        ClientConfig(
            name = "TVHTML5",
            version = "7.20250312.16.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            buildPayloadContext = { clientObj ->
                clientObj.put("clientName", "TVHTML5")
                clientObj.put("clientVersion", "7.20250312.16.00")
                clientObj.put("hl", "en")
                clientObj.put("gl", "US")
            },
        ),
        ClientConfig(
            name = "ANDROID_VR",
            version = "1.50.29",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.50.29 (Linux; U; Android 12) gzip",
            buildPayloadContext = { clientObj ->
                clientObj.put("clientName", "ANDROID_VR")
                clientObj.put("clientVersion", "1.50.29")
                clientObj.put("deviceMake", "Oculus")
                clientObj.put("deviceModel", "Quest 3")
                clientObj.put("androidSdkVersion", 32)
                clientObj.put("hl", "en")
                clientObj.put("gl", "US")
            },
        ),
        ClientConfig(
            name = "IOS",
            version = "19.29.1",
            userAgent = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)",
            buildPayloadContext = { clientObj ->
                clientObj.put("clientName", "IOS")
                clientObj.put("clientVersion", "19.29.1")
                clientObj.put("deviceMake", "Apple")
                clientObj.put("deviceModel", "iPhone16,2")
                clientObj.put("userInterfaceTheme", "USER_INTERFACE_THEME_DARK")
                clientObj.put("osName", "iOS")
                clientObj.put("osVersion", "17.5.1.21F90")
                clientObj.put("hl", "en")
                clientObj.put("gl", "US")
            },
        ),
        ClientConfig(
            name = "ANDROID",
            version = "19.30.36",
            userAgent = "com.google.android.youtube/19.30.36 (Linux; U; Android 11) gzip",
            buildPayloadContext = { clientObj ->
                clientObj.put("clientName", "ANDROID")
                clientObj.put("clientVersion", "19.30.36")
                clientObj.put("androidSdkVersion", 30)
                clientObj.put("hl", "en")
                clientObj.put("gl", "US")
            },
        ),
    )

    fun extractVideoId(url: String): String? {
        val cleanUrl = url.trim()
        if (cleanUrl.contains("youtu.be/")) {
            val parts = cleanUrl.split("youtu.be/")
            if (parts.size > 1) {
                return parts[1].split("?")[0].split("/")[0].take(11)
            }
        }
        if (cleanUrl.contains("/shorts/")) {
            val parts = cleanUrl.split("/shorts/")
            if (parts.size > 1) {
                return parts[1].split("?")[0].split("/")[0].take(11)
            }
        }
        if (cleanUrl.contains("/embed/")) {
            val parts = cleanUrl.split("/embed/")
            if (parts.size > 1) {
                return parts[1].split("?")[0].split("/")[0].take(11)
            }
        }
        if (cleanUrl.contains("/v/")) {
            val parts = cleanUrl.split("/v/")
            if (parts.size > 1) {
                return parts[1].split("?")[0].split("/")[0].take(11)
            }
        }
        try {
            val uri = Uri.parse(cleanUrl)
            val vParam = uri.getQueryParameter("v")
            if (!vParam.isNullOrEmpty()) {
                return vParam.take(11)
            }
        } catch (_: Exception) {}

        return null
    }

    suspend fun extractStreams(videoUrl: String, cookies: String? = null): ExtractionResult? = withContext(Dispatchers.IO) {
        val oldPolicy = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().permitAll().build())
        try {
            val videoId = extractVideoId(videoUrl) ?: return@withContext null
            Log.i(TAG, "Extracting YouTube streams for video ID: $videoId")
            val effectiveCookies = cookies?.takeIf { it.isNotBlank() }
                ?: runCatching { android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull()

            for (config in CLIENT_CONFIGS) {
                val result = tryExtractWithClient(videoId, config, effectiveCookies)
                if (result != null && result.streams.isNotEmpty()) {
                    val testStream = result.streams.firstOrNull { it.url.isNotBlank() }
                    if (testStream != null && isStreamPlayable(testStream.url, config.userAgent, effectiveCookies)) {
                        Log.i(TAG, "Successfully extracted and validated ${result.streams.size} streams using client ${config.name}")
                        return@withContext result
                    } else {
                        Log.w(TAG, "Client ${config.name} streams failed HTTP playback check, skipping")
                    }
                }
            }

            try {
                val pipedResult = tryExtractWithPiped(videoId)
                if (pipedResult != null && pipedResult.streams.isNotEmpty()) {
                    Log.i(TAG, "Successfully extracted streams via Piped API for $videoId")
                    return@withContext pipedResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped API extraction error: ${e.message}")
            }

            Log.e(TAG, "All extraction client configurations failed for video ID: $videoId")
            return@withContext null
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private fun isStreamPlayable(streamUrl: String, userAgent: String, cookies: String? = null): Boolean {
        return try {
            val u = URL(streamUrl)
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Range", "bytes=0-100")
            if (!cookies.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", cookies)
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299 || code == 206
        } catch (_: Exception) {
            false
        }
    }

    private fun tryExtractWithPiped(videoId: String): ExtractionResult? {
        val pipedEndpoints = listOf(
            "https://api.piped.private.coffee/streams/$videoId",
            "https://pipedapi.kavin.rocks/streams/$videoId",
        )
        for (endpoint in pipedEndpoints) {
            try {
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val json = JSONObject(resp)
                    val title = json.optString("title", "YouTube Video")
                    val streams = mutableListOf<YouTubeStream>()
                    val videoStreams = json.optJSONArray("videoStreams")
                    if (videoStreams != null) {
                        for (i in 0 until videoStreams.length()) {
                            val v = videoStreams.optJSONObject(i) ?: continue
                            val sUrl = v.optString("url")
                            if (sUrl.isNotBlank()) {
                                val quality = v.optString("quality", "Video")
                                val mimeType = v.optString("mimeType", "video/mp4")
                                val videoOnly = v.optBoolean("videoOnly", false)
                                val clen = v.optLong("contentLength", -1)
                                streams.add(YouTubeStream(
                                    url = sUrl,
                                    mimeType = mimeType,
                                    quality = quality,
                                    isAudio = false,
                                    isVideoOnly = videoOnly,
                                    sizeBytes = if (clen > 0) clen else null,
                                ))
                            }
                        }
                    }
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null) {
                        for (i in 0 until audioStreams.length()) {
                            val a = audioStreams.optJSONObject(i) ?: continue
                            val sUrl = a.optString("url")
                            if (sUrl.isNotBlank()) {
                                val quality = a.optString("quality", "Audio")
                                val mimeType = a.optString("mimeType", "audio/mp4")
                                val clen = a.optLong("contentLength", -1)
                                streams.add(YouTubeStream(
                                    url = sUrl,
                                    mimeType = mimeType,
                                    quality = quality,
                                    isAudio = true,
                                    isVideoOnly = false,
                                    sizeBytes = if (clen > 0) clen else null,
                                ))
                            }
                        }
                    }
                    if (streams.isNotEmpty()) {
                        return ExtractionResult(title = title, streams = streams)
                    }
                } else {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped endpoint $endpoint failed: ${e.message}")
            }
        }
        return null
    }

    private fun tryExtractWithClient(videoId: String, config: ClientConfig, cookies: String? = null): ExtractionResult? {
        try {
            val url = URL("https://www.youtube.com/youtubei/v1/player")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", config.userAgent)
            if (!cookies.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", cookies)
            }

            val jsonPayload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        config.buildPayloadContext(this)
                    })
                })
            }

            val writer = OutputStreamWriter(conn.outputStream, java.nio.charset.StandardCharsets.UTF_8)
            writer.write(jsonPayload.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Client ${config.name} request failed with HTTP $responseCode")
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val jsonResponse = JSONObject(response)

            val playabilityStatus = jsonResponse.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status")
            if (status != null && status != "OK") {
                val reason = playabilityStatus.optString("reason", "Video is not playable")
                Log.w(TAG, "Client ${config.name} reported playability status: $status - $reason")
                return null
            }

            val videoDetails = jsonResponse.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: "YouTube Video"

            val streamingData = jsonResponse.optJSONObject("streamingData") ?: return null
            val streams = mutableListOf<YouTubeStream>()

            // 1. Multiplexed formats (video + audio in one stream, e.g. 360p, 720p)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val mimeType = format.optString("mimeType")
                        val quality = format.optString("qualityLabel", "Format $i")
                        val contentLength = format.optLong("contentLength", -1)
                        streams.add(YouTubeStream(
                            url = streamUrl,
                            mimeType = mimeType,
                            quality = quality,
                            isAudio = false,
                            isVideoOnly = false,
                            sizeBytes = if (contentLength > 0) contentLength else null,
                        ))
                    }
                }
            }

            // 2. Adaptive formats (separate video-only and audio-only streams)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val mimeType = format.optString("mimeType")
                        val isAudio = mimeType.startsWith("audio/")
                        val quality = if (isAudio) {
                            val audioQuality = format.optString("audioQuality", "AUDIO_QUALITY_MEDIUM")
                                .removePrefix("AUDIO_QUALITY_")
                            val bitrate = format.optInt("bitrate", 0) / 1000
                            if (bitrate > 0) "${bitrate}kbps ($audioQuality)" else audioQuality
                        } else {
                            format.optString("qualityLabel", "Adaptive $i")
                        }
                        val contentLength = format.optLong("contentLength", -1)
                        streams.add(YouTubeStream(
                            url = streamUrl,
                            mimeType = mimeType,
                            quality = quality,
                            isAudio = isAudio,
                            isVideoOnly = !isAudio,
                            sizeBytes = if (contentLength > 0) contentLength else null,
                        ))
                    }
                }
            }

            return ExtractionResult(title, streams)
        } catch (e: Exception) {
            Log.w(TAG, "Client ${config.name} extraction failed with exception", e)
            return null
        }
    }
}
