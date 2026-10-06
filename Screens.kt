@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamtv.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.material3.Text as M3Text

enum class Section(val icon: String, val label: String) {
    HOME("🏠", "Accueil"), SEARCH("🔍", "Recherche"), LIVE("📺", "TV en direct"), MOVIES("🎬", "Films"), SERIES("🍿", "Séries"),
    MATCHES("⚽", "Matchs"), RADIO("📻", "Radio"), CODES("🔑", "Codes"), SOURCES("➕", "Sources"), SETTINGS("⚙", "Réglages")
}

typealias OpenFn = (List<ChannelEntity>, Int, Long) -> Unit

private val SPORT_RE = Regex("(?i)sport|bein|ssc|match|football|soccer|ligue|liga|premier|champions|كأس|رياض|مباريات|كرة")

fun toast(ctx: Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

fun launchTrailer(ctx: Context, scope: CoroutineScope, vm: MainViewModel, c: ChannelEntity) {
    scope.launch {
        val t = vm.trailer(c)
        if (t == null) toast(ctx, "Pas de bande-annonce disponible")
        else {
            val u = if (t.startsWith("http")) t else "https://www.youtube.com/watch?v=$t"
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        }
    }
}

@Composable
fun focusBorder(): Border = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(10.dp))

fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 12) "Bonjour" else if (h < 18) "Bon après-midi" else "Bonsoir"
}

fun todayFr(): String = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date()).replaceFirstChar { it.uppercase() }

@Composable
fun ClockInline(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.FRANCE).format(now) }
    val date = remember(now) { SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(date, fontSize = 12.sp, color = Color.LightGray)
        Text(time, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

// =====================================================================================================
// Shell: three navigation styles (side bar / top bar / tiles)
// =====================================================================================================

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var episodes by remember { mutableStateOf<List<ChannelEntity>?>(null) }

    val open: OpenFn = { list, idx, resume ->
        val target = list[idx]
        if (target.kind == "series") {
            scope.launch {
                val e = vm.episodes(target)
                if (e.isEmpty()) toast(ctx, "Aucun épisode trouvé") else episodes = e
            }
        } else vm.playFull(list, idx, resume)
    }
    val body: @Composable () -> Unit = {
        when (vm.section) {
            Section.HOME -> HomeSection(vm, open)
            Section.SEARCH -> SearchScreen(vm, open)
            Section.LIVE -> BrowseScreen(vm, "live", null, "live", open)
            Section.MOVIES -> BrowseScreen(vm, "movie", null, "movie", open)
            Section.SERIES -> BrowseScreen(vm, "series", null, "series", open)
            Section.MATCHES -> BrowseScreen(vm, "live", SPORT_RE, "matches", open)
            Section.RADIO -> BrowseScreen(vm, "radio", null, "radio", open)
            Section.CODES -> CodesScreen(vm)
            Section.SOURCES -> SourcesScreen(vm)
            Section.SETTINGS -> SettingsScreen(vm)
        }
    }

    if (vm.settings.navStyle.v == "side") {
        Row(Modifier.fillMaxSize()) {
            SideRail(vm)
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 6.dp, top = 8.dp, end = 12.dp)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(vm.section.label, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    UpdateBadge(vm)
                    ClockInline()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { body() }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("STREAM TV", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(Section.values().toList()) { sec -> TopPill(sec, vm.section == sec) { vm.section = sec } }
                }
                UpdateBadge(vm)
                ClockInline()
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) { body() }
        }
    }

    episodes?.let { eps ->
        Dialog(onDismissRequest = { episodes = null }) {
            Box(Modifier.width(520.dp).heightIn(max = 460.dp).background(Color(0xEE111111)).padding(16.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(eps) { i, e ->
                        Button(onClick = { episodes = null; vm.playFull(eps, i, 0L) }, modifier = Modifier.fillMaxWidth()) {
                            Text(e.groupTitle + " · " + e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TopPill(sec: Section, selected: Boolean, onClick: () -> Unit) {
    GlassButton(sec.icon + " " + sec.label, selected = selected, onClick = onClick)
}

@Composable
fun SideRail(vm: MainViewModel) {
    Column(
        Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp).fillMaxHeight().width(62.dp)
            .clip(RoundedCornerShape(31.dp))
            .background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF))))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(31.dp)),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Section.values().forEach { sec -> GlassButton(sec.icon, selected = vm.section == sec, fontSp = 20) { vm.section = sec } }
    }
}

@Composable
fun UpdateBadge(vm: MainViewModel) {
    vm.update.value?.let { u -> GlassButton("⬆ Mise à jour ${u.name}", selected = true) { vm.section = Section.SETTINGS } }
}

// =====================================================================================================
// Home: hero + rows (side / top styles) or greeting + tiles (tiles style)
// =====================================================================================================

@Composable
fun HomeSection(vm: MainViewModel, open: OpenFn) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val style = vm.settings.navStyle.v
    val profile by vm.profile.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favorites.collectAsStateWithLifecycle(emptyList())
    val count by vm.itemCount.collectAsStateWithLifecycle()
    val hero by produceState(emptyList<ChannelEntity>(), count) {
        val m = vm.featured("movie")
        value = if (m.isNotEmpty()) m else vm.featured("series")
    }
    val cw = remember(hist) { hist.filter { it.kind == "movie" && it.position > 5000 } }
    val prog = remember(hist) { hist.associate { it.itemId to (it.position.toFloat() / maxOf(1L, it.duration)).coerceIn(0f, 1f) } }
    val recentLive = remember(hist) { hist.filter { it.kind == "live" || it.kind == "radio" }.map { it.toItem() } }

    if (count == 0) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Bienvenue !", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Ajoutez votre première source pour commencer.", color = Color.LightGray)
            Spacer(Modifier.height(16.dp))
            GlassButton("Ajouter une source") { vm.section = Section.SOURCES }
        }
        return
    }
    val wide = style == "top"
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        if (style == "tiles") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${greeting()}, ${profile?.name ?: ""}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(todayFr(), color = Color.LightGray, fontSize = 13.sp)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                    items(Section.values().filter { it != Section.HOME }) { sec -> Tile(sec) { vm.section = sec } }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigCard("Films", "Des milliers de films", hero.getOrNull(0)?.logo, Modifier.weight(1f)) { vm.section = Section.MOVIES }
                    BigCard("Séries", "Saisons et épisodes", hero.getOrNull(1)?.logo, Modifier.weight(1f)) { vm.section = Section.SERIES }
                    BigCard("TV en direct", "Chaînes en HD", null, Modifier.weight(1f)) { vm.section = Section.LIVE }
                }
            }
        } else if (hero.isNotEmpty()) {
            item { Hero(hero, vm, { open(listOf(it), 0, 0L) }, { launchTrailer(ctx, scope, vm, it) }, big = wide) }
        }
        if (cw.isNotEmpty()) item {
            val list = cw.map { it.toItem() }
            ItemRow("Continuer à regarder", list, { i -> open(list, i, cw[i].position) }, vm, wide = wide, progressOf = { prog[it.id] ?: 0f })
        }
        if (recentLive.isNotEmpty()) item { ItemRow("Chaînes récentes", recentLive, { i -> open(recentLive, i, 0L) }, vm, wide = false) }
        if (favs.isNotEmpty()) item { ItemRow("Ma liste", favs, { i -> open(favs, i, 0L) }, vm, wide = wide) }
    }
}

