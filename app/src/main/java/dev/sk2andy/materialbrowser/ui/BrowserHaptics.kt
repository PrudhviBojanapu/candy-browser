@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
)

package dev.sk2andy.materialbrowser.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.sk2andy.materialbrowser.haptics.CandyHapticEngine
import dev.sk2andy.materialbrowser.R
import kotlin.math.roundToInt

internal fun View.performConfirmHaptic() {
    val engine = CandyHapticEngine.getInstance(context)
    if (!engine.playIfEnabled(context, CandyHapticEngine.Pattern.CLICK, 0.85f)) {
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            },
        )
    }
}

internal fun View.performRejectHaptic() {
    val engine = CandyHapticEngine.getInstance(context)
    if (!engine.playIfEnabled(context, CandyHapticEngine.Pattern.HEAVY_CLICK, 1.0f)) {
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            },
        )
    }
}

internal fun View.performTabFocusHaptic() {
    val hapticFlag = HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (!performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK, hapticFlag)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, hapticFlag)
        }
    } else {
        if (!performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, hapticFlag)) {
            val engine = CandyHapticEngine.getInstance(context)
            engine.playIfEnabled(context, CandyHapticEngine.Pattern.TICK, 0.45f)
        }
    }
}

internal fun View.startRubberbandHaptic() {
    performHapticFeedback(
        HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
    )
}

internal fun View.stopRubberbandHaptic() {
    // No continuous vibration to stop
}

internal fun View.performScaledTickHaptic(strength: Float) {
    val boundedStrength = strength.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val engine = CandyHapticEngine.getInstance(context)
    if (engine.playIfEnabled(context, CandyHapticEngine.Pattern.TICK, boundedStrength, throttleMs = 12L)) {
        return
    }
    val vibrator = rubberbandVibrator()
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        vibrator?.hasVibrator() == true &&
        vibrator.hasAmplitudeControl()
    ) {
        val amplitude = (
            MIN_SCALED_TICK_AMPLITUDE +
                (MAX_SCALED_TICK_AMPLITUDE - MIN_SCALED_TICK_AMPLITUDE) * boundedStrength
            ).roundToInt()
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                SCALED_TICK_DURATION_MILLIS,
                amplitude,
            ),
        )
        return
    }
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

private fun View.rubberbandVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

private const val SCALED_TICK_DURATION_MILLIS = 9L
private const val MIN_SCALED_TICK_AMPLITUDE = 28f
private const val MAX_SCALED_TICK_AMPLITUDE = 180f
