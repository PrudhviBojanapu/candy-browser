package dev.sk2andy.materialbrowser.browser

import android.webkit.WebResourceRequest
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoStreamPayload(
    val url: String,
    val title: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

object MediaStreamDetectorBridge {
    @Volatile
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        YouTubeStreamPreloader.init(context)
    }

    private val _activeStreamPayload = MutableStateFlow<VideoStreamPayload?>(null)
    val activeStreamPayload: StateFlow<VideoStreamPayload?> = _activeStreamPayload.asStateFlow()

    var isDetectionEnabled: (() -> Boolean)? = null

    private val MEDIA_QUERY_PARAMS = listOf(
        "mu", "file", "url", "stream", "source", "src", "video_url", "video", "media", "m3u8", "mpd", "link"
    )

    private val REFERER_QUERY_PARAMS = listOf(
        "pu", "ref", "referer", "page", "orig", "origin"
    )

    private val JUNK_EXTENSIONS = listOf(
        ".gif", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".ico", ".bmp", ".css", ".js", ".woff", ".woff2", ".ttf", ".json", ".xml"
    )

    private val JUNK_DOMAINS_AND_PATHS = listOf(
        "jwpltx.com", "/ping", "/beacon", "/analytics", "/telemetry", "/pixel", "/track", "/collector",
        "doubleclick.net", "googleads", "/pagead/", "adnxs.com", "scorecardresearch.com",
        "facebook.com/tr", "googletagmanager.com", "hotjar.com", "clarity.ms"
    )

    private val SEGMENT_PATTERNS = listOf(
        ".ts?", ".ts&", ".m4s?", ".m4s&", ".vtt", ".srt"
    )

    fun isJunkOrBeaconUrl(rawUrl: String): Boolean {
        val cleanUrl = rawUrl.lowercase()
        val pathOnly = cleanUrl.substringBefore("?").substringBefore("#")
        if (JUNK_EXTENSIONS.any { pathOnly.endsWith(it) }) return true
        if (JUNK_DOMAINS_AND_PATHS.any { cleanUrl.contains(it) }) return true
        if (SEGMENT_PATTERNS.any { cleanUrl.contains(it) } || pathOnly.endsWith(".ts") || pathOnly.endsWith(".m4s")) return true
        return false
    }

    fun isDirectMediaCandidate(url: String): Boolean {
        val cleanUrl = url.lowercase()
        val pathOnly = cleanUrl.substringBefore("?").substringBefore("#")

        if (SEGMENT_PATTERNS.any { cleanUrl.contains(it) } || pathOnly.endsWith(".ts") || pathOnly.endsWith(".m4s")) {
            return false
        }
        if (JUNK_EXTENSIONS.any { pathOnly.endsWith(it) }) {
            return false
        }
        if (cleanUrl.contains("doubleclick.net") || cleanUrl.contains("googleads") || cleanUrl.contains("/pagead/")) {
            return false
        }

        return pathOnly.endsWith(".m3u8") ||
                pathOnly.endsWith(".mpd") ||
                pathOnly.endsWith(".mp4") ||
                pathOnly.endsWith(".webm") ||
                pathOnly.endsWith(".mkv") ||
                cleanUrl.contains(".m3u8") ||
                cleanUrl.contains(".mpd") ||
                cleanUrl.contains(".mp4") ||
                cleanUrl.contains(".webm") ||
                cleanUrl.contains("/videoplayback?") ||
                cleanUrl.contains("googlevideo.com") ||
                cleanUrl.contains("/manifest.mpd") ||
                cleanUrl.contains("/manifest.m3u8") ||
                cleanUrl.contains("/manifest/video") ||
                cleanUrl.contains("/manifest/hls") ||
                cleanUrl.contains("/master") ||
                cleanUrl.contains("/playlist") ||
                cleanUrl.contains("/hls/") ||
                cleanUrl.contains("format=m3u8") ||
                cleanUrl.contains("type=m3u8")
    }

    /**
     * Attempts to unpack nested media streams from beacon tracking pings or player wrapper URLs
     * e.g. https://prd.jwpltx.com/.../ping.gif?...&mu=https%3A%2F%2F...master.m3u8&pu=https%3A%2F%2Fjav.guru
     */
    fun extractRealMediaInfo(rawUrl: String): Pair<String, String?>? {
        if (!rawUrl.contains("?") && !rawUrl.contains("&")) return null
        return try {
            val uri = android.net.Uri.parse(rawUrl)
            var extractedMediaUrl: String? = null
            var extractedReferer: String? = null

            for (param in MEDIA_QUERY_PARAMS) {
                val candidate = uri.getQueryParameter(param)
                if (!candidate.isNullOrBlank()) {
                    var decoded = candidate
                    if (decoded.contains("%")) {
                        decoded = runCatching { java.net.URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
                    }
                    if (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true)) {
                        if (isDirectMediaCandidate(decoded) && !isJunkOrBeaconUrl(decoded)) {
                            extractedMediaUrl = decoded
                            break
                        }
                    }
                }
            }

            for (param in REFERER_QUERY_PARAMS) {
                val candidate = uri.getQueryParameter(param)
                if (!candidate.isNullOrBlank()) {
                    var decoded = candidate
                    if (decoded.contains("%")) {
                        decoded = runCatching { java.net.URLDecoder.decode(decoded, "UTF-8") }.getOrDefault(decoded)
                    }
                    if (decoded.startsWith("http://", ignoreCase = true) || decoded.startsWith("https://", ignoreCase = true)) {
                        extractedReferer = decoded
                        break
                    }
                }
            }

            if (extractedMediaUrl != null) {
                Pair(extractedMediaUrl, extractedReferer)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getStreamPriority(url: String): Int {
        val cleanUrl = url.lowercase()
        return when {
            cleanUrl.contains("master.m3u8") || cleanUrl.contains("/master.") -> 120
            cleanUrl.contains(".m3u8") || cleanUrl.contains(".mpd") || cleanUrl.contains("/manifest") -> 100
            cleanUrl.contains(".mp4") || cleanUrl.contains(".webm") || cleanUrl.contains(".mkv") -> 50
            cleanUrl.contains("googlevideo.com") || cleanUrl.contains("/videoplayback") -> 20
            else -> 10
        }
    }

    fun isMediaStream(url: String): Boolean {
        if (url.isBlank()) return false
        val unpacked = extractRealMediaInfo(url)
        if (unpacked != null) {
            return true
        }
        if (isJunkOrBeaconUrl(url)) {
            return false
        }
        return isDirectMediaCandidate(url)
    }

    fun onStreamDetected(
        url: String,
        pageUrl: String? = null,
        userAgent: String? = null,
        title: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (isDetectionEnabled?.invoke() == false) return
        val unpacked = extractRealMediaInfo(url)
        val targetUrl = unpacked?.first ?: url
        val targetPageUrl = pageUrl ?: unpacked?.second

        if (!isMediaStream(targetUrl) || isJunkOrBeaconUrl(targetUrl)) return

        val newPriority = getStreamPriority(targetUrl)
        val current = _activeStreamPayload.value
        // Never allow a headerless detection to overwrite an existing detection that has real network headers
        if (current != null && current.headers.isNotEmpty() && headers.isEmpty()) {
            return
        }
        // Do not demote an already active master manifest (or higher priority stream)
        if (current != null && current.url != targetUrl && getStreamPriority(current.url) > newPriority) {
            return
        }

        val finalHeaders = headers.filterKeys { key ->
            val lower = key.lowercase()
            lower != "accept-encoding" &&
            lower != "connection" &&
            lower != "host" &&
            lower != "content-length" &&
            !lower.startsWith("sec-")
        }.toMutableMap()
        val hasReferer = finalHeaders.keys.any { it.equals("Referer", ignoreCase = true) }
        val effectiveReferer = if (hasReferer) {
            finalHeaders.entries.first { it.key.equals("Referer", ignoreCase = true) }.value
        } else {
            targetPageUrl?.also { finalHeaders["Referer"] = it }
        }

        val hasOrigin = finalHeaders.keys.any { it.equals("Origin", ignoreCase = true) }
        if (!hasOrigin && !effectiveReferer.isNullOrBlank()) {
            val origin = runCatching {
                val u = android.net.Uri.parse(effectiveReferer)
                if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null
            }.getOrNull()
            if (origin != null) {
                finalHeaders["Origin"] = origin
            }
        }

        val hasUserAgent = finalHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }
        val effectiveUserAgent = if (hasUserAgent) {
            finalHeaders.entries.first { it.key.equals("User-Agent", ignoreCase = true) }.value
        } else if (!userAgent.isNullOrBlank()) {
            finalHeaders["User-Agent"] = userAgent
            userAgent
        } else {
            val defaultUa = "Mozilla/5.0 (Android 15; Mobile; rv:157.0) Gecko/157.0 Firefox/157.0"
            finalHeaders["User-Agent"] = defaultUa
            defaultUa
        }

        android.util.Log.i("StreamSniffer", "MediaStream detected: targetUrl=$targetUrl referer=$effectiveReferer headers=$finalHeaders")

        val payload = VideoStreamPayload(
            url = targetUrl,
            title = title,
            referer = effectiveReferer,
            userAgent = effectiveUserAgent,
            headers = finalHeaders,
        )
        _activeStreamPayload.value = payload
        YouTubeStreamPreloader.onDirectStreamDetected(payload)
    }

    fun clearActiveStream() {
        _activeStreamPayload.value = null
    }

    // Helper for WebView request interception (thread-safe, can be called from worker threads)
    fun inspectWebViewRequest(
        request: WebResourceRequest?,
        pageUrl: String?,
        userAgent: String? = null,
        currentTitle: String? = null,
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        if (isMediaStream(url)) {
            val headers = request.requestHeaders ?: emptyMap()
            onStreamDetected(
                url = url,
                pageUrl = pageUrl,
                userAgent = userAgent,
                title = currentTitle,
                headers = headers,
            )
            return true
        }
        return false
    }

    // JavaScript injection hook for WebView & GeckoView to catch blob/fetch/XHR streams
    val mediaSnifferScript: String = """
        (function() {
            if (window.__candyMediaSnifferLoaded) return;
            window.__candyMediaSnifferLoaded = true;

            function notifyNative(url) {
                if (!url || typeof url !== 'string') return;
                const lower = url.toLowerCase();
                if (lower.includes('.gif') || lower.includes('.png') || lower.includes('.jpg') || lower.includes('.ts') || lower.includes('.m4s')) return;
                if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('.mp4') || lower.includes('.webm') || lower.includes('googlevideo.com') || lower.includes('/videoplayback')) {
                    if (window.CandyMediaBridge && window.CandyMediaBridge.onStreamFound) {
                        window.CandyMediaBridge.onStreamFound(url, document.title || document.location.href);
                    }
                }
            }

            // Hook fetch
            const originalFetch = window.fetch;
            window.fetch = function(...args) {
                if (args[0]) {
                    notifyNative(typeof args[0] === 'string' ? args[0] : args[0].url);
                }
                return originalFetch.apply(this, args);
            };

            // Hook XMLHttpRequest
            const originalOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                notifyNative(url);
                return originalOpen.apply(this, arguments);
            };

            // Hook HTMLMediaElement
            document.addEventListener('play', function(e) {
                if (e.target && e.target.tagName === 'VIDEO') {
                    const src = e.target.currentSrc || e.target.src;
                    if (src && !src.startsWith('blob:')) {
                        notifyNative(src);
                    }
                }
            }, true);

            // Periodic DOM check for videos
            function scanVideos() {
                const elements = document.querySelectorAll('video');
                elements.forEach(function(v) {
                    if (v.currentSrc && !v.currentSrc.startsWith('blob:')) notifyNative(v.currentSrc);
                    else if (v.src && !v.src.startsWith('blob:')) notifyNative(v.src);
                });
            }
            setInterval(scanVideos, 2500);
        })();
    """.trimIndent()
}