@Composable
fun Tile(sec: Section, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.size(92.dp), scale = CardDefaults.scale(focusedScale = 1.1f), border = CardDefaults.border(focusedBorder = focusBorder())) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(sec.icon, fontSize = 28.sp)
            Text(sec.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun BigCard(title: String, sub: String, image: String?, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, scale = CardDefaults.scale(focusedScale = 1.04f), border = CardDefaults.border(focusedBorder = focusBorder())) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 11.sp, color = Color.LightGray)
            }
            AsyncImage(
                model = image?.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(CircleShape)
            )
        }
    }
}

@Composable
fun Hero(items: List<ChannelEntity>, vm: MainViewModel, onWatch: (ChannelEntity) -> Unit, onTrailer: (ChannelEntity) -> Unit, big: Boolean = false) {
    var i by remember { mutableIntStateOf(0) }
    val lite = vm.settings.lite.on
    val bg = MaterialTheme.colorScheme.background
    LaunchedEffect(items, lite) { while (!lite && items.size > 1) { delay(8000); i = (i + 1) % items.size } }
    val cur = items.getOrNull(i % items.size) ?: return
    Box(Modifier.fillMaxWidth().height(if (big) 300.dp else 230.dp).clip(RoundedCornerShape(14.dp))) {
        AsyncImage(model = cur.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.85f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.7f)))))
        Column(Modifier.padding(24.dp).width(520.dp).align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cur.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cur.rating.isNotBlank() && cur.rating != "0") Text("★ ${cur.rating}", color = Color(0xFFF5C518))
                Text(cur.groupTitle, color = Color.LightGray)
                Text(if (cur.kind == "series") "Série" else "Film", color = Color.LightGray)
            }
            if (cur.plot.isNotBlank()) Text(cur.plot.take(170), maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(if (big) "Voir maintenant" else "Regarder") { onWatch(cur) }
                GlassButton("Bande-annonce") { onTrailer(cur) }
                GlassButton("Ma liste") { vm.toggleFav(cur) }
            }
        }
    }
}

@Composable
fun ItemRow(
    title: String?, items: List<ChannelEntity>, onOpen: (Int) -> Unit, vm: MainViewModel,
    wide: Boolean = false, progressOf: (ChannelEntity) -> Float = { 0f }
) {
    val ctx = LocalContext.current
    Column {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 48.dp, top = 8.dp, bottom = 8.dp, start = 4.dp)) {
            itemsIndexed(items) { i, c ->
                MediaCard(c, { onOpen(i) }, { vm.toggleFav(c); toast(ctx, "Ma liste mise à jour") }, wide, progressOf(c))
            }
        }
    }
}

