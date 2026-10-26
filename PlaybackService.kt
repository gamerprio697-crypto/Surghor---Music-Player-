package com.example.musicplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.nio.ByteBuffer
import kotlin.math.*

/** Equalizer-এর সেটিংস। App আর player একই process-এ থাকে, তাই বদলালেই সাথে সাথে শোনা যায়। */
object Dsp {
    val FREQS = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    @Volatile var version = 0
    @Volatile var on = true
    @Volatile var bass = 0f
    @Volatile var wide = 0f
    val gains = FloatArray(10)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("p", 0)
        on = p.getBoolean("dsp_on", true); bass = p.getFloat("dsp_bass", 0f); wide = p.getFloat("dsp_wide", 0f)
        val l = (p.getString("dsp_g", "") ?: "").split(",").mapNotNull { it.toFloatOrNull() }
        for (i in 0 until 10) gains[i] = l.getOrNull(i) ?: 0f
        version++
    }

    fun set(ctx: Context, on: Boolean, g: List<Float>, bass: Float, wide: Float, preset: String) {
        this.on = on; this.bass = bass; this.wide = wide
        for (i in 0 until 10) gains[i] = g[i]
        version++
        ctx.getSharedPreferences("p", 0).edit().putBoolean("dsp_on", on).putString("dsp_g", g.joinToString(","))
            .putFloat("dsp_bass", bass).putFloat("dsp_wide", wide).putString("dsp_preset", preset).apply()
    }
}

/** 10-band parametric EQ + bass shelf + stereo widening। সব হিসাব 64-bit-এ, শেষে dither ও soft limiter। */
class EqProcessor : BaseAudioProcessor() {
    private var rate = 44100; private var ch = 2; private var isFloat = false
    private var seen = -1; private var act = false
    private val used = BooleanArray(11)
    private val co = Array(11) { DoubleArray(5) }
    private var z = DoubleArray(0)
    private var pre = 1.0; private var width = 1.0
    private var seed = 12345

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if ((f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) || f.channelCount !in 1..2)
            return AudioProcessor.AudioFormat.NOT_SET
        rate = f.sampleRate; ch = f.channelCount; isFloat = f.encoding == C.ENCODING_PCM_FLOAT
        z = DoubleArray(ch * 11 * 2); seen = -1
        return f
    }

    private fun peak(i: Int, f: Double, g: Double, q: Double) {
        val a = 10.0.pow(g / 40); val w = 2 * PI * f / rate; val al = sin(w) / (2 * q); val c = cos(w)
        val a0 = 1 + al / a
        co[i][0] = (1 + al * a) / a0; co[i][1] = -2 * c / a0; co[i][2] = (1 - al * a) / a0
        co[i][3] = -2 * c / a0; co[i][4] = (1 - al / a) / a0
    }

    private fun shelf(i: Int, f: Double, g: Double) {
        val a = 10.0.pow(g / 40); val w = 2 * PI * f / rate; val c = cos(w)
        val sa = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
        val a0 = (a + 1) + (a - 1) * c + sa
        co[i][0] = a * ((a + 1) - (a - 1) * c + sa) / a0
        co[i][1] = 2 * a * ((a - 1) - (a + 1) * c) / a0
        co[i][2] = a * ((a + 1) - (a - 1) * c - sa) / a0
        co[i][3] = -2 * ((a - 1) + (a + 1) * c) / a0
        co[i][4] = ((a + 1) + (a - 1) * c - sa) / a0
    }

    private fun refresh() {
        seen = Dsp.version
        var any = false; var maxB = 0.0
        for (b in 0 until 10) {
            val g = Dsp.gains[b].toDouble()
            val u = abs(g) > 0.05 && Dsp.FREQS[b] < rate * 0.45
            used[b] = u
            if (u) { peak(b, Dsp.FREQS[b], g, 1.41); any = true; maxB = max(maxB, g) }
        }
        val bs = Dsp.bass.toDouble()
        used[10] = bs > 0.05
        if (used[10]) { shelf(10, 90.0, bs); any = true; maxB = max(maxB, bs) }
        pre = 10.0.pow(-maxB * 0.9 / 20)
        width = 1.0 + Dsp.wide * 0.9
        act = Dsp.on && (any || Dsp.wide > 0.01)
    }

    private fun flt(c: Int, x: Double): Double {
        var y = x
        for (b in 0 until 11) if (used[b]) {
            val k = (c * 11 + b) * 2; val cf = co[b]
            val o = cf[0] * y + z[k]
            z[k] = cf[1] * y - cf[3] * o + z[k + 1]
            z[k + 1] = cf[2] * y - cf[4] * o
            y = o
        }
        return y
    }

    private fun rnd(): Double { seed = seed * 1664525 + 1013904223; return (seed ushr 8) / 16777216.0 }
    private fun rd(b: ByteBuffer): Double = if (isFloat) b.float.toDouble() else b.short / 32768.0

    private fun wr(o: ByteBuffer, x: Double) {
        val y = if (abs(x) > 0.92) sign(x) * (0.92 + 0.08 * tanh((abs(x) - 0.92) / 0.08)) else x
        if (isFloat) o.putFloat(y.toFloat())
        else {
            val d = (rnd() - rnd()) / 32768.0
            o.putShort(((y + d) * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
        }
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        if (seen != Dsp.version) refresh()
        val out = replaceOutputBuffer(n)
        if (!act) { out.put(input); out.flip(); return }
        val frames = n / ((if (isFloat) 4 else 2) * ch)
        for (i in 0 until frames) {
            var l = rd(input); var r = if (ch == 2) rd(input) else l
            if (ch == 2 && width != 1.0) { val m = (l + r) * 0.5; val s = (l - r) * 0.5 * width; l = m + s; r = m - s }
            l = flt(0, l) * pre
            if (ch == 2) r = flt(1, r) * pre
            wr(out, l); if (ch == 2) wr(out, r)
        }
        input.position(input.limit())
        out.flip()
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Dsp.load(this)
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(EqProcessor()))
                    .build()
        }.setEnableAudioFloatOutput(true)
        val player = ExoPlayer.Builder(this, rf)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
