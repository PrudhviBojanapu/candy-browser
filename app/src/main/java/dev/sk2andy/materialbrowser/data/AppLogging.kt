package dev.sk2andy.materialbrowser.data

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.sk2andy.materialbrowser.BuildConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object AppLogging {
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
        { task -> Thread(task, "CandyAppLogs").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var store: AppLogStore? = null

    @Volatile
    private var privateBrowsingActive = false

    private val privateOwners = mutableSetOf<Any>()

    @Synchronized
    fun initialize(context: Context) {
        if (store != null) return
        val appContext = context.applicationContext
        val directory = File(appContext.noBackupFilesDir, "app_logs")
        store = AppLogStore(directory)
        setEnabled(BrowserSessionStore(appContext).loadDeveloperSettings().appLoggingEnabled)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(
                AppLogCrashHandler(previousHandler) { error ->
                    record(AppLogEvent.UncaughtException, error, sync = true)
                },
            )
        }
        record(AppLogEvent.ProcessStarted)
    }

    @Synchronized
    fun setPrivateBrowsingActive(active: Boolean, owner: Any = this) {
        if (active) privateOwners += owner else privateOwners -= owner
        val anyPrivateOwner = privateOwners.isNotEmpty()
        if (anyPrivateOwner == privateBrowsingActive) return
        if (anyPrivateOwner) store?.invalidatePendingWrites()
        privateBrowsingActive = anyPrivateOwner
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        val result = store?.setEnabled(enabled) ?: return false
        if (enabled) record(AppLogEvent.LoggingEnabled)
        return result
    }

    fun record(event: AppLogEvent, error: Throwable? = null, sync: Boolean = false) {
        val currentStore = store ?: return
        if (privateBrowsingActive) return
        val generation = currentStore.captureGeneration() ?: return
        val record = runCatching { AppLogRules.render(event, System.currentTimeMillis(), error) }
            .getOrNull() ?: return
        if (sync) {
            currentStore.append(record, generation, sync = true)
        } else {
            runCatching {
                writer.execute {
                    if (!privateBrowsingActive) currentStore.append(record, generation)
                }
            }
        }
    }

    /** Debug Logcat uses the same sanitized payload and private-owner gate as opt-in app logs. */
    @Synchronized
    fun recordDiagnostic(event: AppLogEvent, isPrivate: Boolean, error: Throwable? = null) {
        if (isPrivate || privateBrowsingActive) return
        if (BuildConfig.DEBUG) {
            runCatching {
                Log.d("CandyDownload", AppLogRules.render(event, System.currentTimeMillis(), error))
            }
        }
        record(event, error)
    }

    @Synchronized
    fun clear(): Boolean {
        return store?.clear() ?: true
    }

    /** Caller owns Dispatchers.IO and reports failures; sharing remains an explicit user action. */
    fun export(context: Context, uri: Uri, diagnostics: String): Boolean = runCatching {
        val logs = writer.submit<String> {
            store?.snapshot().orEmpty()
        }
            .get(5L, TimeUnit.SECONDS)
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write("$diagnostics\n\nCandy app logs\n$logs".toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrDefault(false)
}