@Composable
fun MediaCard(c: ChannelEntity, onClick: () -> Unit, onFav: () -> Unit, wide: Boolean = false, progress: Float = 0f) {
    val poster = c.kind == "movie" || c.kind == "series"
    Card(
        onClick = onClick, onLongClick = onFav,
        modifier = Modifier.width(if (wide) 190.dp else if (poster) 120.dp else 140.dp),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = focusBorder())
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = c.logo.ifBlank { null }, contentDescription = null,
                contentScale = if (poster || wide) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.height(if (wide) 107.dp else if (poster) 150.dp else 60.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
            )
            if (progress > 0f) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// =====================================================================================================
// Master / detail browser:  categories  ->  items  ->  preview window (live) or details (films / séries)
// =====================================================================================================

@Composable
fun CatRow(title: String, count: Int, selected: Boolean, level: Int = 0, isMain: Boolean = false, expanded: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth().padding(start = (level * 14).dp),
        scale = CardDefaults.scale(focusedScale = 1f), border = CardDefaults.border(focusedBorder = focusBorder())
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (isMain) (if (expanded) "▾ " else "▸ ") else if (selected) "● " else "") + title,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (isMain) 14.sp else 13.sp,
                modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                fontWeight = if (isMain || selected) FontWeight.Bold else FontWeight.Normal
            )
            if (count > 0) Text("$count", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

data class TreeRow(val title: String, val count: Int, val level: Int, val isMain: Boolean, val expanded: Boolean, val main: String, val leaf: String?)

private val KEYWORD_MAIN = listOf(
    Regex("(?i)sport|bein|ssc|match|football|soccer|ligue|liga|premier|champions|كأس|رياض|كرة") to "Sport",
    Regex("(?i)news|info|actualit|أخبار|اخبار") to "Actualités",
    Regex("(?i)kids|enfant|cartoon|أطفال|اطفال") to "Enfants",
    Regex("(?i)music|musique|clip|موسيق|اغاني") to "Musique",
    Regex("(?i)docu|discovery|nature|وثائقي") to "Documentaires",
    Regex("(?i)cin[eé]ma|film|movie|أفلام|افلام") to "Cinéma",
    Regex("(?i)serie|série|مسلسل|novela|drama") to "Séries TV",
    Regex("(?i)relig|islam|quran|coran|قرآن|اسلام") to "Religion"
)

/** "FR | Sport" -> ("FR", "Sport"); null when the title has no prefix. */
fun splitGroup(t: String): Pair<String, String>? {
    val s = t.trim().trimStart('[', '(', '★', '•', '*', ' ')
    for (sep in listOf("|", "»", ">", "::", ":", " - ", "/")) {
        val i = s.indexOf(sep)
        if (i in 2..18) {
            val main = s.substring(0, i).trim().trim(']', ')', ' ')
            val sub = s.substring(i + sep.length).trim()
            if (main.isNotEmpty() && sub.length >= 2) return main.uppercase() to sub
        }
    }
    return null
}

/** Main folders / sub folders, in the same order as the server lists the categories. */
fun buildTree(groups: List<GroupCount>, selected: String?, key: String, expandedMap: Map<String, Boolean>, keywords: Boolean): List<TreeRow> {
    val folders = LinkedHashMap<String, MutableList<Pair<String, GroupCount>>>()
    val order = ArrayList<Any>()
    for (g in groups) {
        val sp = splitGroup(g.groupTitle)
        val main = sp?.first ?: if (keywords) KEYWORD_MAIN.firstOrNull { it.first.containsMatchIn(g.groupTitle) }?.second else null
        if (main == null) order.add(g)
        else {
            val list = folders.getOrPut(main) { order.add(main); ArrayList() }
            list.add((sp?.second ?: g.groupTitle) to g)
        }
    }
    val rows = ArrayList<TreeRow>()
    for (o in order) {
        if (o is GroupCount) { rows.add(TreeRow(o.groupTitle, o.c, 0, false, false, "", o.groupTitle)); continue }
        val name = o as String
        val list = folders[name] ?: continue
        if (list.size == 1) { val g = list[0].second; rows.add(TreeRow(g.groupTitle, g.c, 0, false, false, "", g.groupTitle)); continue }
        val exp = expandedMap["$key|$name"] ?: list.any { it.second.groupTitle == selected }
        rows.add(TreeRow(name, list.sumOf { it.second.c }, 0, true, exp, name, null))
        if (exp) list.forEach { (label, g) -> rows.add(TreeRow(label, g.c, 1, false, false, name, g.groupTitle)) }
    }
    return rows
}

@Composable
fun ChannelRow(
    c: ChannelEntity, size: Int, highlighted: Boolean, lite: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, modifier: Modifier = Modifier
) {
    val poster = c.kind == "movie" || c.kind == "series"
    Card(
        onClick = onClick, onLongClick = onLong, modifier = modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = if (lite) 1f else 1.03f), border = CardDefaults.border(focusedBorder = focusBorder())
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = c.logo.ifBlank { null }, contentDescription = null,
                contentScale = if (poster) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.width(if (poster) (size * 0.7f).dp else size.dp).height(if (poster) (size * 1.2f).dp else size.dp).clip(RoundedCornerShape(4.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(
                    c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                    color = if (highlighted) MaterialTheme.colorScheme.primary else Color.White,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal
                )
                if (poster && c.rating.isNotBlank() && c.rating != "0") Text("★ ${c.rating}", fontSize = 11.sp, color = Color(0xFFF5C518))
            }
        }
    }
}

