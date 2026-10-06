package dev.sk2andy.materialbrowser.browser

import android.webkit.WebResourceRequest
import android.webkit.WebView
import dev.sk2andy.materialbrowser.ui.VideoStreamPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MediaStreamDetectorBridge {

    private val _activeStreamPayload = MutableStateFlow<VideoStreamPayload?>(null)
    val activeStreamPayload: StateFlow<VideoStreamPayload?> = _activeStreamPayload.asStateFlow()

    fun isMediaStream(url: String): Boolean {
        val cleanUrl = url.lowercase()
        return cleanUrl.contains(".m3u8") ||
                cleanUrl.contains(".mpd") ||
                cleanUrl.contains(".mp4") ||
                cleanUrl.contains("/manifest(") ||
                cleanUrl.contains("/master.m3u8") ||
                cleanUrl.contains("/playlist.m3u8")
    }

    fun onStreamDetected(
        url: String,
        pageUrl: String? = null,
        userAgent: String? = null,
        title: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (!isMediaStream(url)) return

        val finalHeaders = headers.toMutableMap()
        if (pageUrl != null && !finalHeaders.containsKey("Referer")) {
            finalHeaders["Referer"] = pageUrl
        }

        _activeStreamPayload.value = VideoStreamPayload(
            url = url,
            title = title,
            referer = pageUrl,
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
                if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('.mp4')) {
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
        })();
    """.trimIndent()
}
