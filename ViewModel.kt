package com.streamtv.iptv

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val FAV_KEY = "__fav__"
const val RECENT_KEY = "__recent__"

fun HistoryEntity.toItem() = ChannelEntity(id = itemId, playlistId = playlistId, kind = kind, name = name, logo = logo, streamUrl = url, tvgId = tvgId)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(AppDb.get(app))
    private val dao = repo.dao
    val settings = AppSettings(app)
    private val prefs = app.getSharedPreferences("ui", 0)
    private val adult = Regex("(?i)adult|xxx|porn|18\\+|sex")

    /** One playback session shared by the preview window and the fullscreen player. */
    val session = PlaybackSession(app, settings, repo) { c, p, d -> saveProgress(c, p, d) }

    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val profile = MutableStateFlow<ProfileEntity?>(null)
    val profiles = dao.profiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val codes = dao.codes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val update = mutableStateOf<UpdateInfo?>(null)
    val updateStatus = MutableStateFlow("")
    val itemCount = dao.count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val theme = MutableStateFlow(runCatching { AppTheme.valueOf(prefs.getString("theme", "MIDNIGHT")!!) }.getOrDefault(AppTheme.MIDNIGHT))

    // UI state kept here so it survives leaving / re-entering a screen (e.g. fullscreen and back)
    var section by mutableStateOf(Section.LIVE)
    val cats = mutableStateMapOf<String, String>()
    val expanded = mutableStateMapOf<String, Boolean>()
    val selected = mutableStateMapOf<String, ChannelEntity>()
    val scrollPos = HashMap<String, Int>()
    var restoreFocus by mutableStateOf(false)

    val history: Flow<List<HistoryEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.history(p.id) }
    val favorites: Flow<List<ChannelEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favorites(p.id) }

    init { viewModelScope.launch { if (dao.profileCount() == 0) dao.insertProfile(ProfileEntity(name = "Default")) } }

    fun historyOf(kind: String): Flow<List<HistoryEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.historyOf(p.id, kind) }
    fun favoritesOf(kind: String): Flow<List<ChannelEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favoritesOf(p.id, kind) }

    fun setTheme(t: AppTheme) { theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun select(p: ProfileEntity?) { session.stop(); profile.value = p }
    fun addProfile(name: String, pin: String, kids: Boolean) = viewModelScope.launch { dao.insertProfile(ProfileEntity(name = name.ifBlank { "Profile" }, pin = pin, isKids = kids)) }
    fun deleteProfile(p: ProfileEntity) = viewModelScope.launch { if (profiles.value.size > 1) dao.deleteProfile(p.id) }

    /** Parental control: kids profiles (and the global option) never see adult categories. */
    fun groups(kind: String): Flow<List<GroupCount>> = combine(dao.groups(kind, "cat_$kind"), profile) { g, p ->
        if (p?.isKids == true || settings.hideAdult.on) g.filterNot { adult.containsMatchIn(it.groupTitle) } else g
    }
    fun channels(kind: String, group: String) = dao.byGroup(kind, group, 5000)
    suspend fun featured(kind: String) = dao.featured(kind, 8)
    suspend fun search(q: String) = dao.search(q, 80)
    suspend fun episodes(i: ChannelEntity) = repo.episodes(i)
    suspend fun trailer(i: ChannelEntity) = repo.trailer(i)

    fun toggleFav(i: ChannelEntity) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        if (dao.isFav(p.id, i.id) > 0) dao.removeFav(p.id, i.id) else dao.addFav(FavEntity(p.id, i.id))
    }

    fun saveProgress(i: ChannelEntity, pos: Long, dur: Long) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        dao.upsertHistory(HistoryEntity(p.id, i.id, i.playlistId, i.kind, i.name, i.logo, i.streamUrl, i.tvgId, pos, dur, System.currentTimeMillis()))
    }

    fun clearHistory() = viewModelScope.launch { profile.value?.let { dao.clearHistory(it.id) } }
    fun clearFavorites() = viewModelScope.launch { profile.value?.let { dao.clearFavs(it.id) } }

    // ---- playback entry points
    fun playFull(list: List<ChannelEntity>, idx: Int, resume: Long) {
        session.play(list, idx, if (settings.resume.on) resume else 0L, full = true, preview = false)
    }

    fun exitFullscreen() {
        if (session.previewMode) { session.saveNow(); session.fullscreen = false; restoreFocus = true }
        else session.stop()
    }

    // ---- sources
    private fun runImport(block: suspend () -> Result<Int>) = viewModelScope.launch {
        busy.value = true; status.value = "Importation en cours... (les grandes listes peuvent prendre une minute)"
        block().onSuccess { status.value = "Importation terminée : $it éléments" }
            .onFailure { status.value = "Échec : ${it.message ?: "serveur injoignable"}. Les données déjà enregistrées restent disponibles." }
        busy.value = false
    }
    private val progress: (String) -> Unit = { status.value = it }
    fun addM3u(n: String, url: String, epg: String) = runImport {
        repo.addM3u(n.ifBlank { "Playlist" }, url, epg.trim(), settings.epgHours.int).also { r ->
            if (r.isSuccess) registerManual(CodeEntity(type = "m3u", name = n.ifBlank { "Playlist" }, host = url.trim(), playlistId = repo.lastPid))
        }
    }

    fun addXtream(n: String, s: String, u: String, p: String) = runImport {
        repo.addXtream(n.ifBlank { "Xtream" }, s, u, p, settings.epgHours.int, progress).also { r ->
            if (r.isSuccess) registerManual(CodeEntity(type = "xtream", name = normalizeHost(s).removePrefix("http://").removePrefix("https://"), host = normalizeHost(s), user = u.trim(), pass = p.trim(), playlistId = repo.lastPid))
        }
    }

    fun addStalker(n: String, portal: String, mac: String, serial: String, deviceId: String) = runImport {
        val opts = "${serial.trim()}|${deviceId.trim()}|${settings.stbModel.v}"
        repo.addStalker(n.ifBlank { "Stalker" }, portal, mac, opts, progress).also { r ->
            if (r.isSuccess) registerManual(CodeEntity(type = "stalker", name = normalizeHost(portal).removePrefix("http://").removePrefix("https://"), host = normalizeHost(portal), mac = mac.trim().uppercase(), opts = opts, playlistId = repo.lastPid))
        }
    }

    /** Every manually added source also lands in the code library; its counts / expiry are measured in the background. */
    private fun registerManual(c: CodeEntity) {
        viewModelScope.launch {
            val id = repo.registerCode(c)
            val saved = repo.dao.code(id) ?: return@launch
            repo.dao.updateCode(CodeTester.test(saved))
        }
    }

    /** M3U playlist file or a .txt file full of codes (from the file picker or uploaded from the phone). */
    fun importM3uUri(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Fichier"
        if (name.endsWith(".txt", true)) {
            viewModelScope.launch {
                val text = withContextIo { getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                if (text == null) status.value = "Fichier illisible" else importCodesText(text)
            }
        } else runImport {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
            if (input == null) Result.failure(IllegalStateException("Fichier illisible")) else repo.addM3uStream(name, input)
        }
    }

    fun importPath(path: String, name: String) {
        if (name.endsWith(".txt", true)) {
            viewModelScope.launch {
                val text = withContextIo { java.io.File(path).readText() }
                importCodesText(text)
            }
        } else runImport { repo.addM3uStream(name.ifBlank { "Fichier M3U" }, java.io.File(path).inputStream()) }
    }

    private suspend fun <T> withContextIo(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    // ---- code library
    fun importCodesText(text: String) {
        viewModelScope.launch {
            busy.value = true; status.value = "Analyse du fichier..."
            val cands = CodeScanner.parse(text, settings.stbModel.v)
            if (cands.isEmpty()) { status.value = "Aucun code reconnu dans ce fichier"; busy.value = false; return@launch }
            val have = repo.dao.codesOnce().map { CodeScanner.key(it) }.toSet()
            val fresh = cands.filter { CodeScanner.key(it) !in have }
            if (fresh.isEmpty()) { status.value = "Ces ${cands.size} codes sont déjà dans la bibliothèque"; busy.value = false; return@launch }
            val saved = fresh.map { it.copy(id = repo.dao.insertCode(it)) }
            var done = 0
            val sem = Semaphore(4)
            val tested = coroutineScope {
                saved.map { c ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val r = CodeTester.test(c)
                            repo.dao.updateCode(r)
                            done++
                            status.value = "Test des codes : $done/${saved.size}"
                            r
                        }
                    }
                }.awaitAll()
            }
            val now = System.currentTimeMillis()
            val good = tested.filter { it.status == "ok" }
            val best = good.filter { it.expiry == 0L || it.expiry > now }.maxByOrNull { it.channels } ?: good.maxByOrNull { it.channels }
            if (best == null) { status.value = "Aucun code valide parmi ${saved.size} (voir l'onglet Codes)"; busy.value = false; return@launch }
            status.value = "Meilleur code : ${best.name} (${best.channels} chaînes). Importation..."
            useCodeInternal(best)
            busy.value = false
        }
    }

    private suspend fun useCodeInternal(c: CodeEntity) {
        repo.useCode(c, settings.epgHours.int, progress)
            .onSuccess { status.value = "Code actif : ${c.name} ($it éléments)" }
            .onFailure { status.value = "Échec : ${it.message ?: "serveur injoignable"}" }
    }

    fun useCode(c: CodeEntity) { viewModelScope.launch { busy.value = true; useCodeInternal(c); busy.value = false } }

    fun retest(c: CodeEntity) {
        viewModelScope.launch {
            busy.value = true; status.value = "Test de ${c.name}..."
            repo.dao.updateCode(CodeTester.test(c))
            status.value = "Test terminé"; busy.value = false
        }
    }

    fun retestAll() {
        viewModelScope.launch {
            busy.value = true
            val all = repo.dao.codesOnce()
            val sem = Semaphore(3)
            var done = 0
            coroutineScope {
                all.map { c -> async(Dispatchers.IO) { sem.withPermit { repo.dao.updateCode(CodeTester.test(c)); done++; status.value = "Nouveau test : $done/${all.size}" } } }.awaitAll()
            }
            status.value = "Tous les codes ont été retestés"; busy.value = false
        }
    }

    fun deleteCode(c: CodeEntity) {
        viewModelScope.launch {
            repo.dao.deleteCode(c.id)
            if (c.playlistId != 0L) repo.dao.playlist(c.playlistId)?.let { repo.removePlaylist(it) }
        }
    }

    fun setPlaylistEnabled(p: PlaylistEntity, on: Boolean) { viewModelScope.launch { repo.dao.setEnabled(p.id, if (on) 1 else 0) } }

    // ---- in-app updates
    fun checkUpdate(ctx: Context, silent: Boolean = false) {
        viewModelScope.launch {
            updateStatus.value = "Recherche de mises à jour..."
            runCatching { Updater.check(ctx, settings.updateRepo) }
                .onSuccess { u ->
                    update.value = u
                    updateStatus.value = if (u == null) "L'application est à jour (version ${Updater.currentBuild(ctx)})" else "Nouvelle version disponible : ${u.name}"
                }
                .onFailure { updateStatus.value = if (silent) "" else "Vérification impossible : ${it.message}" }
        }
    }

    fun installUpdate(ctx: Context) {
        val u = update.value ?: return
        viewModelScope.launch {
            if (!Updater.ensureInstallPermission(ctx)) { updateStatus.value = "Autorisez l'installation depuis cette application, puis réessayez"; return@launch }
            runCatching {
                val f = Updater.download(ctx, u.apkUrl) { updateStatus.value = "Téléchargement : $it %" }
                updateStatus.value = "Installation..."
                Updater.install(ctx, f)
            }.onFailure { updateStatus.value = "Échec de la mise à jour : ${it.message}" }
        }
    }

    fun removePlaylist(p: PlaylistEntity) = viewModelScope.launch { repo.removePlaylist(p) }

    fun refreshEpg(p: PlaylistEntity) = viewModelScope.launch {
        busy.value = true; status.value = "Mise à jour du guide pour ${p.name}..."
        repo.refreshEpg(p, settings.epgHours.int.coerceAtLeast(6))
            .onSuccess { status.value = "Guide mis à jour ($it programmes)" }
            .onFailure { status.value = "Guide indisponible : ${it.message}" }
        busy.value = false
    }

    // ---- Stalker categories (films / séries) are filled on demand
    val loadingCat = mutableStateOf(false)
    val catHasMore = mutableStateMapOf<String, Boolean>()

    fun loadCategory(kind: String, group: String, force: Boolean = false) {
        if (loadingCat.value) return
        viewModelScope.launch {
            loadingCat.value = true
            repo.loadStalkerCategory(kind, group, 3, force)
                .onSuccess { catHasMore["$kind|$group"] = it }
                .onFailure { status.value = "Chargement impossible : ${it.message}" }
            loadingCat.value = false
        }
    }

    /** Plays a raw link right away (movie-like extensions get a seek bar, everything else is treated as live). */
    fun playUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        val isFile = Regex("(?i)\\.(mp4|mkv|avi|mov|webm|m4v)(\\?.*)?$").containsMatchIn(u)
        session.play(listOf(ChannelEntity(playlistId = 0, kind = if (isFile) "movie" else "live", name = "Lecture directe", streamUrl = u)), 0, full = true, preview = false)
    }

    override fun onCleared() { session.release(); super.onCleared() }
}
