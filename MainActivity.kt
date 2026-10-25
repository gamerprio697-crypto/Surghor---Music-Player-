package com.example.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

var dark by mutableStateOf(true)
var artVer by mutableStateOf(0)
val Blue = Color(0xFF1F6BFF); val Accent = Color(0xFF4C9AFF)
val Bg get() = if (dark) Color(0xFF0F131A) else Color(0xFFF3F5F9)
val Card get() = if (dark) Color(0xFF131B2E) else Color.White
val Line get() = if (dark) Color(0xFF22304F) else Color(0xFFD5DBE8)
val Dim get() = if (dark) Color(0xFF8A94A8) else Color(0xFF5F6B80)
val Tx get() = if (dark) Color.White else Color(0xFF0F172A)
val NavBg get() = if (dark) Color(0xFF0A0F1E) else Color.White

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = getSharedPreferences("p", 0).getBoolean("dark", true)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Bg, surface = Bg) else lightColorScheme(background = Bg, surface = Bg)) { App() }
        }
    }
}

fun artFile(ctx: Context, id: Long) = File(ctx.filesDir, "art_$id.jpg")

fun saveArt(ctx: Context, id: Long, uri: Uri) {
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return
            val m = maxOf(b.width, b.height)
            val o = if (m > 800) Bitmap.createScaledBitmap(b, b.width * 800 / m, b.height * 800 / m, true) else b
            FileOutputStream(artFile(ctx, id)).use { o.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        artVer++
    }
}

@Composable
fun Art(song: Song?, size: Dp = Dp.Unspecified, radius: Dp = 14.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, song?.id, artVer) {
        value = if (song == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val f = artFile(ctx, song.id)
                if (f.exists()) BitmapFactory.decodeFile(f.path)
                else if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(song.uri, Size(400, 400), null) else null
            }.getOrNull()
        }
    }
    Box(modifier.then(if (size != Dp.Unspecified) Modifier.size(size) else Modifier).clip(RoundedCornerShape(radius)).background(Card)
        .border(1.dp, Line, RoundedCornerShape(radius)), Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Filled.MusicNote, null, tint = Accent, modifier = Modifier.size(if (size != Dp.Unspecified) size / 2 else 80.dp))
    }
}

class Ops(val favs: Set<Long>, val cur: Long?, val plNames: List<String>, val onFav: (Long) -> Unit,
          val onAdd: (String, Long) -> Unit, val onInfo: (Song) -> Unit, val onPlay: (List<Song>, Int) -> Unit)

class SItem(val label: String, val title: String, val sub: String, val icon: ImageVector, val tint: Color,
            val toggle: Boolean? = null, val onClick: () -> Unit)