@Composable
fun BrowseScreen(vm: MainViewModel, kind: String, only: Regex?, stateKey: String, open: OpenFn) {
    val ctx = LocalContext.current
    val s = vm.session
    val st = vm.settings
    val iconSize = st.iconSize.int
    val lite = st.lite.on
    val hideAdult = st.hideAdult.on
    val isLiveKind = kind == "live" || kind == "radio"

    val groups by remember(kind, hideAdult) { vm.groups(kind) }.collectAsStateWithLifecycle(emptyList())
    val hist by remember(kind) { vm.historyOf(kind) }.collectAsStateWithLifecycle(emptyList())
    val favs by remember(kind) { vm.favoritesOf(kind) }.collectAsStateWithLifecycle(emptyList())
    val shown = remember(groups, only) { if (only != null) groups.filter { only.containsMatchIn(it.groupTitle) } else groups }
    val cat = vm.cats[stateKey]
    val rows = buildTree(shown, cat, stateKey, vm.expanded, isLiveKind)
    val groupItems by remember(kind, cat) {
        if (cat == null || cat == FAV_KEY || cat == RECENT_KEY) flowOf(emptyList<ChannelEntity>()) else vm.channels(kind, cat)
    }.collectAsStateWithLifecycle(emptyList())
    val list = remember(cat, favs, hist, groupItems) {
        when (cat) {
            null -> emptyList()
            FAV_KEY -> favs
            RECENT_KEY -> hist.map { it.toItem() }
            else -> groupItems
        }
    }
    val posMap = remember(hist) { hist.associate { it.itemId to it.position } }

    val catList = rememberLazyListState(vm.scrollPos["c$stateKey"] ?: 0)
    val itemList = rememberLazyListState(vm.scrollPos["i$stateKey"] ?: 0)
    val playFocus = remember { FocusRequester() }
    val firstRun = remember { booleanArrayOf(true) }

    LaunchedEffect(shown) { if (vm.cats[stateKey] == null && shown.isNotEmpty()) vm.cats[stateKey] = shown.first().groupTitle }
    LaunchedEffect(cat) {
        if (firstRun[0]) { firstRun[0] = false } else { runCatching { itemList.scrollToItem(0) } }
        // Stalker categories are filled on demand (does nothing for other sources)
        if (cat != null && (kind == "movie" || kind == "series") && cat != FAV_KEY && cat != RECENT_KEY) vm.loadCategory(kind, cat)
    }
    LaunchedEffect(Unit) {
        if (vm.restoreFocus) {
            vm.restoreFocus = false
            delay(250)
            runCatching { playFocus.requestFocus() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.scrollPos["c$stateKey"] = catList.firstVisibleItemIndex
            vm.scrollPos["i$stateKey"] = itemList.firstVisibleItemIndex
            if (s.previewMode && !s.fullscreen) s.stop() // leaving the section stops the preview
        }
    }

    if (shown.isEmpty() && favs.isEmpty() && hist.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                when (kind) {
                    "movie" -> "Aucun film. Ajoutez une source Xtream ou Stalker qui contient des films."
                    "series" -> "Aucune série. Ajoutez une source Xtream ou Stalker qui contient des séries."
                    "radio" -> "Aucune radio (les catégories nommées Radio sont détectées)."
                    else -> if (only != null) "Aucune catégorie sportive dans vos sources." else "Aucune chaîne. Ouvrez le menu puis Sources."
                }
            )
        }
        return
    }

    val pick: (Int) -> Unit = { i ->
        val c = list[i]
        if (isLiveKind) {
            if (s.item?.id == c.id && s.engineKind.isNotEmpty() && s.previewMode) s.fullscreen = true
            else if (st.clickMode.v == "full") s.play(list, i, full = true, preview = true)
            else s.play(list, i, preview = true)
        } else vm.selected[stateKey] = c
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- column 1: categories
        LazyColumn(
            Modifier.width(190.dp).fillMaxHeight(), state = catList,
            verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item { CatRow("★ Ma liste", favs.size, cat == FAV_KEY) { vm.cats[stateKey] = FAV_KEY } }
            item { CatRow("⏱ Récents", hist.size, cat == RECENT_KEY) { vm.cats[stateKey] = RECENT_KEY } }
            items(rows) { r ->
                if (r.isMain) CatRow(r.title, r.count, false, 0, true, r.expanded) { vm.expanded["$stateKey|${r.main}"] = !r.expanded }
                else CatRow(r.title, r.count, cat == r.leaf, r.level) { vm.cats[stateKey] = r.leaf ?: r.title }
            }
        }
        // ---- column 2: channels / films / séries
        LazyColumn(
            Modifier.width(300.dp).fillMaxHeight(), state = itemList,
            verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            if (list.isEmpty()) item {
                Text(if (vm.loadingCat.value) "Chargement..." else "Rien ici pour le moment", color = Color.Gray, modifier = Modifier.padding(8.dp))
            }
            itemsIndexed(list) { i, c ->
                val playing = isLiveKind && s.engineKind.isNotEmpty() && s.item?.id == c.id
                val chosen = vm.selected[stateKey]?.id == c.id
                ChannelRow(
                    c, iconSize, playing || chosen, lite,
                    onClick = { pick(i) },
                    onLong = { vm.toggleFav(c); toast(ctx, "Ma liste mise à jour") },
                    modifier = if (playing) Modifier.focusRequester(playFocus) else Modifier
                )
            }
            if (cat != null && vm.catHasMore["$kind|$cat"] == true) item {
                Button(onClick = { vm.loadCategory(kind, cat, true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.loadingCat.value) "Chargement..." else "Charger plus")
                }
            }
        }
        // ---- column 3: preview window or details
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (isLiveKind) LivePane(vm)
            else {
                val sel = vm.selected[stateKey]
                if (sel == null) Text("Sélectionnez un élément pour voir ses détails", color = Color.Gray)
                else if (kind == "movie") MovieDetail(vm, sel, posMap[sel.id] ?: 0L)
                else SeriesDetail(vm, sel)
            }
        }
    }
}

