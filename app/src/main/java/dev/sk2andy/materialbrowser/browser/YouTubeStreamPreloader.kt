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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
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
    val isDirectMedia: Boolean = true,
    val isPreloaded: Boolean = false,
)

object YouTubeStreamPreloader {
    private const val TAG = "YouTubePreloader"

    private val _preloadedStream = MutableStateFlow<PreloadedYouTubeStream?>(null)
    val preloadedStream: StateFlow<PreloadedYouTubeStream?> = _preloadedStream.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isPrefetchedAndReady = MutableStateFlow(false)
    val isPrefetchedAndReady: StateFlow<Boolean> = _isPrefetchedAndReady.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var preloadJob: Job? = null
    private var currentVideoId: String? = null

    private val streamCache = ConcurrentHashMap<String, PreloadedYouTubeStream>()

    var isPreloadEnabled: (() -> Boolean)? = null

    fun clear() {
        currentVideoId = null
        _preloadedStream.value = null
        _isLoading.value = false
        _isPrefetchedAndReady.value = false
        preloadJob?.cancel()
        MediaStreamDetectorBridge.clearActiveStream()
    }

    private val KNOWN_VIDEO_HOSTS = listOf(
        // Video & Social platforms
        "tiktok.com", "twitter.com", "x.com", "twitch.tv", "kick.com",
        "bilibili.com", "vimeo.com", "dailymotion.com", "reddit.com",
        "facebook.com", "instagram.com", "threads.net", "rumble.com",
        "streamable.com", "bitchute.com", "odysee.com",
        // Adult sites supported by yt-dlp
        "pornhub.com", "xvideos.com", "xnxx.com", "spankbang.com",
        "eporner.com", "redtube.com", "youporn.com", "tube8.com",
        "chaturbate.com", "erome.com", "sunporno.com", "pornerbros.com",
        "hqporner.com", "brazzers.com", "beeg.com", "stripchat.com",
        "cam4.com", "camsoda.com", "bongacams.com"
    )