fun loadPl(p: android.content.SharedPreferences): Map<String, Set<Long>> =
    (p.getStringSet("pls", emptySet()) ?: emptySet()).sorted().associateWith { n ->
        (p.getStringSet("pl:$n", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet() }

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    var songs by remember { mutableStateOf(listOf<Song>()) }
    var favs by remember { mutableStateOf((prefs.getStringSet("favs", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()) }
    var pls by remember { mutableStateOf(loadPl(prefs)) }
    var recent by remember { mutableStateOf(listOf<Song>()) }
    var tab by remember { mutableStateOf(0) }
    var showEq by remember { mutableStateOf(false) }
    var openPl by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Song?>(null) }
    var lyrics by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var curId by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var sleepMin by remember { mutableStateOf(0) }
    var sleepEnd by remember { mutableStateOf(0L) }
    var hideShort by remember { mutableStateOf(prefs.getBoolean("hide_short", false)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[perm] == true || ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }
    fun scan() { songs = scanSongs(ctx, if (hideShort) 60000 else 30000) }
    LaunchedEffect(granted) { if (granted) withContext(Dispatchers.IO) { scan() } }

    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        f.addListener({
            val c = f.get(); controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { playing = p }
                override fun onMediaItemTransition(m: MediaItem?, r: Int) { curId = m?.mediaId?.toLongOrNull(); dur = c.duration.coerceAtLeast(0) }
                override fun onPlaybackStateChanged(s: Int) { dur = c.duration.coerceAtLeast(0) }
            })
            playing = c.isPlaying; curId = c.currentMediaItem?.mediaId?.toLongOrNull()
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    LaunchedEffect(controller) { while (true) { controller?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0) }; delay(500) } }
    LaunchedEffect(curId) { songs.find { it.id == curId }?.let { s -> recent = (listOf(s) + recent.filter { it.id != s.id }).take(10) } }
    LaunchedEffect(sleepEnd) {
        if (sleepEnd > 0) { delay((sleepEnd - System.currentTimeMillis()).coerceAtLeast(0)); controller?.pause(); sleepEnd = 0; sleepMin = 0 }
    }
    BackHandler(enabled = showEq || openPl != null) { if (showEq) showEq = false else openPl = null }

    val current = songs.find { it.id == curId }
    fun playList(list: List<Song>, i: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map { s ->
            val af = artFile(ctx, s.id)
            MediaItem.Builder().setMediaId(s.id.toString()).setUri(s.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
                    .apply { if (af.exists()) setArtworkUri(Uri.fromFile(af)) }.build()).build()
        }, i, 0)
        c.prepare(); c.play()
    }
    fun toggleFav(id: Long) {
        favs = if (id in favs) favs - id else favs + id
        prefs.edit().putStringSet("favs", favs.map { it.toString() }.toSet()).apply()
    }
    fun savePl(m: Map<String, Set<Long>>, removed: String? = null) {
        pls = m.toSortedMap()
        val e = prefs.edit().putStringSet("pls", m.keys.toSet())
        m.forEach { (n, s) -> e.putStringSet("pl:$n", s.map { it.toString() }.toSet()) }
        removed?.let { e.remove("pl:$it") }
        e.apply()
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && current != null) { saveArt(ctx, current.id, uri); Toast.makeText(ctx, "Artwork updated", Toast.LENGTH_SHORT).show() }
    }
    val ops = Ops(favs, curId, pls.keys.toList(), { toggleFav(it) },
        { n, id -> savePl(pls + (n to ((pls[n] ?: emptySet()) + id))); Toast.makeText(ctx, "Added to $n", Toast.LENGTH_SHORT).show() },
        { info = it }, { l, i -> playList(l, i) })

    if (!granted) {
        Column(Modifier.fillMaxSize().background(Bg).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.LibraryMusic, null, tint = Accent, modifier = Modifier.size(84.dp))
            Spacer(Modifier.height(20.dp))
            Text("Allow access to your audio files so Surghor can find and play your music.", color = Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                launcher.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(perm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(perm))
            }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Allow Access") }
        }
        return
    }

    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val sleeps = listOf(0, 15, 30, 60)
    val settingsItems = listOf(
        SItem("AUDIO SETTINGS", "Sound & Audio Equalizer", "5-Band EQ with Bass Boost, Treble & Vocal presets", Icons.Outlined.Tune, Accent) { showEq = true },
        SItem("THEME & APPEARANCE", "Interface & Theme", if (dark) "Dark Mode active" else "Light Mode active", Icons.Outlined.Palette, Color(0xFFB794F6), dark) {
            dark = !dark; prefs.edit().putBoolean("dark", dark).apply() },
        SItem("FILE MANAGEMENT", "Local Device Scan", "Rescan phone storage for music files automatically", Icons.Outlined.FolderOpen, Color(0xFF2DD4BF)) {
            scan(); Toast.makeText(ctx, "Scan complete", Toast.LENGTH_SHORT).show() },
        SItem("BACKGROUND & LOCK SCREEN", "Notification Bar & Lock Screen", "Audio continues playing when app is closed or screen is turned off", Icons.Outlined.Notifications, Color(0xFFFBBF24)) {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        SItem("PLAYBACK", "Playback Speed", "Tap to change: ${speed}x", Icons.Outlined.Speed, Color(0xFF60A5FA)) {
            speed = speeds[(speeds.indexOf(speed) + 1) % speeds.size]; controller?.setPlaybackSpeed(speed) },
        SItem("PLAYBACK", "Sleep Timer", if (sleepMin == 0) "Off. Tap to set 15 / 30 / 60 min" else "Stops music in $sleepMin min", Icons.Outlined.Timer, Color(0xFFF472B6)) {
            sleepMin = sleeps[(sleeps.indexOf(sleepMin) + 1) % sleeps.size]
            sleepEnd = if (sleepMin == 0) 0L else System.currentTimeMillis() + sleepMin * 60000L },
        SItem("LIBRARY MANAGEMENT", "Hide Short Tracks", "Skip audio shorter than 60 seconds (rescan to apply)", Icons.Outlined.ContentCut, Color(0xFF34D399), hideShort) {
            hideShort = !hideShort; prefs.edit().putBoolean("hide_short", hideShort).apply(); scan() },
        SItem("ARTWORK", "Reset Custom Artwork", "Remove all gallery images you set for songs", Icons.Outlined.Image, Color(0xFF93C5FD)) {
            ctx.filesDir.listFiles { f -> f.name.startsWith("art_") }?.forEach { it.delete() }; artVer++
            Toast.makeText(ctx, "Artwork reset", Toast.LENGTH_SHORT).show() },
        SItem("LIBRARY MANAGEMENT", "Clear Favourites", "Remove all songs from your favourites", Icons.Outlined.Delete, Color(0xFFFF5470)) {
            favs = emptySet(); prefs.edit().putStringSet("favs", emptySet()).apply() },
        SItem("ABOUT", "Surghor - Music Player", "Version 1.0", Icons.Outlined.Info, Dim) {}
    )

    Scaffold(containerColor = Bg, bottomBar = {
        Column {
            if (current != null && tab != 1) MiniPlayer(current, playing, current.id in favs, { toggle() },
                { controller?.seekToNextMediaItem() }, { toggleFav(current.id) }, { tab = 1; showEq = false })
            NavigationBar(containerColor = NavBg) {
                listOf("HOME" to Icons.Outlined.Home, "PLAYER" to Icons.Outlined.Album, "LIBRARY" to Icons.Outlined.LibraryMusic,
                    "PLAYLISTS" to Icons.Outlined.QueueMusic, "SETTINGS" to Icons.Outlined.Settings).forEachIndexed { i, (l, ic) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; showEq = false; openPl = null }, icon = { Icon(ic, null) },
                        label = { Text(l, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = Dim, unselectedTextColor = Dim, indicatorColor = Color.Transparent))
                }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (showEq) EqScreen { showEq = false } else when (tab) {
                0 -> Home(songs.size, favs.size, pls.size + 1, recent, current, playing, { toggle() }, { tab = 1 }, { scan() },
                    { tab = 2 }, { showEq = true }, { dark = !dark; prefs.edit().putBoolean("dark", dark).apply() }) { playList(listOf(it), 0) }
                1 -> PlayerScreen(current, playing, pos, dur, current?.let { it.id in favs } == true, shuffle, repeat,
                    { toggle() }, { controller?.seekToPreviousMediaItem() }, { controller?.seekToNextMediaItem() },
                    { controller?.seekTo(it) }, { current?.let { s -> toggleFav(s.id) } },
                    { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle },
                    { repeat = !repeat; controller?.repeatMode = if (repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF },
                    { pick.launch("image/*") }, { tab = 0 }, { current?.let { info = it } }, { lyrics = true })
                2 -> Library(songs, query, { query = it }, ops) { scan() }
                3 -> Playlists(songs, favs, pls, openPl, { openPl = it }, ops,
                    { n -> if (n.isNotBlank() && n != "Favourites") savePl(pls + (n.trim() to emptySet())) },
                    { n -> savePl(pls - n, n); openPl = null })
                else -> SettingsScreen(settingsItems)
            }
        }
    }
    info?.let { s ->
        AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton({ info = null }) { Text("Close") } },
            title = { Text(s.title) },
            text = { Text("Artist: ${s.artist}\nAlbum: ${s.album}\nDuration: ${fmt(s.durationMs)}\nFolder: ${s.folder}") })
    }
    if (lyrics) AlertDialog(onDismissRequest = { lyrics = false }, confirmButton = { TextButton({ lyrics = false }) { Text("Close") } },
        title = { Text("Lyrics") }, text = { Text("Lyrics are not available yet.") })
}