@Composable
fun LivePane(vm: MainViewModel) {
    val s = vm.session
    val ctx = LocalContext.current
    val cur = s.item
    val epg by produceState(emptyList<EpgEntity>(), cur?.id) {
        val t = cur?.tvgId
        value = if (!t.isNullOrBlank()) runCatching { vm.repo.nowNext(t) }.getOrDefault(emptyList()) else emptyList()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (cur != null && s.engineKind.isNotEmpty() && s.previewMode) {
            MiniPlayer(vm, Modifier.fillMaxWidth())
            Text(cur.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            epg.getOrNull(0)?.let { Text("En cours : ${it.title}", fontSize = 13.sp) }
            epg.getOrNull(1)?.let { Text("Ensuite : ${it.title}", fontSize = 12.sp, color = Color.Gray) }
            s.error?.let { Text(it, color = Color(0xFFFF8888), fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Plein écran") { s.fullscreen = true }
                GlassButton("Ma liste") { vm.toggleFav(cur); toast(ctx, "Ma liste mise à jour") }
                GlassButton("Arrêter") { s.stop() }
            }
            Text("OK sur la fenêtre = plein écran  -  OK long sur une chaîne = Ma liste", fontSize = 11.sp, color = Color.Gray)
        } else {
            Text("Choisissez une chaîne pour la regarder dans cette fenêtre", color = Color.Gray)
        }
    }
}

@Composable
fun MovieDetail(vm: MainViewModel, c: ChannelEntity, resume: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AsyncImage(
            model = c.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(130.dp).height(190.dp).clip(RoundedCornerShape(8.dp))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c.rating.isNotBlank() && c.rating != "0") Text("★ ${c.rating}", color = Color(0xFFF5C518))
                Text(c.groupTitle, color = Color.LightGray, fontSize = 13.sp)
            }
            if (c.plot.isNotBlank()) Text(c.plot, maxLines = 6, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            if (resume > 5000) Text("Reprendre à ${fmtTime(resume)}", color = Color(0xFF9FD3FF), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (resume > 5000) "Reprendre" else "Lecture") { vm.playFull(listOf(c), 0, resume) }
                if (resume > 5000) GlassButton("Depuis le début") { vm.playFull(listOf(c), 0, 0L) }
                GlassButton("Bande-annonce") { launchTrailer(ctx, scope, vm, c) }
                GlassButton("Ma liste") { vm.toggleFav(c); toast(ctx, "Ma liste mise à jour") }
            }
        }
    }
}

@Composable
fun SeriesDetail(vm: MainViewModel, c: ChannelEntity) {
    val ctx = LocalContext.current
    var loading by remember(c.id) { mutableStateOf(true) }
    val eps by produceState(emptyList<ChannelEntity>(), c.id) { value = vm.episodes(c); loading = false }
    val hist by remember { vm.historyOf("movie") }.collectAsStateWithLifecycle(emptyList())
    val pos = remember(hist) { hist.associate { it.itemId to it.position } }
    val seasons = remember(eps) { eps.map { it.groupTitle }.distinct() }
    var sel by remember(c.id) { mutableStateOf<String?>(null) }
    val season = sel ?: seasons.firstOrNull()
    val shownEps = remember(eps, season) { eps.filter { it.groupTitle == season } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = c.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(80.dp).height(115.dp).clip(RoundedCornerShape(6.dp))
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (c.rating.isNotBlank() && c.rating != "0") Text("★ ${c.rating}", color = Color(0xFFF5C518), fontSize = 12.sp)
                    if (eps.isNotEmpty()) Text("${seasons.size} saison(s) • ${eps.size} épisodes", color = Color.LightGray, fontSize = 12.sp)
                }
                if (c.plot.isNotBlank()) Text(c.plot, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                GlassButton("Ma liste") { vm.toggleFav(c); toast(ctx, "Ma liste mise à jour") }
            }
        }
        if (loading) Text("Chargement des épisodes...", color = Color.Gray)
        else if (eps.isEmpty()) Text("Aucun épisode trouvé", color = Color.Gray)
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { sn -> GlassButton(if (sn == season) "● $sn" else sn) { sel = sn } }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(shownEps) { _, e ->
                val p = pos[e.id] ?: 0L
                Card(
                    onClick = { vm.playFull(eps, eps.indexOf(e).coerceAtLeast(0), p) }, modifier = Modifier.fillMaxWidth(),
                    scale = CardDefaults.scale(focusedScale = 1f), border = CardDefaults.border(focusedBorder = focusBorder())
                ) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AsyncImage(
                            model = e.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(4.dp))
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (e.plot.isNotBlank()) Text(e.plot, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Color.LightGray)
                            if (e.ext.isNotBlank() && e.ext.contains(":")) Text(e.ext, fontSize = 11.sp, color = Color.Gray)
                        }
                        if (p > 5000) Text("▶ ${fmtTime(p)}", fontSize = 11.sp, color = Color(0xFF9FD3FF))
                    }
                }
            }
        }
    }
}

