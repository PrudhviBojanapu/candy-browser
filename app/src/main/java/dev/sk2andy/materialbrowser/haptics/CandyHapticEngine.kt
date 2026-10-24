package dev.sk2andy.materialbrowser.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Candy Haptic Engine
 * Ported from Petal / Ever-Haptics for high-precision tactile feedback on Android.
 * Leverages hardware vibration primitives (PRIMITIVE_CLICK, PRIMITIVE_TICK, PRIMITIVE_LOW_TICK)
 * with multi-tiered fallbacks and 16-level intensity bucketing.
 */
class CandyHapticEngine private constructor(context: Context) {

    enum class Pattern {
        CLICK,
        TICK,
        HEAVY_CLICK,
        DOUBLE_CLICK,
        SOFT_BUMP,
        DOUBLE_TICK
    }

    private val vibrator: Vibrator?
    val hasVibrator: Boolean
    val hasAmplitudeControl: Boolean
    private val touchAttrs: VibrationAttributes?

    private val effectCache = ConcurrentHashMap<Int, VibrationEffect>()
    private val lastPlayMs = ConcurrentHashMap<Pattern, AtomicLong>()

    init {
        val appContext = context.applicationContext
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator ?: (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        hasVibrator = vibrator != null && vibrator.hasVibrator()
        hasAmplitudeControl = hasVibrator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (vibrator?.hasAmplitudeControl() == true)

        touchAttrs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
        } else {
            null
        }
    }

    companion object {
        private const val INTENSITY_BUCKETS = 16
        private const val MIN_AUDIBLE_INTENSITY = 0.02f
        private const val PREFS_NAME = "candy_haptics"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"

        @Volatile
        private var instance: CandyHapticEngine? = null

        fun getInstance(context: Context): CandyHapticEngine {
            return instance ?: synchronized(this) {
                instance ?: CandyHapticEngine(context).also { instance = it }
            }
        }

        fun isHapticsEnabled(context: Context?): Boolean {
            if (context == null) return true
            return try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.getBoolean(KEY_HAPTICS_ENABLED, true)
            } catch (_: Throwable) {
                true
            }
        }

