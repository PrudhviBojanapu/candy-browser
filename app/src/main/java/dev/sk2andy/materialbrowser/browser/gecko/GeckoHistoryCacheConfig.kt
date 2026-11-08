package dev.sk2andy.materialbrowser.browser.gecko

import android.util.AtomicFile
import android.util.Log
import dev.sk2andy.materialbrowser.data.BrowserMemorySettings
import dev.sk2andy.materialbrowser.data.writeSafely
import java.io.File
import org.json.JSONObject

internal object GeckoHistoryCacheConfig {
    fun write(directory: File, settings: BrowserMemorySettings): String? {
        val file = File(directory, FILE_NAME)
        val prefs = JSONObject().apply {
            put(
                "browser.sessionhistory.contentViewerTimeout",
                GeckoHistoryCacheRules.contentViewerTimeoutSeconds(settings),
            )
            put("network.http.max-connections", 128)
            put("network.http.max-persistent-connections-per-server", 16)
            // Petal-grade memory & battery optimizations
            put("browser.cache.memory.enable", true)
            put("browser.cache.memory.capacity", 32768)
            put("image.mem.surfacecache.max_size_kb", 32768)
            put("image.mem.discardable", true)
            put("javascript.options.mem.gc_compacting", true)
            put("javascript.options.mem.gc_per_zone", true)
            put("dom.ipc.processPriorityManager.backgroundGracePeriodMS", 1000)
            put("dom.ipc.processPriorityManager.backgroundPerceivableGracePeriodMS", 1000)
            put("media.suspend-bkgnd-video.enabled", true)
            put("media.autoplay.default", 0)
        }
        val configuration = JSONObject().put("prefs", prefs)
        val saved = AtomicFile(file).writeSafely { output ->
            output.write(configuration.toString().toByteArray(Charsets.UTF_8))
        }
        if (!saved) {
            Log.w(TAG, "History cache startup configuration could not be written")
            return null
        }
        return file.absolutePath
    }

    private const val FILE_NAME = "gecko-history-cache-config.json"
    private const val TAG = "GeckoHistoryCacheConfig"
}