// =====================================================================================================
// Search, sources, settings, profiles
// =====================================================================================================

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, onFocus: () -> Unit = {}, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { M3Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    )
}

@Composable
fun SearchScreen(vm: MainViewModel, open: OpenFn) {
    val ctx = LocalContext.current
    val iconSize = vm.settings.iconSize.int
    val lite = vm.settings.lite.on
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(Unit) { RemoteBus.text.collect { q += it } }
    LaunchedEffect(q) { if (q.length >= 2) { delay(300); results = vm.search(q) } else results = emptyList() }
    Column(Modifier.width(620.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(q, { q = it }, "Chaînes, films, séries (le clavier du téléphone fonctionne aussi)")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(results) { i, c ->
                ChannelRow(c, iconSize, false, lite, onClick = { open(results, i, 0L) }, onLong = { vm.toggleFav(c); toast(ctx, "Ma liste mise à jour") })
            }
        }
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var mode by remember { mutableIntStateOf(0) } // 0 M3U, 1 Xtream, 2 Stalker
    var focused by remember { mutableIntStateOf(0) }
    val v = remember { mutableStateListOf("", "", "", "", "", "", "") } // 0 nom, 1 url, 2 user/mac, 3 mot de passe, 4 epg, 5 lien direct
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) vm.importM3uUri(uri) }
    LaunchedEffect(Unit) { RemoteBus.text.collect { v[focused] = v[focused] + it } }
    val idxs = when (mode) { 0 -> listOf(0, 1, 4); 1 -> listOf(0, 1, 2, 3); else -> listOf(0, 1, 2, 3, 6) }
    fun label(i: Int) = when (i) {
        0 -> "Nom de la source"
        1 -> listOf("Lien de la playlist (.m3u / .m3u8)", "Serveur (http://hôte:port)", "Adresse du portail (http://hôte/c/)")[mode]
        2 -> if (mode == 1) "Nom d'utilisateur" else "Adresse MAC (00:1A:79:xx:xx:xx)"
        3 -> if (mode == 1) "Mot de passe" else "Numéro de série STB (facultatif)"
        6 -> "Device ID (facultatif)"
        4 -> "Lien du guide XMLTV (facultatif)"
        else -> "Lien direct du flux (http://...)"
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(620.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
        item { Text("Ajouter une source", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("M3U", "Xtream Codes", "Stalker / MAG").forEachIndexed { i, n -> GlassButton(if (mode == i) "● $n" else n) { mode = i; focused = 0 } }
            }
        }
        idxs.forEach { i -> item(key = "f$i-$mode") { Field(v[i], { v[i] = it }, label(i), { focused = i }) } }
        if (mode == 2) item { Text("Les films et séries Stalker se chargent à l'ouverture de chaque catégorie.", fontSize = 12.sp, color = Color.Gray) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !busy, onClick = {
                    when (mode) { 0 -> vm.addM3u(v[0], v[1], v[4]); 1 -> vm.addXtream(v[0], v[1], v[2], v[3]); else -> vm.addStalker(v[0], v[1], v[2], v[3], v[6]) }
                }) { Text(if (busy) "Patientez..." else "Importer") }
                Button(enabled = !busy, onClick = {
                    try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { toast(ctx, "Sélecteur de fichiers indisponible : envoyez le fichier depuis la télécommande téléphone (Réglages)") }
                }) { Text("Fichier M3U ou codes (.txt)") }
            }
        }
        item { Text(status, fontSize = 13.sp) }

        item { Text("Lecture directe", style = MaterialTheme.typography.titleMedium) }
        item { Field(v[5], { v[5] = it }, label(5), { focused = 5 }) }
        item { GlassButton("Lire ce lien maintenant") { vm.playUrl(v[5]) } }

        item { Text("Vos sources", style = MaterialTheme.typography.titleMedium) }
        items(playlists) { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} (${p.type})", modifier = Modifier.width(190.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = { vm.setPlaylistEnabled(p, p.enabled == 0) }) { Text(if (p.enabled == 1) "Actif" else "Inactif") }
                Button(enabled = !busy, onClick = { vm.refreshEpg(p) }) { Text("Guide TV") }
                GlassButton("Supprimer") { vm.removePlaylist(p) }
            }
        }
    }
}

@Composable
fun OptButton(label: String, o: Opt) {
    Button(onClick = { o.cycle() }, modifier = Modifier.fillMaxWidth()) { Text("$label :  ${o.label}") }
}