@Composable
fun SqBtn(ic: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(16.dp)).clickable { onClick() }, Alignment.Center) {
        Icon(ic, null, tint = Accent)
    }
}

@Composable
fun Home(nSongs: Int, favCount: Int, plCount: Int, recent: List<Song>, cur: Song?, playing: Boolean, onToggle: () -> Unit,
         onExpand: () -> Unit, onScan: () -> Unit, onSearch: () -> Unit, onEq: () -> Unit, onTheme: () -> Unit, onPlay: (Song) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SqBtn(Icons.Outlined.Tune, onEq)
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable { onSearch() }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, tint = Dim); Spacer(Modifier.width(10.dp)); Text("Search songs", color = Dim)
                }
                SqBtn(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, onTheme)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Favourites", "$favCount songs", Icons.Filled.Favorite, Color(0xFF4A1620), Color(0xFFFF5470), Modifier.weight(1f))
                StatCard("Playlists", "$plCount lists", Icons.Outlined.QueueMusic, Color(0xFF0F3B45), Color(0xFF2DD4BF), Modifier.weight(1f))
                StatCard("Recent", "${recent.size} tracks", Icons.Outlined.History, Color(0xFF2A1A55), Color(0xFFA78BFA), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("NOW PLAYING", color = Dim, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Expand ›", color = Accent, modifier = Modifier.clickable { onExpand() })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Blue.copy(.5f), RoundedCornerShape(20.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Art(cur, 64.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(cur?.title ?: "Nothing playing", color = Tx, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cur?.artist ?: "Pick a song from Library", color = Dim, maxLines = 1)
                    if (cur != null) Text(if (playing) "Playing" else "Paused", color = Accent, fontSize = 13.sp)
                }
                PlayBtn(playing, 48.dp, onToggle)
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("RECENTLY PLAYED", color = Dim, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent) { s ->
                        Column(Modifier.width(90.dp).clickable { onPlay(s) }) {
                            Art(s, 90.dp)
                            Text(s.title, color = Tx, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text(s.artist, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scan Device Audio", color = Tx, fontWeight = FontWeight.Bold)
                    Text("$nSongs songs found on your phone", color = Dim, fontSize = 13.sp)
                }
                Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("Scan") }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
fun StatCard(t: String, s: String, ic: ImageVector, bg: Color, tint: Color, m: Modifier) {
    Column(m.clip(RoundedCornerShape(18.dp)).background(bg).padding(14.dp)) {
        Icon(ic, null, tint = tint); Spacer(Modifier.height(10.dp))
        Text(t, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(s, color = Color(0xFFB0B8C8), fontSize = 12.sp)
    }
}

@Composable
fun PlayBtn(playing: Boolean, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(Blue).clickable { onClick() }, Alignment.Center) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(size / 2))
    }
}

@Composable
fun MiniPlayer(s: Song, playing: Boolean, fav: Boolean, onToggle: () -> Unit, onNext: () -> Unit, onFav: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(Card)
        .border(1.dp, Line, RoundedCornerShape(20.dp)).clickable { onOpen() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(s, 48.dp, 10.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, color = Tx, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = Dim, fontSize = 13.sp, maxLines = 1)
        }
        IconButton(onFav) { Icon(if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, tint = if (fav) Color.Red else Dim) }
        PlayBtn(playing, 44.dp, onToggle)
        IconButton(onNext) { Icon(Icons.Filled.SkipNext, null, tint = Tx) }
    }
}

