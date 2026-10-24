package dev.sk2andy.materialbrowser.browser.cast

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class CastSessionController(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") onMediaLoaded: (CastMediaCandidate) -> Unit,
) {
    var state by mutableStateOf(CastUiState())
        private set

    fun updateCandidate(@Suppress("UNUSED_PARAMETER") candidate: CastMediaCandidate?) = Unit

    fun togglePlayback() = Unit

    fun disconnect() = Unit

    fun seekTo(@Suppress("UNUSED_PARAMETER") positionMillis: Long) = Unit

    fun setDeviceVolume(@Suppress("UNUSED_PARAMETER") volume: Float) = Unit

    fun release() = Unit
}
