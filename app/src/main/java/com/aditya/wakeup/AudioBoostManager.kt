package com.aditya.wakeup

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlin.math.log10
import kotlin.math.roundToInt

object AudioBoostManager {
    private const val TAG = "WakeUpAudioBoost"

    /**
     * Converts a boost percentage (100%..200%) into millibels (mB) of digital target gain.
     * 100% = 0 mB (0 dB, baseline unamplified)
     * 110% = 20 * log10(1.10) * 100 mB ≈ 83 mB (+0.83 dB)
     * 120% = 20 * log10(1.20) * 100 mB ≈ 158 mB (+1.58 dB)
     * 150% = 20 * log10(1.50) * 100 mB ≈ 352 mB (+3.52 dB)
     * 200% = 20 * log10(2.00) * 100 mB ≈ 602 mB (+6.02 dB)
     */
    fun percentageToGainMB(percentage: Int): Int {
        val clamped = percentage.coerceIn(100, 200)
        if (clamped <= 100) return 0
        val linearMultiplier = clamped / 100.0
        val gainDB = 20.0 * log10(linearMultiplier)
        return (gainDB * 100.0).roundToInt()
    }

    /**
     * Formats gain in decibels for user display (e.g. "+3.5 dB" or "0 dB").
     */
    fun formatGainDB(percentage: Int): String {
        val gainMB = percentageToGainMB(percentage)
        val dB = gainMB / 100.0
        return if (gainMB > 0) String.format(java.util.Locale.US, "+%.1f dB", dB) else "0 dB"
    }

    /**
     * Attaches Android's native LoudnessEnhancer audio effect to the MediaPlayer session.
     * Android's LoudnessEnhancer applies clean digital amplification with an integrated
     * soft-knee limiter to prevent harsh integer clipping and distortion.
     */
    fun attachBoost(audioSessionId: Int, percentage: Int): LoudnessEnhancer? {
        if (percentage <= 100 || audioSessionId <= 0) return null
        return try {
            val gainMB = percentageToGainMB(percentage)
            val enhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gainMB)
                enabled = true
            }
            Log.i(TAG, "Attached LoudnessEnhancer on session $audioSessionId: +$gainMB mB (${formatGainDB(percentage)})")
            enhancer
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer unavailable or failed on session $audioSessionId: ${e.message}")
            null
        }
    }
}
