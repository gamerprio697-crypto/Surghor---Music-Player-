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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.LruCache
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

var dark by mutableStateOf(true)
var artVer by mutableStateOf(0)
var gPos by mutableStateOf(0L)
var gDur by mutableStateOf(0L)
val Cyan = Color(0xFF4FE3FF)
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
            MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Bg, surface = Bg) else lightColorScheme(background = Bg, surface = Bg)) {
                var splash by rememberSaveable { mutableStateOf(true) }
                if (splash) Splash { splash = false } else App()
            }
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

object ArtCache {
    val c = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
}

@Composable
fun Art(song: Song?, size: Dp = Dp.Unspecified, radius: Dp = 14.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = if (size == Dp.Unspecified || size.value > 120f) 600 else 240
    val key = "${song?.id}_${artVer}_$px"
    var bmp by remember(key) { mutableStateOf(ArtCache.c.get(key)) }
    LaunchedEffect(key) {
        if (bmp == null && song != null) {
            val b = withContext(Dispatchers.IO) {
                runCatching {
                    val f = artFile(ctx, song.id)
                    if (f.exists()) BitmapFactory.decodeFile(f.path)
                    else if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null) else null
                }.getOrNull()
            }
            if (b != null) { ArtCache.c.put(key, b); bmp = b }
        }
    }
    Box(modifier.then(if (size != Dp.Unspecified) Modifier.size(size) else Modifier).clip(RoundedCornerShape(radius))
        .background(Brush.verticalGradient(listOf(Color(0xFF1B2A5C), Color(0xFF3A1F6E))))
        .border(1.dp, Line, RoundedCornerShape(radius)), Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Filled.MusicNote, null, tint = Color(0xFF5B8CFF), modifier = Modifier.size(if (size != Dp.Unspecified) size / 2 else 80.dp))
    }
}

@androidx.compose.runtime.Immutable
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
    var about by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var curId by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
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
                override fun onMediaItemTransition(m: MediaItem?, r: Int) { curId = m?.mediaId?.toLongOrNull(); gDur = c.duration.coerceAtLeast(0) }
                override fun onPlaybackStateChanged(s: Int) { gDur = c.duration.coerceAtLeast(0) }
            })
            playing = c.isPlaying; curId = c.currentMediaItem?.mediaId?.toLongOrNull()
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    LaunchedEffect(controller) { while (true) { controller?.let { gPos = it.currentPosition; gDur = it.duration.coerceAtLeast(0) }; delay(250) } }
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
    val ops = remember(favs, curId, pls) { Ops(favs, curId, pls.keys.toList(), { toggleFav(it) },
        { n, id -> savePl(pls + (n to ((pls[n] ?: emptySet()) + id))); Toast.makeText(ctx, "Added to $n", Toast.LENGTH_SHORT).show() },
        { info = it }, { l, i -> playList(l, i) }) }

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
        SItem("AUDIO SETTINGS", "Sound & Audio Equalizer", "10-Band studio EQ with Bass Boost, Stereo Widen & presets", Icons.Outlined.Tune, Accent) { showEq = true },
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
        SItem("ABOUT", "Surghor - Music Player", "Version 1.0", Icons.Outlined.Info, Dim) { about = true }
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
                1 -> PlayerScreen(current, playing, current?.let { it.id in favs } == true, shuffle, repeat,
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
    if (about) AlertDialog(onDismissRequest = { about = false }, confirmButton = { TextButton({ about = false }) { Text("Close") } },
        title = { Text("Surghor - Music Player") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Version 1.0\n\nA simple, fast and private music player made for people who just want to enjoy their own music.\n\n" +
                    "• Plays every song stored on your phone\n" +
                    "• Keeps playing in the background, with notification and lock screen controls\n" +
                    "• 10-band studio equalizer with bass boost and stereo widening\n" +
                    "• Custom artwork from your gallery, playlists, favourites and sleep timer\n" +
                    "• Dark and light themes\n\n" +
                    "Privacy: Surghor has no internet permission, no ads and no tracking. Your music and your data never leave your phone.\n\n" +
                    "an app by Prio.A Studio")
            }
        })
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
fun Times() {
    val st = TextStyle(color = Color(0xFF9FB4E8), fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 20.sp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), Arrangement.SpaceBetween) { Text(fmt(gPos), style = st); Text(fmt(gDur), style = st) }
}

