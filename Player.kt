@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamtv.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Overlay { NONE, BAR, MENU, AUDIO, SUBS, EPISODES }

private val ASPECTS = listOf("Ajusté", "Étiré", "Zoom", "16:9", "4:3")

fun fmtTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun openExternal(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")) }
}

@Composable
fun Equalizer() {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(24) { i ->
            val h by t.animateFloat(0.15f, 1f, infiniteRepeatable(tween(300 + (i * 53) % 400, easing = LinearEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(10.dp).fillMaxHeight(h).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Rounded translucent ("glass") button used everywhere: menus, player bar, panels. */
@Composable
fun GlassButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, big: Boolean = false, fontSp: Int = 0, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val p = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(if (big) 52.dp else 42.dp).widthIn(min = if (big) 52.dp else 42.dp)
            .clip(shape)
            .background(if (focused) p.copy(alpha = 0.9f) else if (selected) p.copy(alpha = 0.40f) else Color(0x33FFFFFF))
            .border(1.dp, if (focused) Color.White else Color(0x44FFFFFF), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = (if (fontSp > 0) fontSp else if (big) 20 else 14).sp, color = Color.White, maxLines = 1) }
}

/** Small preview window (16:9). OK on it opens fullscreen. */
@Composable
fun MiniPlayer(vm: MainViewModel, modifier: Modifier = Modifier) {
    val s = vm.session
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.aspectRatio(16f / 9f).background(Color.Black)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x55FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .clickable { s.fullscreen = true }
    ) {
        key(s.engineGen) {
            AndroidView(factory = { c -> s.createHost(c) }, onRelease = { v -> s.releaseHost(v) }, modifier = Modifier.fillMaxSize())
        }
        if (s.item?.kind == "radio") Box(Modifier.align(Alignment.Center)) { Equalizer() }
        if (s.loading) Text("Chargement...", modifier = Modifier.align(Alignment.Center), color = Color.White)
    }
}

@Composable
private fun PickerPanel(title: String, entries: List<Pair<String, Boolean>>, first: FocusRequester, modifier: Modifier, onPick: (Int) -> Unit) {
    Column(
        modifier.padding(16.dp).width(400.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xCC101018))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entries) { i, e ->
                GlassButton(
                    (if (e.second) "● " else "○ ") + e.first,
                    if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth(),
                    selected = e.second
                ) { onPick(i) }
            }
        }
    }
}

@Composable
private fun EpgCell(label: String, e: EpgEntity?, tf: SimpleDateFormat, strong: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Color(0xFF9AA4C0))
        if (e == null) Text("—", fontSize = 13.sp, color = Color(0xFF6B7490))
        else {
            Text(e.title, fontSize = if (strong) 15.sp else 13.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tf.format(Date(e.start)) + " - " + tf.format(Date(e.stop)), fontSize = 11.sp, color = Color(0xFFB8C0DA))
        }
    }
}

