package com.example.musicplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class Song(val id: Long, val title: String, val artist: String, val album: String,
                val durationMs: Long, val uri: Uri, val folder: String)

fun scanSongs(ctx: Context, minMs: Long = 30000): List<Song> {
    val out = mutableListOf<Song>()
    val cols = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
    if (Build.VERSION.SDK_INT >= 29) cols += MediaStore.Audio.Media.RELATIVE_PATH
    val c = ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > $minMs",
        null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC") ?: return out
    c.use {
        while (it.moveToNext()) {
            val id = it.getLong(0)
            val folder = if (it.columnCount > 5) (it.getString(5) ?: "").trimEnd('/').substringAfterLast('/').ifBlank { "Root" } else "Music"
            out += Song(id, it.getString(1) ?: "Unknown", it.getString(2)?.takeIf { a -> a != "<unknown>" } ?: "Unknown Artist",
                it.getString(3) ?: "Unknown Album", it.getLong(4),
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), folder)
        }
    }
    return out
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
