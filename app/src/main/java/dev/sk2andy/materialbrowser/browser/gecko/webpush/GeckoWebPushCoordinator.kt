package dev.sk2andy.materialbrowser.browser.gecko.webpush

import android.content.Context
import androidx.annotation.UiThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.sk2andy.materialbrowser.BuildConfig
import dev.sk2andy.materialbrowser.browser.AndroidBrowserEngineKind
import dev.sk2andy.materialbrowser.browser.gecko.GeckoRuntimeOwner
import dev.sk2andy.materialbrowser.data.BrowserSessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebPushController
import org.mozilla.geckoview.WebPushDelegate
import org.mozilla.geckoview.WebPushSubscription

/** Owns Gecko's process-wide Web Push delegate and FOSS delivery transport. */
internal object GeckoWebPushCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val workerStartingRuntime = AtomicBoolean(false)
    private var foregroundJob: Job? = null

    @Volatile
    private var transport: FossWebPushTransport? = null

    @Volatile
    private var controller: WebPushController? = null

    @UiThread
    fun attach(context: Context, runtime: GeckoRuntime) {
        // Disabled per user request (debloated)
    }

    suspend fun reconnectOnce(context: Context): Boolean = true

    fun onBrowserEngineChanged(context: Context, kind: AndroidBrowserEngineKind) {
        FossWebPushTransport.schedulePeriodicReconnect(context.applicationContext, enabled = false)
    }

    suspend fun clearAllData(context: Context): Boolean = true

    suspend fun removeProfileSubscriptions(context: Context, profileId: String): Boolean = true

    private fun delegate(context: Context): WebPushDelegate = object : WebPushDelegate {
        override fun onSubscribe(
            scope: String,
            appServerKey: ByteArray?,
        ): GeckoResult<WebPushSubscription> {
            val result = GeckoResult<WebPushSubscription>()
            this@GeckoWebPushCoordinator.scope.launch {
                val subscription = runCatching {
                    transport(context).subscribe(
                        scope = scope,
                        appServerKey = appServerKey,
                        keys = WebPushCrypto.generateKeyMaterial(),
                        isPrivate = scope.contains("privateBrowsingId=1"),
                    )
                }.getOrNull()
                if (subscription == null) {
                    result.completeExceptionally(IllegalStateException("Web Push subscription unavailable"))
                } else {
                    FossWebPushTransport.schedulePeriodicReconnect(context, enabled = true)
                    result.complete(subscription.toGeckoSubscription())
                }
            }
            return result
        }

        override fun onGetSubscription(scope: String): GeckoResult<WebPushSubscription> {
            val result = GeckoResult<WebPushSubscription>()
            this@GeckoWebPushCoordinator.scope.launch {
                val subscription = runCatching {
                    transport(context).get(scope)
                }.getOrNull()
                result.complete(subscription?.toGeckoSubscription())
            }
            return result
        }

        override fun onUnsubscribe(scope: String): GeckoResult<Void> {
            val result = GeckoResult<Void>()
            this@GeckoWebPushCoordinator.scope.launch {
                if (runCatching { transport(context).unsubscribe(scope) }.getOrDefault(false)) {
                    FossWebPushTransport.schedulePeriodicReconnect(
                        context,
                        enabled = transport(context).hasPendingWork(),
                    )
                    result.complete(null)
                } else {
                    result.completeExceptionally(IllegalStateException("Web Push unsubscribe failed"))
                }
            }
            return result
        }
    }

    private fun transport(context: Context): FossWebPushTransport =
        transport ?: synchronized(lock) {
            transport ?: FossWebPushTransport(
                context = context.applicationContext,
                onMessage = ::onMessage,
                onSubscriptionsInvalidated = ::onSubscriptionsInvalidated,
            ).also { created -> transport = created }
        }

    private suspend fun onMessage(message: FossPushMessage): Boolean {
        val plaintext = message.encryptedPayload?.let { payload ->
            // A malformed or undecryptable payload will not improve on retry.
            WebPushCrypto.decryptAes128Gcm(payload, message.subscription.keys) ?: return true
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                val pushController = controller ?: return@runCatching false
                if (plaintext == null) pushController.onPushEvent(message.subscription.scope)
                else pushController.onPushEvent(message.subscription.scope, plaintext)
                true
            }.getOrDefault(false)
        }
    }

    private fun onSubscriptionsInvalidated(scopes: List<String>) {
        scope.launch(Dispatchers.Main) {
            controller?.let { pushController ->
                scopes.forEach(pushController::onSubscriptionChanged)
            }
        }
    }

    private fun FossPushSubscription.toGeckoSubscription() = WebPushSubscription(
        scope,
        endpoint,
        appServerKey,
        keys.publicKey,
        keys.authSecret,
    )
}