@Composable
fun SeekWave(onSeek: (Long) -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp), Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp)) {
            val n = 90; val p = if (gDur > 0) gPos.toFloat() / gDur else 0f
            val bw = size.width / n
            for (i in 0 until n) {
                val hh = size.height * (0.25f + (((i * 13) % 7) / 7f) * 0.75f)
                drawRoundRect(if (i < p * n) Cyan else Color(0xFF3A4254), Offset(i * bw, (size.height - hh) / 2), GSize(bw * 0.6f, hh), CornerRadius(bw * 0.3f))
            }
        }
        Slider(value = if (gDur > 0) gPos.toFloat() / gDur else 0f,
            onValueChange = { gPos = (it * gDur).toLong(); onSeek(gPos) },
            colors = SliderDefaults.colors(thumbColor = Color(0xFF7B8CFF), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
    }
}

@Composable
fun PlayerScreen(s: Song?, playing: Boolean, fav: Boolean, shuffle: Boolean, repeat: Boolean,
                 onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onFav: () -> Unit,
                 onShuffle: () -> Unit, onRepeat: () -> Unit, onPickArt: () -> Unit, onClose: () -> Unit, onInfo: () -> Unit, onLyrics: () -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
    var vol by remember { mutableStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val soft = Color(0xFFDDE3F0)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1C2230), Color(0xFF11151C))))) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 14) drawCircle(Color.White.copy(alpha = 0.12f), 1.5f + (i % 3),
                Offset(size.width * ((i * 37 % 100) / 100f), size.height * ((i * 53 % 100) / 100f)))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = soft) }
                Text("NOW PLAYING", color = Color(0xFF9AA5B8), fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 20.sp, letterSpacing = 1.sp)
                IconButton(onInfo) { Icon(Icons.Filled.MoreVert, null, tint = soft) }
            }
            Box(Modifier.fillMaxWidth()) {
                Art(s, radius = 28.dp, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                Row(Modifier.align(Alignment.BottomEnd).padding(14.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E2430).copy(.92f))
                    .border(1.dp, Color(0xFF39414F), RoundedCornerShape(16.dp)).clickable { if (s != null) onPickArt() }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Image, null, tint = Color(0xFF5B8CFF), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("Gallery Art", color = Color.White, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s?.title ?: "", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s?.artist ?: "", color = Cyan, fontSize = 17.sp, maxLines = 1)
                }
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF262B36)).clickable { onFav() }, Alignment.Center) {
                    Icon(Icons.Filled.Favorite, null, tint = if (fav) Color(0xFFFF4D6D) else Color(0xFFE5E7EB), modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val n = 48; val bw = size.width / n
                for (i in 0 until n) {
                    val hh = size.height * (0.25f + ((i * 7) % 5) * 0.15f)
                    drawRoundRect(Color(0xFF4A5262), Offset(i * bw + bw * 0.25f, (size.height - hh) / 2), GSize(bw * 0.5f, hh), CornerRadius(bw * 0.25f))
                }
            }
            SeekWave(onSeek)
            Times()
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                IconButton(onShuffle) { Icon(Icons.Filled.Shuffle, null, tint = if (shuffle) Cyan else Cyan.copy(.55f), modifier = Modifier.size(30.dp)) }
                IconButton(onPrev) { Icon(Icons.Filled.SkipPrevious, null, tint = Cyan, modifier = Modifier.size(44.dp)) }
                Box(Modifier.size(96.dp).shadow(28.dp, CircleShape, ambientColor = Cyan, spotColor = Cyan).clip(CircleShape).background(Cyan).clickable { onToggle() }, Alignment.Center) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color(0xFF0B1220), modifier = Modifier.size(54.dp))
                }
                IconButton(onNext) { Icon(Icons.Filled.SkipNext, null, tint = Cyan, modifier = Modifier.size(44.dp)) }
                IconButton(onRepeat) { Icon(Icons.Filled.Repeat, null, tint = if (repeat) Cyan else Cyan.copy(.55f), modifier = Modifier.size(30.dp)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VolumeUp, null, tint = Color(0xFF9AA5B8))
                Slider(vol, { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) }, Modifier.weight(1f), valueRange = 0f..maxVol,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF7B8CFF), activeTrackColor = Color(0xFF8B7BFF), inactiveTrackColor = Color(0xFF2A3040)))
                Text("${(vol / maxVol * 100).toInt()}%", color = Color(0xFF9AA5B8), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onLyrics, shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(contentColor = soft)) {
                    Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(6.dp)); Text("LYRICS", fontWeight = FontWeight.Bold) }
                OutlinedButton(onInfo, shape = RoundedCornerShape(50), colors = ButtonDefaults.outlinedButtonColors(contentColor = soft)) {
                    Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(6.dp)); Text("INFO", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp))
        }
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
fun VSlider(value: Float, onChange: (Float) -> Unit) {
    Slider(value, onChange, Modifier.graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin(0f, 0f) }
        .layout { m, c ->
            val p = m.measure(Constraints(c.minHeight, c.maxHeight, c.minWidth, c.maxWidth))
            layout(p.height, p.width) { p.place(-p.width, 0) }
        }, valueRange = -12f..12f,
        colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
}