@Composable
fun SettingsHeader(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val st = vm.settings
    val ctx = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val updStatus by vm.updateStatus.collectAsStateWithLifecycle()
    LazyColumn(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
        item { SettingsHeader("Apparence") }
        item { OptButton("Style d'interface", st.navStyle) }
        item {
            Text("Thème : ${theme.label}", fontSize = 13.sp, color = Color.LightGray)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AppTheme.values().toList()) { t -> GlassButton(if (t == theme) "● " + t.label else t.label) { vm.setTheme(t) } }
            }
        }
        item { OptButton("Taille des icônes de chaînes", st.iconSize) }
        item { OptButton("Mode léger (moins d'animations et de mémoire)", st.lite) }
        item { OptButton("Clic sur une chaîne", st.clickMode) }

        item { SettingsHeader("Lecteur") }
        item { OptButton("Lecteur par défaut", st.engine) }
        item { OptButton("Mise en mémoire tampon", st.buffer) }
        item { OptButton("Décodage matériel (VLC)", st.hw) }
        item { OptButton("Format d'image par défaut", st.aspect) }
        item { OptButton("Saut avant/arrière (films)", st.seek) }
        item { OptButton("Reprendre les films là où je me suis arrêté", st.resume) }
        item { OptButton("Épisode suivant automatique", st.autoNext) }
        item { OptButton("Taille des sous-titres", st.subSize) }
        item { OptButton("Amélioration de l'image (netteté, contraste, couleurs)", st.enhance) }
        item { Text("Fonctionne avec ExoPlayer. Désactivée automatiquement si l'appareil est trop lent.", fontSize = 11.sp, color = Color.Gray) }

        item { SettingsHeader("Contenu") }
        item { OptButton("Guide des programmes (EPG)", st.epgHours) }
        item { OptButton("Masquer les catégories adultes", st.hideAdult) }
        item { Button(onClick = { vm.clearHistory(); toast(ctx, "Historique effacé") }, modifier = Modifier.fillMaxWidth()) { Text("Effacer l'historique") } }
        item { Button(onClick = { vm.clearFavorites(); toast(ctx, "Ma liste vidée") }, modifier = Modifier.fillMaxWidth()) { Text("Vider Ma liste") } }

        item { SettingsHeader("Stalker / MAG") }
        item { OptButton("Modèle MAG émulé", st.stbModel) }

        item { SettingsHeader("Mises à jour") }
        item { Field(st.updateRepo, { st.setUpdateRepo(it) }, "Dépôt GitHub (utilisateur/dépôt) ou lien JSON") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Rechercher une mise à jour") { vm.checkUpdate(ctx) }
                vm.update.value?.let { u -> GlassButton("Installer ${u.name}", selected = true) { vm.installUpdate(ctx) } }
            }
        }
        item { Text(updStatus, fontSize = 12.sp, color = Color.LightGray) }
        item { vm.update.value?.let { u -> if (u.notes.isNotBlank()) Text(u.notes.take(300), fontSize = 11.sp, color = Color.Gray) } }

        item { SettingsHeader("Profil") }
        item {
            Text("Profil actuel : ${profile?.name ?: ""}${if (profile?.isKids == true) " (Enfant)" else ""}")
            Spacer(Modifier.height(6.dp))
            GlassButton("Changer de profil") { vm.select(null) }
        }

        item { SettingsHeader("Télécommande téléphone") }
        item {
            val url = RemoteInfo.url
            if (url.isBlank()) Text("Serveur indisponible (port occupé ou pas de réseau).")
            else {
                Text("Scannez avec un téléphone sur le même Wi-Fi : flèches, clavier, lien direct et envoi de fichier M3U.", color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Image(bitmap = remember(url) { qr(url, 320) }, contentDescription = "QR", modifier = Modifier.size(200.dp).background(Color.White).padding(8.dp))
                Text(url, fontSize = 12.sp)
            }
        }

        item { SettingsHeader("À propos") }
        item { Text("Stream TV 2.1  -  lecteurs : ExoPlayer (Media3) et VLC (libVLC)", fontSize = 12.sp, color = Color.Gray) }
    }
}

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val ps by vm.profiles.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ClockInline()
        Spacer(Modifier.height(12.dp))
        Text("${greeting()} ! Qui regarde ?", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ps.forEach { p ->
                Card(
                    onClick = { if (p.pin.isBlank()) vm.select(p) else pinFor = p }, onLongClick = { vm.deleteProfile(p) },
                    scale = CardDefaults.scale(focusedScale = 1.12f), border = CardDefaults.border(focusedBorder = focusBorder())
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(100.dp).background(theme.primary, RoundedCornerShape(16.dp)), Alignment.Center) {
                            Text(p.name.take(1).uppercase(), fontSize = 40.sp, color = Color.White)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(p.name + if (p.isKids) " (Enfant)" else "")
                        if (p.pin.isNotBlank()) Text("🔒", fontSize = 12.sp)
                    }
                }
            }
            Card(onClick = { adding = true }, scale = CardDefaults.scale(focusedScale = 1.12f), border = CardDefaults.border(focusedBorder = focusBorder())) {
                Box(Modifier.size(132.dp, 160.dp), Alignment.Center) { Text("+  Ajouter", fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("OK long sur un profil pour le supprimer", color = Color.Gray, fontSize = 12.sp)
    }
    pinFor?.let { p ->
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { pinFor = null }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Code PIN de ${p.name}")
                Field(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "PIN", secret = true)
                if (wrong) Text("Code incorrect", color = Color(0xFFFF6666))
                GlassButton("Valider") { if (pin == p.pin) { pinFor = null; vm.select(p) } else wrong = true }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var kids by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { adding = false }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nouveau profil")
                Field(name, { name = it }, "Nom")
                Field(pin, { pin = it.filter(Char::isDigit).take(8) }, "Code PIN (facultatif)", secret = true)
                GlassButton(if (kids) "Profil enfant : OUI (catégories adultes masquées)" else "Profil enfant : NON") { kids = !kids }
                GlassButton("Créer") { vm.addProfile(name, pin, kids); adding = false }
            }
        }
    }
}