        fun setHapticsEnabled(context: Context, enabled: Boolean) {
            try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
            } catch (_: Throwable) {}
        }
    }

    fun play(pattern: Pattern, intensity: Float, throttleMs: Long = 0L): Boolean {
        if (!hasVibrator || vibrator == null) return false
        if (intensity <= MIN_AUDIBLE_INTENSITY) return false

        if (throttleMs > 0L) {
            val now = System.currentTimeMillis()
            val lastMs = lastPlayMs.computeIfAbsent(pattern) { AtomicLong(0L) }
            val prev = lastMs.get()
            if (now - prev < throttleMs) return false
            lastMs.compareAndSet(prev, now)
        }

        val clamped = intensity.coerceIn(0.1f, 1f)
        val effect = effectFor(pattern, clamped)

        if (pattern == Pattern.DOUBLE_CLICK || pattern == Pattern.HEAVY_CLICK) {
            try {
                vibrator.cancel()
            } catch (_: Throwable) {}
        }

        if (effect != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && touchAttrs != null) {
                    vibrator.vibrate(effect, touchAttrs)
                    return true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(effect)
                    return true
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25)
                    return true
                }
            } catch (_: Throwable) {}
        }

        // Multi-tiered fallback for devices lacking hardware primitive composition
        try {
            val durationMs = when (pattern) {
                Pattern.TICK -> 5L
                Pattern.CLICK -> 10L
                Pattern.SOFT_BUMP -> 8L
                Pattern.DOUBLE_CLICK, Pattern.HEAVY_CLICK -> 20L
                Pattern.DOUBLE_TICK -> 6L
            }
            val amplitude = when (pattern) {
                Pattern.TICK -> (clamped * 100f).roundToInt().coerceIn(30, 110)
                Pattern.SOFT_BUMP -> (clamped * 110f).roundToInt().coerceIn(40, 120)
                else -> (clamped * 180f).roundToInt().coerceIn(60, 200)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val fallbackEffect = VibrationEffect.createOneShot(durationMs, amplitude)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && touchAttrs != null) {
                    vibrator.vibrate(fallbackEffect, touchAttrs)
                } else {
                    vibrator.vibrate(fallbackEffect)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
            return true
        } catch (_: Throwable) {}

        return false
    }

    fun playIfEnabled(context: Context, pattern: Pattern, intensity: Float, throttleMs: Long = 0L): Boolean {
        if (!isHapticsEnabled(context)) return false
        return play(pattern, intensity, throttleMs)
    }

    fun playOneShot(durationMs: Long, amplitude: Int) {
        if (!hasVibrator || vibrator == null) return
        try {
            vibrator.cancel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = amplitude.coerceIn(1, 255)
                val effect = VibrationEffect.createOneShot(durationMs, amp)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Throwable) {}
    }

    fun playClick(context: Context) {
        playIfEnabled(context, Pattern.CLICK, 0.85f, 0L)
    }

    fun playHeavyClick(context: Context) {
        playIfEnabled(context, Pattern.HEAVY_CLICK, 1.0f, 0L)
    }

    fun playTick(context: Context, intensity: Float = 0.65f) {
        playIfEnabled(context, Pattern.TICK, intensity, 0L)
    }

    private fun effectFor(pattern: Pattern, intensity: Float): VibrationEffect? {
        val bucket = ((intensity * (INTENSITY_BUCKETS - 1)) + 0.5f).roundToInt().coerceIn(0, INTENSITY_BUCKETS - 1)
        val key = pattern.ordinal * INTENSITY_BUCKETS + bucket

        val cached = effectCache[key]
        if (cached != null) return cached

        val bucketIntensity = bucket.toFloat() / (INTENSITY_BUCKETS - 1)
        val built = buildEffect(pattern, bucketIntensity)
        if (built != null) {
            effectCache.putIfAbsent(key, built)
        }
        return built
    }

    private fun buildEffect(pattern: Pattern, intensity: Float): VibrationEffect? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
                val primitive = getPrimitiveForPattern(pattern)
                if (vibrator.areAllPrimitivesSupported(primitive)) {
                    val composition = VibrationEffect.startComposition()
                    when (pattern) {
                        Pattern.CLICK -> {
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                        }
                        Pattern.TICK -> {
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, intensity)
                        }
                        Pattern.HEAVY_CLICK -> {
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                            composition.addPrimitive(
                                VibrationEffect.Composition.PRIMITIVE_CLICK,
                                (intensity * 0.6f).coerceIn(0f, 1f),
                                40
                            )
                        }
                        Pattern.DOUBLE_CLICK -> {
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity, 80)
                        }
                        Pattern.SOFT_BUMP -> {
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, intensity)
                        }
                        Pattern.DOUBLE_TICK -> {
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, intensity)
                            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, intensity, 60)
                        }
                    }
                    return composition.compose()
                }
            }
        } catch (_: Throwable) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effectId = when (pattern) {
                    Pattern.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
                    Pattern.DOUBLE_CLICK, Pattern.DOUBLE_TICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    Pattern.TICK, Pattern.SOFT_BUMP -> VibrationEffect.EFFECT_TICK
                    Pattern.CLICK -> VibrationEffect.EFFECT_CLICK
                }
                return VibrationEffect.createPredefined(effectId)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = (intensity * 255f).roundToInt().coerceIn(1, 255)
                return when (pattern) {
                    Pattern.HEAVY_CLICK -> VibrationEffect.createOneShot(35, min(255, amp + 40))
                    Pattern.TICK -> VibrationEffect.createOneShot(10, max(1, (amp * 0.7f).roundToInt()))
                    Pattern.SOFT_BUMP -> VibrationEffect.createOneShot(25, max(1, (amp * 0.5f).roundToInt()))
                    Pattern.DOUBLE_CLICK -> VibrationEffect.createWaveform(
                        longArrayOf(0, 20, 40, 20),
                        intArrayOf(0, amp, 0, amp),
                        -1
                    )
                    Pattern.DOUBLE_TICK -> {
                        val tickAmp = max(1, (amp * 0.6f).roundToInt())
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 10, 30, 10),
                            intArrayOf(0, tickAmp, 0, tickAmp),
                            -1
                        )
                    }
                    Pattern.CLICK -> VibrationEffect.createOneShot(16, amp)
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun getPrimitiveForPattern(pattern: Pattern): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (pattern) {
                Pattern.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
                Pattern.SOFT_BUMP -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                Pattern.CLICK,
                Pattern.HEAVY_CLICK,
                Pattern.DOUBLE_CLICK,
                Pattern.DOUBLE_TICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
            }
        }
        return 0
    }
}