@Composable
fun EqScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("p", 0) }
    var on by remember { mutableStateOf(Dsp.on) }
    var g by remember { mutableStateOf(Dsp.gains.toList()) }
    var bass by remember { mutableStateOf(Dsp.bass) }
    var wide by remember { mutableStateOf(Dsp.wide) }
    var preset by remember { mutableStateOf(p.getString("dsp_preset", "Flat") ?: "Flat") }
    fun push() = Dsp.set(ctx, on, g, bass, wide, preset)
    val presets = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to listOf(6f, 5f, 4f, 2f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
        "Rock" to listOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f),
        "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "Hip-Hop" to listOf(6f, 5f, 2f, 3f, -1f, -1f, 2f, 0f, 2f, 3f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f),
        "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f),
        "Classical" to listOf(4f, 3f, 2f, 1f, 0f, 0f, -1f, -2f, -2f, -3f))
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Tx) }
            Column(Modifier.weight(1f)) {
                Text("AUDIO SETTINGS", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("EQUALIZER", color = Tx, fontWeight = FontWeight.Black, fontSize = 28.sp)
            }
            Switch(on, { on = it; push() })
        }
        Text("32-bit studio processing. When every band is flat, your music plays untouched.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets.keys.toList()) { name ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (preset == name) Blue else Card)
                    .border(1.dp, if (preset == name) Blue else Line, RoundedCornerShape(50))
                    .clickable { g = presets[name]!!; preset = name; push() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(name, color = if (preset == name) Color.White else Tx) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(vertical = 14.dp, horizontal = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), Arrangement.SpaceBetween) { Text("+12 dB", color = Dim, fontSize = 11.sp); Text("-12 dB", color = Dim, fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 10) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%+d".format(g[i].toInt()), color = Tx, fontSize = 10.sp)
                    Box(Modifier.height(180.dp).fillMaxWidth(), Alignment.Center) {
                        VSlider(g[i]) { v -> g = g.toMutableList().also { l -> l[i] = v.roundToInt().toFloat() }; preset = "Custom"; push() }
                    }
                    Text(labels[i], color = Dim, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Text("Bass Boost  +${bass.roundToInt()} dB", color = Tx, fontWeight = FontWeight.Bold)
            Slider(bass, { bass = it; preset = "Custom"; push() }, valueRange = 0f..12f, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
            Text("Stereo Widen  ${(wide * 100).roundToInt()}%", color = Tx, fontWeight = FontWeight.Bold)
            Slider(wide, { wide = it; preset = "Custom"; push() }, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line))
        }
        TextButton({ g = List(10) { 0f }; bass = 0f; wide = 0f; preset = "Flat"; push() }) { Text("Reset all", color = Accent) }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun Splash(onDone: () -> Unit) {
    val view = LocalView.current
    val done = rememberUpdatedState(onDone)
    val mp = remember { MediaPlayer() }
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; done.value() } }
    DisposableEffect(Unit) {
        val w = view.context.findActivity()?.window
        val ctl = if (w != null) WindowCompat.getInsetsController(w, view) else null
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        if (w != null && Build.VERSION.SDK_INT >= 28) {
            val lp = w.attributes; lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES; w.attributes = lp
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()); runCatching { mp.release() } }
    }
    AndroidView(modifier = Modifier.fillMaxSize().background(Color.Black), factory = { ctx ->
        val root = FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val tv = TextureView(ctx)
        root.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                try {
                    mp.setSurface(Surface(st))
                    ctx.assets.openFd("splash.mp4").use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                        if (vw > 0 && vh > 0) root.post {
                            val k = maxOf(root.width.toFloat() / vw, root.height.toFloat() / vh)
                            tv.layoutParams = FrameLayout.LayoutParams((vw * k).toInt() + 1, (vh * k).toInt() + 1, Gravity.CENTER)
                        }
                    }
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnCompletionListener { finish() }
                    mp.setOnErrorListener { _, _, _ -> finish(); true }
                    mp.prepareAsync()
                } catch (e: Exception) { finish() }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        root
    })
}