// =====================================================================================================
// Codes tab: every saved access code in a table (expiry, channels, films, series) - tap one to go back to it
// =====================================================================================================

@Composable
fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.22f)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BarCell(n: Int, max: Int, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(if (n > 0) "$n" else "—", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (n > 0) Color.White else Color.Gray)
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(if (max > 0) (n.toFloat() / max).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(color))
        }
    }
}

@Composable
fun CodesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val codes by vm.codes.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) vm.importM3uUri(uri) }
    val primary = MaterialTheme.colorScheme.primary
    val active = playlists.filter { it.enabled == 1 }.map { it.id }.toSet()
    val now = System.currentTimeMillis()
    val maxCh = codes.maxOfOrNull { it.channels } ?: 0
    val maxMv = codes.maxOfOrNull { it.movies } ?: 0
    val maxSe = codes.maxOfOrNull { it.series } ?: 0
    val ok = codes.count { it.status == "ok" }
    val best = codes.firstOrNull { it.status == "ok" && (it.expiry == 0L || it.expiry > now) }
    val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("${codes.size} codes", Color(0xFF4DA3FF))
                Chip("$ok valides", Color(0xFF3DDC84))
                if (codes.size - ok > 0) Chip("${codes.size - ok} hors service", Color(0xFFFF6B6B))
                if (best != null) Chip("⭐ Meilleur : ${best.name} (${best.channels} chaînes)", Color(0xFFF5C518))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("📄 Importer un fichier .txt", selected = true) {
                    try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { toast(ctx, "Sélecteur indisponible : envoyez le fichier depuis le téléphone (Réglages)") }
                }
                GlassButton("↻ Tout retester") { if (!busy) vm.retestAll() }
            }
        }
        item { if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = Color(0xFFFFD27A)) }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.55f), primary.copy(alpha = 0.15f)))).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("CODE", Modifier.weight(2.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("EXPIRATION", Modifier.weight(1.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("CHAÎNES", Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("FILMS", Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("SÉRIES", Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("ACTIONS", Modifier.width(210.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (codes.isEmpty()) item { Text("Aucun code pour le moment. Importez un fichier .txt (portail + MAC, liens Xtream ou M3U) : le code avec le plus de chaînes est choisi automatiquement.", color = Color.Gray, fontSize = 13.sp) }
        itemsIndexed(codes) { i, c ->
            val isActive = c.playlistId != 0L && c.playlistId in active
            val days = if (c.expiry > 0) ((c.expiry - now) / 86_400_000L).toInt() else 0
            val (expText, expColor) = when {
                c.status == "?" -> "à tester" to Color(0xFF9AA4C0)
                c.expiry == 0L -> "Illimitée / inconnue" to Color(0xFF9AA4C0)
                c.expiry < now -> "Expiré le ${df.format(Date(c.expiry))}" to Color(0xFFFF6B6B)
                days <= 30 -> "${df.format(Date(c.expiry))}  (J-$days)" to Color(0xFFFFA94D)
                else -> "${df.format(Date(c.expiry))}  (J-$days)" to Color(0xFF3DDC84)
            }
            val typeColor = when (c.type) { "xtream" -> Color(0xFF4DA3FF); "stalker" -> Color(0xFFB26BFF); else -> Color(0xFF3DDC84) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (isActive) primary.copy(alpha = 0.20f) else if (i % 2 == 0) Color(0x16FFFFFF) else Color(0x0AFFFFFF))
                    .border(1.dp, if (isActive) primary else Color(0x22FFFFFF), RoundedCornerShape(14.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.weight(2.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(typeColor), Alignment.Center) {
                        Text(when (c.type) { "xtream" -> "X"; "stalker" -> "S"; else -> "M" }, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column {
                        Text((if (c == best) "⭐ " else "") + c.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (c.type) { "xtream" -> c.user; "stalker" -> c.mac; else -> "lien M3U" } +
                                (if (c.status == "ko") "  ✕ " + c.note else if (c.status != "ok" && c.status != "?") "  ✕ " + c.status else if (c.note.isNotBlank() && c.type == "xtream") "  " + c.note else ""),
                            fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(Modifier.weight(1.5f)) { Chip(expText, expColor) }
                BarCell(c.channels, maxCh, Color(0xFF4DA3FF), Modifier.weight(1.2f))
                BarCell(c.movies, maxMv, Color(0xFFFF8A4D), Modifier.weight(1.2f))
                BarCell(c.series, maxSe, Color(0xFFB26BFF), Modifier.weight(1.2f))
                Row(Modifier.width(210.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassButton(if (isActive) "● Actif" else "▶ Utiliser", selected = isActive) { if (!busy) vm.useCode(c) }
                    GlassButton("↻") { if (!busy) vm.retest(c) }
                    GlassButton("🗑") { vm.deleteCode(c) }
                }
            }
        }
    }
}
