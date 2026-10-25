package com.example.musicplayer

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Equalizer, bass boost ও virtualizer: player-এর audio session-এ বসানো হয় */
object Fx {
    var eq: Equalizer? = null; var bass: BassBoost? = null; var virt: Virtualizer? = null
    fun init(sid: Int) {
        runCatching { eq?.release(); bass?.release(); virt?.release() }
        eq = runCatching { Equalizer(0, sid) }.getOrNull()
        bass = runCatching { BassBoost(0, sid) }.getOrNull()
        virt = runCatching { Virtualizer(0, sid) }.getOrNull()
    }
    fun apply(ctx: Context) {
        val p = ctx.getSharedPreferences("p", 0)
        val on = p.getBoolean("eq_on", true)
        runCatching {
            eq?.let { e ->
                e.enabled = on
                val lv = (p.getString("eq_lv", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
                val lo = e.bandLevelRange[0].toInt(); val hi = e.bandLevelRange[1].toInt()
                for (b in 0 until e.numberOfBands) e.setBandLevel(b.toShort(), (lv.getOrNull(b) ?: 0).coerceIn(lo, hi).toShort())
            }
            bass?.let { it.enabled = on; if (it.strengthSupported) it.setStrength(p.getInt("bass", 0).toShort()) }
            virt?.let { it.enabled = on; if (it.strengthSupported) it.setStrength(p.getInt("virt", 0).toShort()) }
        }
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        Fx.init(player.audioSessionId); Fx.apply(this)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        runCatching { Fx.eq?.release(); Fx.bass?.release(); Fx.virt?.release() }
        Fx.eq = null; Fx.bass = null; Fx.virt = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