@Composable
fun PlayerScreen(s: Song?, playing: Boolean, pos: Long, dur: Long, fav: Boolean, shuffle: Boolean, repeat: Boolean,
                 onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onFav: () -> Unit,
                 onShuffle: () -> Unit, onRepeat: () -> Unit, onPickArt: () -> Unit, onClose: () -> Unit, onInfo: () -> Unit, onLyrics: () -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
    var vol by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val prog = if (dur > 0) pos.toFloat() / dur else 0f
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Tx) }
            Text("NOW PLAYING", color = Dim, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            IconButton(onInfo) { Icon(Icons.Filled.MoreVert, null, tint = Tx) }
        }
        Box(Modifier.fillMaxWidth()) {
            Art(s, radius = 28.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Row(Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Bg.copy(.85f))
                .border(1.dp, Line, RoundedCornerShape(14.dp)).clickable { if (s != null) onPickArt() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, null, tint = Accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Gallery Art", color = Tx, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s?.title ?: "—", color = Tx, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s?.artist ?: "", color = Accent, fontSize = 18.sp, maxLines = 1)
            }
            IconButton(onFav) { Icon(if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, tint = if (fav) Color.Red else Dim, modifier = Modifier.size(30.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(32.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            for (i in 0 until 40) Box(Modifier.width(3.dp).height((6 + ((i * 37) % 5) * 5).dp).clip(RoundedCornerShape(2.dp)).background(if (i < prog * 40) Accent else Line))
        }
        Slider(value = prog, onValueChange = { onSeek((it * dur).toLong()) },
            colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(fmt(pos), color = Dim); Text(fmt(dur), color = Dim) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            IconButton(onShuffle) { Icon(Icons.Filled.Shuffle, null, tint = if (shuffle) Accent else Dim) }
            IconButton(onPrev) { Icon(Icons.Filled.SkipPrevious, null, tint = Tx, modifier = Modifier.size(40.dp)) }
            PlayBtn(playing, 72.dp, onToggle)
            IconButton(onNext) { Icon(Icons.Filled.SkipNext, null, tint = Tx, modifier = Modifier.size(40.dp)) }
            IconButton(onRepeat) { Icon(Icons.Filled.Repeat, null, tint = if (repeat) Accent else Dim) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VolumeUp, null, tint = Dim)
            Slider(vol, { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) }, Modifier.weight(1f), valueRange = 0f..maxVol,
                colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
            Text("${(vol / maxVol * 100).toInt()}%", color = Dim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onLyrics) { Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(6.dp)); Text("LYRICS") }
            OutlinedButton(onInfo) { Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(6.dp)); Text("INFO") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SongList(list: List<Song>, ops: Ops, modifier: Modifier = Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
                .border(1.dp, if (s.id == ops.cur) Blue else Line, RoundedCornerShape(18.dp))
                .clickable { ops.onPlay(list, i) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(s, 56.dp, 12.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.title, color = Tx, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.artist} • ${fmt(s.durationMs)}", color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ ops.onFav(s.id) }) { Icon(if (s.id in ops.favs) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, tint = if (s.id in ops.favs) Color.Red else Dim) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Dim) }
                    DropdownMenu(menu, { menu = false }) {
                        ops.plNames.forEach { n -> DropdownMenuItem({ Text("Add to $n") }, { ops.onAdd(n, s.id); menu = false }) }
                        DropdownMenuItem({ Text("Song info") }, { ops.onInfo(s); menu = false })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun Library(songs: List<Song>, q: String, onQ: (String) -> Unit, ops: Ops, onScan: () -> Unit) {
    var sub by remember { mutableStateOf(0) }
    var sel by remember { mutableStateOf<String?>(null) }
    val base = songs.filter { q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    fun key(s: Song) = when (sub) { 1 -> s.artist; 2 -> s.album; else -> s.folder }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("AUDIO COLLECTION", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("LIBRARY", color = Tx, fontWeight = FontWeight.Black, fontSize = 30.sp)
            }
            OutlinedButton(onScan) { Icon(Icons.Filled.Add, null); Text("Scan Audio") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween) {
            listOf("songs", "artists", "albums", "folders").forEachIndexed { i, t ->
                Column(Modifier.clickable { sub = i; sel = null }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, color = if (sub == i) Accent else Dim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Box(Modifier.width(36.dp).height(2.dp).background(if (sub == i) Accent else Color.Transparent))
                }
            }
        }
        OutlinedTextField(q, onQ, Modifier.fillMaxWidth(), placeholder = { Text("Search songs, artists or albums...") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        if (sub == 0 || sel != null) {
            val list = if (sub == 0) base else base.filter { key(it) == sel }
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel != null) { Text("‹ Back  ", color = Accent, modifier = Modifier.clickable { sel = null }); Text(sel ?: "", color = Tx, fontWeight = FontWeight.Bold) }
                else Text("Total ${list.size} songs", color = Dim)
            }
            SongList(list, ops, Modifier.fillMaxSize())
        } else {
            val groups = base.groupBy { key(it) }.toList().sortedBy { it.first.lowercase() }
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { (name, l) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp))
                        .clickable { sel = name }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Art(l.first(), 52.dp, 12.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(name, color = Tx, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${l.size} songs", color = Dim, fontSize = 13.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun Playlists(songs: List<Song>, favs: Set<Long>, pls: Map<String, Set<Long>>, open: String?, setOpen: (String?) -> Unit, ops: Ops,
              onCreate: (String) -> Unit, onDelete: (String) -> Unit) {
    var dlg by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val all = linkedMapOf("Favourites" to favs).apply { putAll(pls) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("CUSTOM COLLECTIONS", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("PLAYLISTS", color = Tx, fontWeight = FontWeight.Black, fontSize = 30.sp)
            }
            Button({ dlg = true }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Icon(Icons.Filled.Add, null); Text("New Playlist") }
        }
        Spacer(Modifier.height(16.dp))
        if (open != null && all.containsKey(open)) {
            val l = songs.filter { it.id in (all[open] ?: emptySet()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back  ", color = Accent, modifier = Modifier.clickable { setOpen(null) })
                Text(open, color = Tx, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (open != "Favourites") TextButton({ onDelete(open) }) { Text("Delete", color = Color(0xFFFF5470)) }
            }
            Spacer(Modifier.height(8.dp))
            if (l.isEmpty()) Text("No songs yet. Use the ⋮ menu on a song to add it here.", color = Dim)
            SongList(l, ops, Modifier.fillMaxSize())
        } else {
            LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(all.toList()) { (n, ids) ->
                    val l = songs.filter { it.id in ids }
                    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).clickable { setOpen(n) }.padding(10.dp)) {
                        Art(l.firstOrNull(), radius = 14.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(n, color = Tx, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${l.size} Songs", color = Accent)
                    }
                }
            }
        }
    }
    if (dlg) AlertDialog(onDismissRequest = { dlg = false }, title = { Text("New Playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
        confirmButton = { TextButton({ onCreate(name); name = ""; dlg = false }) { Text("Create") } },
        dismissButton = { TextButton({ dlg = false }) { Text("Cancel") } })
}

@Composable
fun SettingsScreen(items: List<SItem>) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("CONFIGURATION", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("SETTINGS", color = Tx, fontWeight = FontWeight.Black, fontSize = 30.sp)
        }
        items(items) { it ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp))
                .clickable { it.onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(it.tint.copy(.15f)), Alignment.Center) { Icon(it.icon, null, tint = it.tint) }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(it.label, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(it.title, color = Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(it.sub, color = Dim, fontSize = 14.sp)
                }
                if (it.toggle != null) Switch(it.toggle, { _ -> it.onClick() }) else Icon(Icons.Filled.ChevronRight, null, tint = Dim)
            }
        }
        item {
            Text("an app by Prio.A Studio", color = Dim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
        }
    }
}

@Composable
fun EqScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("p", 0) }
    val e = Fx.eq
    val n = e?.numberOfBands?.toInt() ?: 0
    val lo = e?.bandLevelRange?.get(0)?.toInt() ?: -1500
    val hi = e?.bandLevelRange?.get(1)?.toInt() ?: 1500
    var on by remember { mutableStateOf(p.getBoolean("eq_on", true)) }
    var bass by remember { mutableStateOf(p.getInt("bass", 0).toFloat()) }
    var virt by remember { mutableStateOf(p.getInt("virt", 0).toFloat()) }
    var preset by remember { mutableStateOf(p.getString("eq_preset", "Normal") ?: "Normal") }
    var lv by remember { mutableStateOf(List(n) { b -> (p.getString("eq_lv", "") ?: "").split(",").getOrNull(b)?.toIntOrNull() ?: 0 }) }
    fun save() {
        p.edit().putBoolean("eq_on", on).putInt("bass", bass.toInt()).putInt("virt", virt.toInt())
            .putString("eq_lv", lv.joinToString(",")).putString("eq_preset", preset).apply()
        Fx.apply(ctx)
    }
    val presets = linkedMapOf(
        "Normal" to listOf(0f, 0f, 0f, 0f, 0f), "Bass Boost" to listOf(.8f, .5f, 0f, 0f, 0f), "Treble Boost" to listOf(0f, 0f, 0f, .5f, .8f),
        "Vocal" to listOf(-.4f, 0f, .6f, .5f, -.2f), "Rock" to listOf(.6f, .3f, -.3f, .4f, .7f),
        "Pop" to listOf(-.2f, .3f, .6f, .3f, -.2f), "Classical" to listOf(.5f, .3f, -.2f, .3f, .5f))
    val sc = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    Column(sc) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Tx) }
            Column(Modifier.weight(1f)) {
                Text("AUDIO SETTINGS", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("EQUALIZER", color = Tx, fontWeight = FontWeight.Black, fontSize = 28.sp)
            }
            Switch(on, { on = it; save() })
        }
        if (e == null) {
            Spacer(Modifier.height(24.dp))
            Text("Equalizer is not ready yet. Play any song once, then open this screen again.", color = Dim)
            return@Column
        }
        Spacer(Modifier.height(16.dp))
        Text("PRESETS", color = Dim, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets.keys.toList()) { name ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (preset == name) Blue else Card)
                    .border(1.dp, if (preset == name) Blue else Line, RoundedCornerShape(50))
                    .clickable {
                        val v = presets[name]!!
                        lv = List(n) { b -> val f = v[(b * v.size / n).coerceAtMost(v.size - 1)]; (f * (if (f > 0) hi else -lo)).toInt() }
                        preset = name; save()
                    }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(name, color = if (preset == name) Color.White else Tx) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp)) {
            for (b in 0 until n) {
                val hz = e.getCenterFreq(b.toShort()) / 1000
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", color = Dim, modifier = Modifier.width(64.dp), fontSize = 13.sp)
                    Slider(lv[b].toFloat(), { v -> lv = lv.toMutableList().also { it[b] = v.toInt() }; preset = "Custom"; save() }, Modifier.weight(1f),
                        valueRange = lo.toFloat()..hi.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
                    Text("${lv[b] / 100} dB", color = Tx, modifier = Modifier.width(52.dp), fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Text("Bass Boost", color = Tx, fontWeight = FontWeight.Bold)
            Slider(bass, { bass = it; save() }, valueRange = 0f..1000f, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
            Text("Surround (Virtualizer)", color = Tx, fontWeight = FontWeight.Bold)
            Slider(virt, { virt = it; save() }, valueRange = 0f..1000f, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
        }
        Spacer(Modifier.height(12.dp))
        TextButton({ lv = List(n) { 0 }; bass = 0f; virt = 0f; preset = "Normal"; save() }) { Text("Reset all", color = Accent) }
    }
}