@Composable
fun FullPlayer(vm: MainViewModel) {
    val s = vm.session
    val cur = s.item ?: return
    val ctx = LocalContext.current
    val isLive = cur.kind != "movie"
    val step = vm.settings.seek.int
    val primary = MaterialTheme.colorScheme.primary

    var overlay by remember { mutableStateOf(Overlay.BAR) }
    var tick by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf(true) }
    var epg by remember { mutableStateOf(Triple<EpgEntity?, EpgEntity?, EpgEntity?>(null, null, null)) }
    val focus = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val tf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val showError = s.error != null
    val modal = overlay != Overlay.NONE || showError

    BackHandler { if (overlay != Overlay.NONE) overlay = Overlay.NONE else vm.exitFullscreen() }

    LaunchedEffect(Unit) {
        while (true) {
            s.engine?.let { e -> pos = e.position; dur = e.duration; playing = e.isPlaying; buffering = e.isBuffering }
            delay(500)
        }
    }
    LaunchedEffect(cur.id) {
        epg = if (cur.tvgId.isNotBlank()) runCatching { vm.repo.around(cur.tvgId) }.getOrDefault(Triple(null, null, null)) else Triple(null, null, null)
        banner = true
        delay(3500)
        banner = false
    }
    // the bar hides by itself 5 seconds after the last key press
    LaunchedEffect(tick, overlay) {
        if (overlay == Overlay.BAR) { delay(5000); if (overlay == Overlay.BAR) overlay = Overlay.NONE }
    }
    LaunchedEffect(s.notice) { if (s.notice.isNotBlank()) { delay(4500); s.clearNotice() } }
    LaunchedEffect(modal, overlay) {
        delay(60)
        runCatching { if (modal) first.requestFocus() else focus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                tick++
                if (modal) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AKey.KEYCODE_CHANNEL_UP, AKey.KEYCODE_MEDIA_NEXT -> { s.zap(1); true }
                    AKey.KEYCODE_CHANNEL_DOWN, AKey.KEYCODE_MEDIA_PREVIOUS -> { s.zap(-1); true }
                    AKey.KEYCODE_DPAD_UP -> { if (isLive) s.zap(1) else overlay = Overlay.BAR; true }
                    AKey.KEYCODE_DPAD_DOWN -> { if (isLive) s.zap(-1) else overlay = Overlay.BAR; true }
                    AKey.KEYCODE_DPAD_LEFT -> { if (!isLive) s.seekBy(-step * 1000L); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_DPAD_RIGHT -> { if (!isLive) s.seekBy(step * 1000L); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_MEDIA_REWIND -> { s.seekBy(-step * 1000L); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_MEDIA_FAST_FORWARD -> { s.seekBy(step * 1000L); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER -> { overlay = Overlay.BAR; true }
                    AKey.KEYCODE_MEDIA_PLAY_PAUSE -> { s.togglePlay(); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_MEDIA_PLAY -> { s.engine?.setPlaying(true); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_MEDIA_PAUSE -> { s.engine?.setPlaying(false); overlay = Overlay.BAR; true }
                    AKey.KEYCODE_MEDIA_STOP -> { vm.exitFullscreen(); true }
                    AKey.KEYCODE_MENU -> { overlay = Overlay.MENU; true }
                    AKey.KEYCODE_PROG_RED, AKey.KEYCODE_INFO, AKey.KEYCODE_GUIDE -> { overlay = Overlay.BAR; true }
                    AKey.KEYCODE_PROG_GREEN -> { overlay = Overlay.AUDIO; true }
                    AKey.KEYCODE_PROG_YELLOW -> { overlay = Overlay.SUBS; true }
                    AKey.KEYCODE_PROG_BLUE -> { s.cycleAspect(); true }
                    else -> false
                }
            }
    ) {
        key(s.engineGen) {
            AndroidView(factory = { c -> s.createHost(c) }, onRelease = { v -> s.releaseHost(v) }, modifier = Modifier.fillMaxSize())
        }
        if (cur.kind == "radio") Box(Modifier.align(Alignment.Center)) { Equalizer() }
        if ((buffering || s.loading) && !showError && !s.inPip) {
            Text("Chargement...", modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        // ---- small banner when zapping (bar hidden)
        if (banner && overlay == Overlay.NONE && !showError && !s.inPip) {
            Column(
                Modifier.align(Alignment.TopStart).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x99101018)).padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(cur.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                epg.second?.let { Text(it.title, fontSize = 13.sp, color = Color(0xFFD0D6EA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (s.recording && overlay == Overlay.NONE && !s.inPip) {
            Text("● REC  " + fmtTime(s.recSeconds * 1000), color = Color(0xFFFF5555), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(24.dp))
        }

        // ---- media bar (Netflix / Windows Media Player style): translucent, hides after 5 s
        if (overlay == Overlay.BAR && !showError && !s.inPip) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(horizontal = 28.dp, vertical = 20.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0x66101018))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            cur.name + if (s.items.size > 1) "   ${s.index + 1}/${s.items.size}" else "",
                            fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        if (s.enhance != "off") Text("✨ " + vm.settings.enhance.labels[vm.settings.enhance.options.indexOf(s.enhance).coerceAtLeast(0)], fontSize = 12.sp, color = primary)
                        if (s.recording) Text("● REC  " + fmtTime(s.recSeconds * 1000), color = Color(0xFFFF5555), fontWeight = FontWeight.Bold)
                    }
                    if (s.notice.isNotBlank()) Text(s.notice, fontSize = 13.sp, color = Color(0xFFFFD27A))

                    if (isLive) {
                        val c = epg.second
                        val frac = if (c != null && c.stop > c.start) ((System.currentTimeMillis() - c.start).toFloat() / (c.stop - c.start)).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(primary))
                        }
                        if (epg.first == null && epg.second == null && epg.third == null) {
                            Text("Guide des programmes indisponible pour cette chaîne (ajoutez un lien XMLTV ou actualisez le guide)", fontSize = 12.sp, color = Color(0xFF9AA4C0))
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                EpgCell("◀ Précédent", epg.first, tf, false, Modifier.weight(1f))
                                EpgCell("● En cours", epg.second, tf, true, Modifier.weight(1.3f))
                                EpgCell("Ensuite ▶", epg.third, tf, false, Modifier.weight(1f))
                            }
                        }
                    } else {
                        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(fmtTime(pos), fontSize = 13.sp, color = Color.White)
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))) {
                                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(primary))
                            }
                            Text(fmtTime(dur), fontSize = 13.sp, color = Color.White)
                        }
                    }

                    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        item { GlassButton("⏮", big = true) { s.zap(-1) } }
                        item {
                            GlassButton("⏪", big = true) {
                                if (!isLive || s.engine?.seekable == true) s.seekBy(-step * 1000L) else toast(ctx, "Retour impossible sur ce direct")
                            }
                        }
                        item { GlassButton(if (playing) "⏸" else "▶", Modifier.focusRequester(first), big = true) { s.togglePlay() } }
                        item { GlassButton("⏹", big = true) { vm.exitFullscreen() } }
                        item {
                            GlassButton("⏩", big = true) {
                                if (!isLive || s.engine?.seekable == true) s.seekBy(step * 1000L) else toast(ctx, "Avance impossible sur ce direct")
                            }
                        }
                        item { GlassButton("⏭", big = true) { s.zap(1) } }
                        if (isLive) item { GlassButton(if (s.recording) "⏹ Arrêter REC" else "⏺ Enregistrer", selected = s.recording) { s.toggleRecording(ctx) } }
                        item { GlassButton("🔊 Audio") { overlay = Overlay.AUDIO } }
                        item { GlassButton("CC Sous-titres") { overlay = Overlay.SUBS } }
                        item { GlassButton("✨ Qualité") { s.cycleEnhance() } }
                        item { GlassButton("⚙ Options") { overlay = Overlay.MENU } }
                    }
                }
            }
        }

        // ---- error panel
        if (showError) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color(0xDD101018)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(s.error ?: "", color = Color.White)
                GlassButton("Recharger le flux", Modifier.focusRequester(first)) { s.retry() }
                GlassButton("Essayer l'autre lecteur (ExoPlayer / VLC)") { s.switchEngine() }
                GlassButton("Ouvrir dans un lecteur externe") { openExternal(ctx, s.currentUrl()) }
                GlassButton("Retour") { vm.exitFullscreen() }
            }
        }

        // ---- advanced options
        if (overlay == Overlay.MENU && !showError) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xCC101018))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "${cur.name}  -  ${if (s.engineKind == "vlc") "VLC" else "ExoPlayer"}  ${s.engine?.videoInfo ?: ""}",
                    color = Color(0xFFCCCCCC), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { GlassButton(if (playing) "Pause" else "Lecture", Modifier.focusRequester(first)) { s.togglePlay() } }
                    item { GlassButton("Vitesse ${s.speed}x") { s.cycleSpeed() } }
                    item { GlassButton("Format : ${ASPECTS[s.aspect]}") { s.cycleAspect() } }
                    item { GlassButton("Lecteur : ${if (s.engineKind == "vlc") "VLC" else "ExoPlayer"} (changer)") { s.switchEngine() } }
                    if (!isLive && s.items.size > 1) {
                        item { GlassButton("Épisodes (${s.index + 1}/${s.items.size})") { overlay = Overlay.EPISODES } }
                    }
                    item { GlassButton("Tampon : ${if (s.stable) "Stable" else "Normal"}") { s.toggleStable() } }
                    item { GlassButton("Minuterie : ${s.sleepLabel}") { s.cycleSleep() } }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { GlassButton("Décalage sous-titres -  (${s.subDelay} ms)") { if (s.engine?.supportsDelay() == true) s.addSubDelay(-250) else toast(ctx, "Réglage disponible avec le lecteur VLC") } }
                    item { GlassButton("Décalage sous-titres +") { if (s.engine?.supportsDelay() == true) s.addSubDelay(250) else toast(ctx, "Réglage disponible avec le lecteur VLC") } }
                    item { GlassButton("Décalage audio -  (${s.audioDelay} ms)") { if (s.engine?.supportsDelay() == true) s.addAudioDelayMs(-100) else toast(ctx, "Réglage disponible avec le lecteur VLC") } }
                    item { GlassButton("Décalage audio +") { if (s.engine?.supportsDelay() == true) s.addAudioDelayMs(100) else toast(ctx, "Réglage disponible avec le lecteur VLC") } }
                    item { GlassButton("Image dans l'image") { overlay = Overlay.NONE; (ctx as? MainActivity)?.enterPip() } }
                    item { GlassButton("Lecteur externe") { s.engine?.setPlaying(false); openExternal(ctx, s.currentUrl()) } }
                    item { GlassButton("Recharger") { s.retry() } }
                    item { GlassButton("Fermer") { overlay = Overlay.NONE } }
                }
            }
        }

        // ---- track pickers
        if (overlay == Overlay.AUDIO && !showError) {
            val tr = remember(overlay) { s.audioTracks() }
            val entries = if (tr.isEmpty()) listOf("Aucune piste audio détectée" to false) else tr.map { it.label to it.selected }
            PickerPanel("Piste audio", entries, first, Modifier.align(Alignment.CenterEnd)) { i ->
                if (tr.isNotEmpty()) s.engine?.selectAudio(tr[i].id)
                overlay = Overlay.NONE
            }
        }
        if (overlay == Overlay.SUBS && !showError) {
            val tr = remember(overlay) { s.subtitleTracks() }
            val entries = listOf("Désactivés" to tr.none { it.selected }) + tr.map { it.label to it.selected }
            PickerPanel("Sous-titres", entries, first, Modifier.align(Alignment.CenterEnd)) { i ->
                if (i == 0) s.engine?.selectSubtitle(-1) else s.engine?.selectSubtitle(tr[i - 1].id)
                overlay = Overlay.NONE
            }
        }
        if (overlay == Overlay.EPISODES && !showError) {
            val entries = s.items.mapIndexed { i, c -> (if (c.groupTitle.startsWith("Saison")) "S" + c.groupTitle.removePrefix("Saison ") + " · " else "") + c.name to (i == s.index) }
            PickerPanel("Épisodes", entries, first, Modifier.align(Alignment.CenterEnd)) { i ->
                s.jumpTo(i)
                overlay = Overlay.NONE
            }
        }
    }
}
