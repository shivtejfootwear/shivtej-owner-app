package com.shivtej.owner.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import com.shivtej.owner.App
import kotlin.math.PI
import kotlin.math.sin

/**
 * Scanner sounds, all made here (no system tone, so they play on every phone):
 *  ok    = a valid barcode: one high beep + a short vibration
 *  again = the same piece scanned again: two mid beeps + a double vibration
 *  fail  = not valid / not found / already used elsewhere: one low long buzz + a long vibration
 * The sound is on/off from the "आवाज़" switch in the stock check (prefs.stockCheckSound).
 */
object Beeps {
    private const val RATE = 22050
    private const val OK = 0
    private const val AGAIN = 1
    private const val FAIL = 2

    private fun wave(freq: Int, ms: Int): ShortArray {
        val n = RATE * ms / 1000
        val fade = RATE / 200
        val out = ShortArray(n)
        for (i in 0 until n) {
            var v = sin(2 * PI * freq * i / RATE) * 0.6
            if (i < fade) v *= i.toDouble() / fade
            if (i > n - fade) v *= (n - i).toDouble() / fade
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
    private fun silence(ms: Int) = ShortArray(RATE * ms / 1000)
    private fun concat(vararg parts: ShortArray): ShortArray {
        val r = ShortArray(parts.sumOf { it.size })
        var p = 0
        parts.forEach { System.arraycopy(it, 0, r, p, it.size); p += it.size }
        return r
    }

    private val sounds: Map<Int, ShortArray> by lazy {
        mapOf(
            OK to wave(1800, 120),
            AGAIN to concat(wave(700, 110), silence(90), wave(700, 110)),
            FAIL to wave(220, 380)
        )
    }
    private val tracks = HashMap<Int, AudioTrack>()

    private fun track(kind: Int): AudioTrack {
        tracks[kind]?.let { return it }
        val buf = sounds.getValue(kind)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(buf, 0, buf.size)
        tracks[kind] = t
        return t
    }

    private fun play(ctx: Context, kind: Int) {
        val app = ctx.applicationContext as? App
        val soundOn = app?.prefs?.stockCheckSound ?: true
        if (soundOn) runCatching {
            val t = track(kind)
            t.stop()
            t.setPlaybackHeadPosition(0)
            t.play()
        }
        val v = ctx.applicationContext.getSystemService(Vibrator::class.java) ?: return
        runCatching {
            when (kind) {
                OK -> v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                AGAIN -> v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 80), -1))
                else -> v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    fun ok(ctx: Context) = play(ctx, OK)
    fun again(ctx: Context) = play(ctx, AGAIN)
    fun fail(ctx: Context) = play(ctx, FAIL)
}
