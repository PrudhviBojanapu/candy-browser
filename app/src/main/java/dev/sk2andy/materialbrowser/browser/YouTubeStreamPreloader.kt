package dev.sk2andy.materialbrowser.browser
 
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PreloadedYouTubeStream(
    val videoId: String,
    val pageUrl: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val title: String? = null,
    val resolution: String = "4K Max",
    val headers: Map<String, String> = emptyMap(),
)

object YouTubeStreamPreloader {
    private const val TAG = "YouTubePreloader"

    private val _preloadedStream = MutableStateFlow<PreloadedYouTubeStream?>(null)
    val preloadedStream: StateFlow<PreloadedYouTubeStream?> = _preloadedStream.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var preloadJob: Job? = null
    private var currentVideoId: String? = null

    private val streamCache = ConcurrentHashMap<String, PreloadedYouTubeStream>()

    fun launchPreloadedStream(
        context: Context,
        stream: PreloadedYouTubeStream,
        onLaunched: (() -> Unit)? = null,
    ): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(stream.videoUrl), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            stream.audioUrl?.takeIf { it.isNotBlank() }?.let { putExtra("audio_url", it) }
            stream.title?.takeIf { it.isNotBlank() }?.let { putExtra("title", it) }
            putExtra("fallback_url", stream.pageUrl)
            putExtra("referer", "https://www.youtube.com/")
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            putExtra("user_agent", ua)
            val headerPairs = mutableListOf("Referer", "https://www.youtube.com/", "User-Agent", ua)
            stream.headers.forEach { (k, v) ->
                headerPairs.add(k)
                headerPairs.add(v)
            }
            putExtra("headers", headerPairs.toTypedArray())
            putExtra("direct_media", true)
            putExtra("ytdl", "no")
            putExtra("format_sort", "res,fps,br")
            putExtra("ytdl_format", "bestvideo+bestaudio/best")
        }

        val pm = context.packageManager
        val candidatePackages = listOf("app.gyrolet.mpvrx.debug", "app.gyrolet.mpvrx")
        val mpvPackage = candidatePackages.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        } ?: "app.gyrolet.mpvrx.debug"

        intent.component = ComponentName(mpvPackage, "app.gyrolet.mpvrx.ui.player.PlayerActivity")
        return runCatching {
            context.startActivity(intent)
            onLaunched?.invoke()
            true
        }.getOrDefault(false)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun isYouTubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("youtube.com/watch") ||
                lower.contains("youtu.be/") ||
                lower.contains("youtube.com/shorts/") ||
                lower.contains("m.youtube.com/watch")
    }

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val lower = url.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) return null

        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (lower.contains("youtu.be/")) {
            return uri.lastPathSegment?.takeIf { it.length == 11 }
        }
        if (lower.contains("/shorts/")) {
            return uri.lastPathSegment?.takeIf { it.length >= 10 }
        }
        return uri.getQueryParameter("v")?.takeIf { it.length == 11 }
    }

    fun onUrlChanged(context: Context, url: String?) {
        val effectiveUrl = url.orEmpty()
        val videoId = extractVideoId(effectiveUrl)
        if (videoId == null) {
            currentVideoId = null
            _preloadedStream.value = null
            _isLoading.value = false
            preloadJob?.cancel()
            return
        }

        if (videoId == currentVideoId && _preloadedStream.value != null) {
            return
        }

        currentVideoId = videoId
        val cached = streamCache[videoId]
        if (cached != null) {
            _preloadedStream.value = cached
            _isLoading.value = false
            return
        }

        _preloadedStream.value = null
        _isLoading.value = true
        preloadJob?.cancel()

        preloadJob = scope.launch {
            val result = resolveStream(context, effectiveUrl, videoId)
            if (result != null && currentVideoId == videoId) {
                streamCache[videoId] = result
                _preloadedStream.value = result
                Log.i(TAG, "Pre-grabbed YouTube stream successfully: ${result.resolution} for $videoId")
            }
            if (currentVideoId == videoId) {
                _isLoading.value = false
            }
        }
    }

    private fun resolveStream(context: Context, pageUrl: String, videoId: String): PreloadedYouTubeStream? {
        val cleanUrl = "https://www.youtube.com/watch?v=$videoId"
        // Strategy 1: mpvRx YtdlpStreamProvider via ContentResolver
        val mpvResult = queryMpvStreamProvider(context, cleanUrl, videoId)
        if (mpvResult != null) return mpvResult

        // Strategy 2: Fast Public Stream Invidious/Piped endpoint
        val fastResult = queryFastStreamApi(cleanUrl, videoId)
        if (fastResult != null) return fastResult

        return null
    }

    private fun queryMpvStreamProvider(context: Context, pageUrl: String, videoId: String): PreloadedYouTubeStream? {
        val authorities = listOf(
            "app.gyrolet.mpvrx.debug.ytdlp",
            "app.gyrolet.mpvrx.ytdlp"
        )
        for (auth in authorities) {
            try {
                val uri = Uri.parse("content://$auth/resolve")
                val extras = Bundle().apply {
                    putString("format", "bestvideo+bestaudio/best")
                    putString("format_sort", "res,fps,br")
                }
                val bundle = context.contentResolver.call(uri, "resolveStream", pageUrl, extras)
                if (bundle != null && bundle.getBoolean("success", false)) {
                    val videoUrl = bundle.getString("video_url")
                    if (!videoUrl.isNullOrBlank()) {
                        val audioUrl = bundle.getString("audio_url")
                        val title = bundle.getString("title")
                        val resolution = bundle.getString("resolution").takeIf { !it.isNullOrBlank() } ?: "4K Max"
                        val keys = bundle.getStringArrayList("header_keys") ?: ArrayList()
                        val values = bundle.getStringArrayList("header_values") ?: ArrayList()
                        val headers = mutableMapOf<String, String>()
                        for (i in 0 until minOf(keys.size, values.size)) {
                            headers[keys[i]] = values[i]
                        }
                        return PreloadedYouTubeStream(
                            videoId = videoId,
                            pageUrl = pageUrl,
                            videoUrl = videoUrl,
                            audioUrl = audioUrl,
                            title = title,
                            resolution = resolution,
                            headers = headers,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Provider call error on $auth: ${e.message}")
            }
        }
        return null
    }

    private fun queryFastStreamApi(pageUrl: String, videoId: String): PreloadedYouTubeStream? {
        val endpoints = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://api.piped.private.coffee/streams/$videoId",
            "https://inv.tux.pizza/api/v1/videos/$videoId?fields=title,adaptiveFormats,formatStreams",
            "https://invidious.nerdvpn.de/api/v1/videos/$videoId?fields=title,adaptiveFormats,formatStreams"
        )

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)

                    if (json.has("videoStreams")) {
                        val title = json.optString("title")
                        val videoStreams = json.optJSONArray("videoStreams")
                        val audioStreams = json.optJSONArray("audioStreams")
                        var bestVideoUrl: String? = null
                        var bestResolution = "1080p Max"
                        var maxHeight = 0

                        if (videoStreams != null) {
                            for (i in 0 until videoStreams.length()) {
                                val s = videoStreams.optJSONObject(i) ?: continue
                                val h = s.optInt("height", 0)
                                val u = s.optString("url")
                                if (u.isNotBlank() && h > maxHeight) {
                                    maxHeight = h
                                    bestVideoUrl = u
                                    bestResolution = if (h >= 2160) "4K" else "${h}p"
                                }
                            }
                        }

                        var bestAudioUrl: String? = null
                        if (audioStreams != null && audioStreams.length() > 0) {
                            bestAudioUrl = audioStreams.optJSONObject(0)?.optString("url")
                        }

                        if (!bestVideoUrl.isNullOrBlank()) {
                            return PreloadedYouTubeStream(
                                videoId = videoId,
                                pageUrl = pageUrl,
                                videoUrl = bestVideoUrl,
                                audioUrl = bestAudioUrl,
                                title = title,
                                resolution = bestResolution,
                            )
                        }
                    }

                    if (json.has("adaptiveFormats")) {
                        val title = json.optString("title")
                        val formats = json.optJSONArray("adaptiveFormats")
                        var bestVideoUrl: String? = null
                        var bestAudioUrl: String? = null
                        var maxHeight = 0
                        var bestResolution = "1080p Max"

                        if (formats != null) {
                            for (i in 0 until formats.length()) {
                                val f = formats.optJSONObject(i) ?: continue
                                val type = f.optString("type")
                                val url = f.optString("url")
                                if (url.isBlank()) continue

                                if (type.startsWith("video/")) {
                                    val h = runCatching { f.optString("resolution").replace("p", "").toInt() }.getOrDefault(0)
                                    if (h > maxHeight) {
                                        maxHeight = h
                                        bestVideoUrl = url
                                        bestResolution = if (h >= 2160) "4K" else "${h}p"
                                    }
                                } else if (type.startsWith("audio/") && bestAudioUrl == null) {
                                    bestAudioUrl = url
                                }
                            }
                        }

                        if (!bestVideoUrl.isNullOrBlank()) {
                            return PreloadedYouTubeStream(
                                videoId = videoId,
                                pageUrl = pageUrl,
                                videoUrl = bestVideoUrl,
                                audioUrl = bestAudioUrl,
                                title = title,
                                resolution = bestResolution,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Fast API query error: ${e.message}")
            }
        }
        return null
    }
}
