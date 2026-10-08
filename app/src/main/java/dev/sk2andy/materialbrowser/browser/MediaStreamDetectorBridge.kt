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

    private val _activeStreamPayload = MutableStateFlow<VideoStreamPayload?>(null)
    val activeStreamPayload: StateFlow<VideoStreamPayload?> = _activeStreamPayload.asStateFlow()

    fun getStreamPriority(url: String): Int {
        val cleanUrl = url.lowercase()
        return when {
            cleanUrl.contains(".m3u8") || cleanUrl.contains(".mpd") || cleanUrl.contains("/manifest(") -> 100
            cleanUrl.contains(".mp4") || cleanUrl.contains(".webm") || cleanUrl.contains(".mkv") -> 50
            cleanUrl.contains("googlevideo.com") || cleanUrl.contains("/videoplayback") -> 20
            else -> 10
        }
    }

    fun isMediaStream(url: String): Boolean {
        val cleanUrl = url.lowercase()
        if (cleanUrl.contains(".ts?") || cleanUrl.endsWith(".ts") ||
            cleanUrl.contains(".m4s?") || cleanUrl.endsWith(".m4s") ||
            cleanUrl.contains(".vtt") || cleanUrl.contains(".srt") ||
            cleanUrl.contains("doubleclick.net") || cleanUrl.contains("googleads") ||
            cleanUrl.contains("/pagead/") || cleanUrl.contains("adnxs.com")
        ) {
            return false
        }
        return cleanUrl.contains(".m3u8") ||
                cleanUrl.contains(".mpd") ||
                cleanUrl.contains(".mp4") ||
                cleanUrl.contains(".webm") ||
                cleanUrl.contains(".mkv") ||
                cleanUrl.contains("/videoplayback?") ||
                cleanUrl.contains("googlevideo.com") ||
                cleanUrl.contains("/manifest") ||
                cleanUrl.contains("/master") ||
                cleanUrl.contains("/playlist") ||
                cleanUrl.contains("/hls/") ||
                cleanUrl.contains("format=m3u8") ||
                cleanUrl.contains("type=m3u8")
    }

    fun onStreamDetected(
        url: String,
        pageUrl: String? = null,
        userAgent: String? = null,
        title: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (!isMediaStream(url)) return

        val newPriority = getStreamPriority(url)
        val current = _activeStreamPayload.value
        if (current != null && current.referer == pageUrl && getStreamPriority(current.url) > newPriority) {
            return
        }

        val finalHeaders = headers.toMutableMap()
        val hasReferer = finalHeaders.keys.any { it.equals("Referer", ignoreCase = true) }
        val effectiveReferer = if (hasReferer) {
            finalHeaders.entries.first { it.key.equals("Referer", ignoreCase = true) }.value
        } else {
            pageUrl?.also { finalHeaders["Referer"] = it }
        }

        _activeStreamPayload.value = VideoStreamPayload(
            url = url,
            title = title,
            referer = effectiveReferer,
            userAgent = userAgent,
            headers = finalHeaders,
        )
    }

    fun clearActiveStream() {
        _activeStreamPayload.value = null
    }

    // Helper for WebView request interception
    fun inspectWebViewRequest(view: WebView?, request: WebResourceRequest?, currentTitle: String?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (isMediaStream(url)) {
            val pageUrl = view?.url
            val userAgent = view?.settings?.userAgentString
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