    fun launchPreloadedStream(
        context: Context,
        stream: PreloadedYouTubeStream,
        onLaunched: (() -> Unit)? = null,
    ): Boolean {
        val isYt = isYouTubeUrl(stream.pageUrl) || isYouTubeUrl(stream.videoUrl) ||
            stream.videoUrl.contains("googlevideo.com") ||
            (stream.videoId.length == 11 && !stream.videoId.contains("/"))
        val cleanYtUrl = if (isYt) {
            val vid = extractVideoId(stream.pageUrl) ?: extractVideoId(stream.videoUrl) ?: stream.videoId
            "https://www.youtube.com/watch?v=$vid"
        } else null

        val isDirectStream = !isYt && stream.isDirectMedia && stream.videoUrl.isNotBlank() &&
            (stream.videoUrl.startsWith("http://", ignoreCase = true) || stream.videoUrl.startsWith("https://", ignoreCase = true))
        val isYtdl = isYt || (!isDirectStream && stream.videoUrl.isBlank())
        val playUri = if (isYt) (cleanYtUrl ?: stream.pageUrl) else (if (isDirectStream) stream.videoUrl else stream.pageUrl)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(playUri), "video/*")
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (isDirectStream) {
                stream.audioUrl?.takeIf { it.isNotBlank() }?.let { putExtra("audio_url", it) }
            }
            stream.title?.takeIf { it.isNotBlank() }?.let { putExtra("title", it) }
            putExtra("fallback_url", cleanYtUrl ?: stream.pageUrl)
            val referer = stream.headers["Referer"] ?: stream.headers["referer"] ?: stream.pageUrl
            putExtra("referer", referer)
            putExtra("extra_referrer", referer)
            val ua = stream.headers["User-Agent"] ?: stream.headers["user-agent"]
                ?: "Mozilla/5.0 (Android 15; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"
            putExtra("user_agent", ua)
            putExtra("extra_user_agent", ua)
            val headerPairs = mutableListOf<String>()
            if (!stream.headers.keys.any { it.equals("Referer", ignoreCase = true) }) {
                headerPairs.add("Referer")
                headerPairs.add(referer)
            }
            if (!stream.headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                headerPairs.add("User-Agent")
                headerPairs.add(ua)
            }
            val hasOrigin = stream.headers.keys.any { it.equals("Origin", ignoreCase = true) }
            if (!hasOrigin && referer.isNotBlank()) {
                val origin = runCatching {
                    val u = Uri.parse(referer)
                    if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null
                }.getOrNull()
                if (origin != null) {
                    headerPairs.add("Origin")
                    headerPairs.add(origin)
                }
            }
            stream.headers.forEach { (k, v) ->
                val lower = k.lowercase()
                if (lower != "accept-encoding" && lower != "connection" && lower != "host" && lower != "content-length" && !lower.startsWith("sec-")) {
                    headerPairs.add(k)
                    headerPairs.add(v)
                }
            }
            putExtra("headers", headerPairs.toTypedArray())
            if (isDirectStream) {
                putExtra("direct_media", true)
                putExtra("ytdl", "no")
            } else {
                putExtra("direct_media", false)
                putExtra("ytdl", "yes")
            }
            putExtra("format_sort", "res,fps,br")
            putExtra("ytdl_format", "bestvideo+bestaudio/best")
        }

        val pm = context.packageManager
        val candidatePackages = listOf("app.myaioapp", "app.gyrolet.mpvrx", "app.gyrolet.mpvrx.debug", context.packageName)
        val mpvPackage = candidatePackages.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        } ?: "app.myaioapp"

        intent.component = ComponentName(mpvPackage, "app.gyrolet.mpvrx.ui.player.PlayerActivity")
        return runCatching {
            context.startActivity(intent)
            onLaunched?.invoke()
            true
        }.getOrElse {
            runCatching {
                intent.component = null
                intent.setPackage("app.myaioapp")
                context.startActivity(intent)
                onLaunched?.invoke()
                true
            }.getOrDefault(false)
        }
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
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    fun isCandidateVideoPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (isYouTubeUrl(url)) {
            return extractVideoId(url) != null
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".m3u8") || lower.endsWith(".mpd") ||
            lower.endsWith(".webm") || lower.endsWith(".mkv")
        ) {
            return true
        }
        val isKnownHost = KNOWN_VIDEO_HOSTS.any { lower.contains(it) }
        if (isKnownHost) {
            return lower.contains("/watch") || lower.contains("/video") ||
                lower.contains("/v/") || lower.contains("/status/") || lower.contains("/view_video") ||
                lower.contains("/clip") || lower.contains("/reel") || lower.contains("/shorts") ||
                lower.contains("/post/") || lower.contains("viewkey=") || lower.contains("/p/") ||
                lower.contains(".mp4") || lower.contains(".m3u8") ||
                lower.contains("twitch.tv/") || lower.contains("kick.com/") || lower.contains("chaturbate.com/")
        }
        return lower.contains("/video/") || lower.contains("/watch/") ||
            lower.contains("/v/") || lower.contains("/view_video.php")
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
        if (lower.contains("/embed/")) {
            return uri.lastPathSegment?.takeIf { it.length == 11 }
        }
        val vParam = uri.getQueryParameter("v")
        if (!vParam.isNullOrBlank()) {
            return vParam.substringBefore('&').takeIf { it.length == 11 }
        }
        val match = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    fun extractMediaKey(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val ytId = extractVideoId(url)
        if (ytId != null) return ytId
        if (isCandidateVideoPage(url)) return url.trim()
        return null
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun getMediaCacheDir(context: Context): File {
        val candidates = listOf(
            File("/data/data/app.myaioapp/cache/media_stream_cache"),
            File("/data/user/0/app.myaioapp/cache/media_stream_cache"),
            File(context.cacheDir, "media_stream_cache"),
        )
        for (c in candidates) {
            if (runCatching { c.mkdirs() }.isSuccess) return c
        }
        return File(context.cacheDir, "media_stream_cache").apply { mkdirs() }
    }

    private fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
        val trimmed = relativeUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val base = java.net.URI(baseUrl)
            base.resolve(trimmed).toASCIIString()
        } catch (_: Exception) {
            if (trimmed.startsWith("/")) {
                val baseUri = Uri.parse(baseUrl)
                "${baseUri.scheme}://${baseUri.host}$trimmed"
            } else {
                val lastSlash = baseUrl.lastIndexOf('/')
                if (lastSlash != -1) {
                    baseUrl.substring(0, lastSlash + 1) + trimmed
                } else {
                    trimmed
                }
            }
        }
    }

    private fun applyPreloadHeaders(builder: Request.Builder, headers: Map<String, String>, sourceUrl: String) {
        val referer = headers["Referer"] ?: headers["referer"] ?: sourceUrl
        val ua = headers["User-Agent"] ?: headers["user-agent"]
            ?: "Mozilla/5.0 (Android 15; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"
        builder.header("Referer", referer)
        builder.header("User-Agent", ua)
        headers.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower != "accept-encoding" && lower != "connection" && lower != "host" && lower != "content-length" && !lower.startsWith("sec-")) {
                builder.header(k, v)
            }
        }
    }

    private suspend fun preload5To10Percent(context: Context, stream: PreloadedYouTubeStream): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = getMediaCacheDir(context)
        val url = stream.videoUrl
        val isHls = url.contains(".m3u8", ignoreCase = true) || stream.headers["Content-Type"]?.contains("mpegurl", ignoreCase = true) == true

        if (isHls) {
            preloadHlsStream(cacheDir, stream)
        } else {
            preloadProgressiveStream(cacheDir, stream)
        }
    }

    private fun preloadHlsStream(cacheDir: File, stream: PreloadedYouTubeStream): Boolean {
        try {
            // 1. Fetch initial manifest
            val reqBuilder = Request.Builder().url(stream.videoUrl)
            applyPreloadHeaders(reqBuilder, stream.headers, stream.videoUrl)
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) return false
            val body = resp.body?.string() ?: return false
            val finalUrl = resp.request.url.toString()
            resp.close()

            // 2. Check if master manifest or variant
            val isMaster = body.contains("#EXT-X-STREAM-INF:", ignoreCase = true)
            val variantUrl = if (isMaster) {
                val lines = body.lines()
                var selectedVariant: String? = null
                for (i in lines.indices) {
                    if (lines[i].trim().startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                        for (j in (i + 1) until lines.size) {
                            val candidate = lines[j].trim()
                            if (candidate.isNotBlank() && !candidate.startsWith("#")) {
                                selectedVariant = resolveRelativeUrl(finalUrl, candidate)
                                break
                            }
                        }
                        if (selectedVariant != null) break
                    }
                }
                selectedVariant ?: finalUrl
            } else {
                finalUrl
            }

            // 3. Fetch variant manifest if master
            val variantText = if (isMaster && variantUrl != finalUrl) {
                val vReq = Request.Builder().url(variantUrl)
                applyPreloadHeaders(vReq, stream.headers, variantUrl)
                val vResp = httpClient.newCall(vReq.build()).execute()
                val vBody = vResp.body?.string() ?: return false
                vResp.close()
                vBody
            } else {
                body
            }

            // 4. Parse segment URLs and keys
            val segmentUrls = mutableListOf<String>()
            val keyUrls = mutableListOf<String>()
            for (rawLine in variantText.lines()) {
                val line = rawLine.trim()
                if (line.startsWith("#EXT-X-KEY:", ignoreCase = true)) {
                    val uriRegex = Regex("""URI="([^"]+)"""")
                    uriRegex.find(line)?.groupValues?.getOrNull(1)?.let { keyUri ->
                        keyUrls.add(resolveRelativeUrl(variantUrl, keyUri))
                    }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    segmentUrls.add(resolveRelativeUrl(variantUrl, line))
                }
            }

            // Preload any encryption keys
            for (kUrl in keyUrls) {
                val keyFile = File(cacheDir, "key_${sha256(kUrl)}.bin")
                if (!keyFile.exists() || keyFile.length() == 0L) {
                    val kReq = Request.Builder().url(kUrl)
                    applyPreloadHeaders(kReq, stream.headers, variantUrl)
                    runCatching {
                        httpClient.newCall(kReq.build()).execute().use { kResp ->
                            kResp.body?.bytes()?.let { keyFile.writeBytes(it) }
                        }
                    }
                }
            }

            if (segmentUrls.isEmpty()) return false

            // Preload 5% to 10% of total segments (capped at 2 to 5 segments)
            val total = segmentUrls.size
            val targetCount = maxOf(2, minOf(5, (total * 0.08f).toInt().coerceAtLeast(2)))
            Log.d(TAG, "Preloading $targetCount of $total HLS segments (5-10%) for ${stream.videoId}")

            for (i in 0 until minOf(targetCount, segmentUrls.size)) {
                val segUrl = segmentUrls[i]
                val cacheFile = File(cacheDir, "seg_${sha256(segUrl)}.ts")
                if (cacheFile.exists() && cacheFile.length() > 0L) continue

                val sReq = Request.Builder().url(segUrl)
                applyPreloadHeaders(sReq, stream.headers, variantUrl)
                try {
                    httpClient.newCall(sReq.build()).execute().use { sResp ->
                        val bytes = sResp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            cacheFile.writeBytes(bytes)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to preload segment $i: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "HLS preload error: ${e.message}")
            return false
        }
    }

    private fun preloadProgressiveStream(cacheDir: File, stream: PreloadedYouTubeStream): Boolean {
        try {
            val headReq = Request.Builder().url(stream.videoUrl).head()
            applyPreloadHeaders(headReq, stream.headers, stream.videoUrl)
            var totalBytes = -1L
            runCatching {
                httpClient.newCall(headReq.build()).execute().use { resp ->
                    totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
            }

            val targetBytes = if (totalBytes > 0L) {
                maxOf(2L * 1024 * 1024, minOf(12L * 1024 * 1024, (totalBytes * 0.08).toLong()))
            } else {
                4L * 1024 * 1024
            }

            val cacheFile = File(cacheDir, "prog_${sha256(stream.videoUrl)}.part")
            if (!cacheFile.exists() || cacheFile.length() < targetBytes) {
                val rangeReq = Request.Builder()
                    .url(stream.videoUrl)
                    .header("Range", "bytes=0-${targetBytes - 1}")
                applyPreloadHeaders(rangeReq, stream.headers, stream.videoUrl)
                val ok = httpClient.newCall(rangeReq.build()).execute().use { rResp ->
                    if (!rResp.isSuccessful && rResp.code != 206 && rResp.code != 200) {
                        Log.w(TAG, "Progressive preload HTTP ${rResp.code} for ${stream.videoUrl}")
                        return@use false
                    }
                    val body = rResp.body ?: return@use false
                    val sink = cacheFile.outputStream()
                    val buffer = ByteArray(32 * 1024)
                    var readTotal = 0L
                    body.byteStream().use { input ->
                        sink.use { output ->
                            while (readTotal < targetBytes) {
                                val toRead = minOf(buffer.size.toLong(), targetBytes - readTotal).toInt()
                                val read = input.read(buffer, 0, toRead)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                readTotal += read
                            }
                        }
                    }
                    Log.d(TAG, "Preloaded progressive $readTotal bytes (5-10%)")
                    readTotal > 0L
                }
                if (!ok && cacheFile.length() == 0L) return true
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Progressive preload error: ${e.message}")
            return true
        }
    }

    fun isAdOrAuxiliaryMedia(url: String?, sizeBytes: Long? = null): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()

        // Ad networks and tracking
        if (lower.contains("doubleclick") || lower.contains("googlead") || lower.contains("/pagead/") ||
            lower.contains("/ads/") || lower.contains("/ad/") || lower.contains("adserver") ||
            lower.contains("adsystem") || lower.contains("popads") || lower.contains("syndication") ||
            lower.contains("exoclick") || lower.contains("juicyads") || lower.contains("trafficjunky") ||
            lower.contains("ero-advertising") || lower.contains("outbrain") || lower.contains("taboola") ||
            lower.contains("smartadserver") || lower.contains("adnxs") || lower.contains("rubiconproject")
        ) {
            return true
        }

        // Subtitles, thumbnails, sprites, posters, audio, images
        if (lower.contains(".vtt") || lower.contains(".srt") || lower.contains(".ass") ||
            lower.contains("sprite") || lower.contains("thumbnail") || lower.contains("thumb") ||
            lower.contains("poster") || lower.contains("still") || lower.contains("snapshot") ||
            lower.contains(".gif") || lower.contains(".png") || lower.contains(".jpg") ||
            lower.contains(".jpeg") || lower.contains(".webp") || lower.contains(".svg") ||
            lower.contains(".ico") || lower.contains(".bmp") || lower.contains(".css") ||
            lower.contains(".js")
        ) {
            return true
        }

        // Short previews, teasers, trailers, hover clips
        if (lower.contains("/preview") || lower.contains("_preview") || lower.contains("-preview") ||
            lower.contains("/trailer") || lower.contains("_trailer") || lower.contains("-trailer") ||
            lower.contains("/teaser") || lower.contains("_teaser") || lower.contains("-teaser") ||
            lower.contains("/hover") || lower.contains("_hover") || lower.contains("-hover") ||
            lower.contains("/sample") || lower.contains("_sample") || lower.contains("-sample") ||
            lower.contains("short_video") || lower.contains("story_preview")
        ) {
            return true
        }

        if (sizeBytes != null && sizeBytes in 1..2_500_000L) {
            return true
        }

        return false
    }

    fun onDirectStreamDetected(payload: VideoStreamPayload) {
        if (isPreloadEnabled?.invoke() == false) {
            return
        }
        if (isAdOrAuxiliaryMedia(payload.url)) {
            Log.d(TAG, "Ignoring direct stream detection for auxiliary/ad media: ${payload.url}")
            return
        }
        val isYt = isYouTubeUrl(payload.referer) || isYouTubeUrl(currentVideoId) ||
            payload.url.contains("googlevideo.com") || payload.referer?.contains("youtube.com") == true ||
            payload.referer?.contains("youtu.be") == true
        if (isYt) {
            Log.d(TAG, "Ignoring direct stream detection for YouTube; must be resolved via YouTubeExtractor / yt-dlp")
            return
        }

        val directStream = PreloadedYouTubeStream(
            videoId = currentVideoId ?: payload.url,
            pageUrl = payload.referer ?: payload.url,
            videoUrl = payload.url,
            title = payload.title ?: _preloadedStream.value?.title,
            resolution = "Direct Stream",
            headers = payload.headers,
            isDirectMedia = true,
            isPreloaded = false,
        )
        val current = _preloadedStream.value
        if (current?.videoUrl == directStream.videoUrl && _isPrefetchedAndReady.value) {
            return
        }

        preloadJob?.cancel()
        _isLoading.value = true
        _isPrefetchedAndReady.value = false

        preloadJob = scope.launch {
            val ctx = appContext
            var prefetched = false
            if (ctx != null) {
                prefetched = preload5To10Percent(ctx, directStream)
            }
            if (currentVideoId == null || currentVideoId == directStream.videoId) {
                val readyStream = directStream.copy(isPreloaded = true)
                currentVideoId?.let { streamCache[it] = readyStream }
                streamCache[directStream.videoId] = readyStream
                _preloadedStream.value = readyStream
                _isLoading.value = false
                _isPrefetchedAndReady.value = true
                Log.i(TAG, "Direct stream 5-10% prefetched ($prefetched) and button ready: ${directStream.videoUrl}")
            }
        }
    }

    fun onUrlChanged(context: Context, url: String?) {
        init(context)
        if (isPreloadEnabled?.invoke() == false) {
            clear()
            return
        }
        val effectiveUrl = url.orEmpty().trim()
        if (effectiveUrl.isBlank() || effectiveUrl == "about:blank" || effectiveUrl == "data:" || effectiveUrl.startsWith("blob:")) {
            return
        }
        val mediaKey = extractMediaKey(effectiveUrl) ?: effectiveUrl
        if (mediaKey == currentVideoId && (_isPrefetchedAndReady.value || _isLoading.value)) {
            return
        }

        MediaStreamDetectorBridge.clearActiveStream()

        currentVideoId = mediaKey
        val cached = streamCache[mediaKey]
        if (cached != null && cached.isPreloaded && !isAdOrAuxiliaryMedia(cached.videoUrl)) {
            _preloadedStream.value = cached
            _isLoading.value = false
            _isPrefetchedAndReady.value = true
            return
        }

        _preloadedStream.value = null
        _isPrefetchedAndReady.value = false
        preloadJob?.cancel()

        if (!isCandidateVideoPage(effectiveUrl)) {
            _isLoading.value = false
            return
        }

        _isLoading.value = true

        preloadJob = scope.launch {
            val result = resolveStream(context, effectiveUrl, mediaKey)
            if (result != null && currentVideoId == mediaKey) {
                val prefetched = preload5To10Percent(context, result)
                if (currentVideoId == mediaKey) {
                    val readyStream = result.copy(isPreloaded = true)
                    streamCache[mediaKey] = readyStream
                    _preloadedStream.value = readyStream
                    _isLoading.value = false
                    _isPrefetchedAndReady.value = true
                    Log.i(TAG, "Prefetched 5-10% ($prefetched) and stream ready: ${result.resolution} for $mediaKey")
                }
            } else if (currentVideoId == mediaKey) {
                _isLoading.value = false
                _isPrefetchedAndReady.value = false
                _preloadedStream.value = null
            }
        }
    }

    private suspend fun resolveStream(context: Context, pageUrl: String, mediaKey: String): PreloadedYouTubeStream? {
        val isYt = isYouTubeUrl(pageUrl)
        val cleanUrl = if (isYt) {
            val vid = extractVideoId(pageUrl) ?: mediaKey
            "https://www.youtube.com/watch?v=$vid"
        } else {
            pageUrl
        }

        // Method 1: System / MPV yt-dlp Provider (via ContentResolver)
        // High-fidelity extraction for known platforms (YouTube, eporner, pornhub, twitter, etc.)
        val isKnownSite = isYt || KNOWN_VIDEO_HOSTS.any { cleanUrl.lowercase().contains(it) }
        if (isKnownSite) {
            val ytdlpResult = queryMpvStreamProvider(context, cleanUrl, mediaKey)
            if (ytdlpResult != null && !isAdOrAuxiliaryMedia(ytdlpResult.videoUrl)) {
                Log.i(TAG, "Resolved stream via yt-dlp Provider: ${ytdlpResult.resolution} for $cleanUrl")
                return ytdlpResult
            }
        }

        // Method 2: Fast YouTubeExtractor InnerTube Engine for YouTube fallback
        if (isYt) {
            try {
                val ytResult = YouTubeExtractor.extractStreams(cleanUrl)
                if (ytResult != null && ytResult.streams.isNotEmpty()) {
                    val muxed = ytResult.streams.firstOrNull { !it.isAudio && !it.isVideoOnly && it.url.isNotBlank() }
                    val videoOnly = ytResult.streams.firstOrNull { !it.isAudio && it.isVideoOnly && it.url.isNotBlank() }
                    val audioOnly = ytResult.streams.firstOrNull { it.isAudio && it.url.isNotBlank() }

                    val chosenVideo = muxed ?: videoOnly
                    if (chosenVideo != null) {
                        Log.i(TAG, "Resolved YouTube stream via Inbuilt YouTubeExtractor: ${chosenVideo.quality}")
                        return PreloadedYouTubeStream(
                            videoId = mediaKey,
                            pageUrl = pageUrl,
                            videoUrl = chosenVideo.url,
                            audioUrl = if (muxed != null) null else audioOnly?.url,
                            title = ytResult.title,
                            resolution = chosenVideo.quality,
                            headers = buildMap {
                                put("Referer", "https://www.youtube.com/")
                                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                val cookies = runCatching { android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com") }.getOrNull()
                                if (!cookies.isNullOrBlank()) {
                                    put("Cookie", cookies)
                                }
                            },
                            isDirectMedia = false,
                            isPreloaded = true,
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Inbuilt YouTubeExtractor failed: ${t.message}")
            }
        }

        // Method 3: MediaStreamDetectorBridge captured stream
        val detected = MediaStreamDetectorBridge.activeStreamPayload.value
        if (detected != null && !isAdOrAuxiliaryMedia(detected.url) &&
            (detected.referer == pageUrl || pageUrl.contains(detected.url) || detected.url.isNotBlank())
        ) {
            return PreloadedYouTubeStream(
                videoId = mediaKey,
                pageUrl = pageUrl,
                videoUrl = detected.url,
                title = detected.title,
                resolution = "Direct Stream",
                headers = detected.headers,
                isDirectMedia = true,
                isPreloaded = true,
            )
        }

        // Method 4: yt-dlp Provider check for any remaining candidate sites
        if (!isKnownSite) {
            val ytdlpResult = queryMpvStreamProvider(context, cleanUrl, mediaKey)
            if (ytdlpResult != null && !isAdOrAuxiliaryMedia(ytdlpResult.videoUrl)) {
                return ytdlpResult
            }
        }

        // Method 5: Fast Public Invidious/Piped endpoint (for YouTube)
        if (isYt) {
            val ytVideoId = extractVideoId(pageUrl) ?: mediaKey
            val fastResult = queryFastStreamApi(cleanUrl, ytVideoId)
            if (fastResult != null && !isAdOrAuxiliaryMedia(fastResult.videoUrl)) {
                return fastResult
            }
            return null
        }

        return null
    }

    private fun queryMpvStreamProvider(context: Context, pageUrl: String, videoId: String): PreloadedYouTubeStream? {
        val authorities = listOf(
            "${context.packageName}.ytdlp",
            "${context.packageName}.gecko.ytdlp",
            "app.myaioapp.gecko.ytdlp",
            "app.myaioapp.ytdlp",
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
                        val isYt = isYouTubeUrl(pageUrl)
                        return PreloadedYouTubeStream(
                            videoId = videoId,
                            pageUrl = pageUrl,
                            videoUrl = videoUrl,
                            audioUrl = audioUrl,
                            title = title,
                            resolution = resolution,
                            headers = headers,
                            isDirectMedia = !isYt,
                            isPreloaded = true,
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
