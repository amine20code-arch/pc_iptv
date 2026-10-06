package com.streamtv.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

class Repository(val db: AppDb) {
    val dao = db.dao()
    @Volatile var lastPid = 0L

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }

    // ---------------------------------------------------------------- M3U (link / local file / phone upload)

    private suspend fun importM3u(pid: Long, reader: BufferedReader): Int {
        var n = 0
        M3uParser.parse(reader, pid) { dao.insertItems(it); n += it.size }
        return n
    }

    suspend fun addM3u(name: String, url: String, epg: String, hours: Int): Result<Int> = io {
        val clean = url.trim()
        if (!clean.startsWith("http", true)) error("Le lien doit commencer par http:// ou https://")
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "m3u", url = clean, epgUrl = epg))
        lastPid = pid
        var n = 0
        try {
            Net.open(clean).use { r -> n = importM3u(pid, r.body!!.charStream().buffered()) }
            if (n == 0) error("Aucune chaîne trouvée dans cette liste")
        } catch (e: Exception) { rollback(pid); throw e }
        if (epg.isNotBlank() && hours > 0) runCatching { loadEpg(pid, epg, hours) }
        n
    }

    suspend fun addM3uStream(name: String, input: InputStream): Result<Int> = io {
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "m3u", url = "fichier:$name"))
        lastPid = pid
        try {
            val n = input.use { importM3u(pid, it.bufferedReader()) }
            if (n == 0) error("Aucune chaîne trouvée dans ce fichier")
            n
        } catch (e: Exception) { rollback(pid); throw e }
    }

    // ---------------------------------------------------------------- Xtream Codes (live + films + séries in parallel)

    suspend fun addXtream(name: String, server: String, user: String, pass: String, hours: Int, progress: (String) -> Unit): Result<Int> = io {
        if (server.isBlank() || user.isBlank() || pass.isBlank()) error("Serveur, utilisateur et mot de passe sont requis")
        val c = XtreamClient(server, user, pass)
        c.checkAuth()
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "xtream", url = c.base, username = c.user, password = c.pass))
        lastPid = pid
        val live = AtomicInteger(); val mov = AtomicInteger(); val ser = AtomicInteger()
        fun report() = progress("Chaînes : ${live.get()}   Films : ${mov.get()}   Séries : ${ser.get()}")
        try {
            coroutineScope {
                val dl = async { c.live(pid) { dao.insertItems(it); live.addAndGet(it.size); report() } }
                val dm = async { runCatching { c.movies(pid) { dao.insertItems(it); mov.addAndGet(it.size); report() } } }
                val ds = async { runCatching { c.series(pid) { dao.insertItems(it); ser.addAndGet(it.size); report() } } }
                dl.await(); dm.await(); ds.await()
            }
        } catch (e: Exception) { rollback(pid); throw e }
        if (hours > 0) runCatching { loadEpg(pid, c.epgUrl(), hours) }
        live.get() + mov.get() + ser.get()
    }

    // ---------------------------------------------------------------- Stalker / MAG

    suspend fun addStalker(name: String, portal: String, mac: String, opts: String, progress: (String) -> Unit): Result<Int> = io {
        val m = mac.trim()
        if (!Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(m)) error("Adresse MAC invalide (format 00:1A:79:XX:XX:XX)")
        if (portal.isBlank()) error("Adresse du portail requise")
        val c = StalkerClient(portal, m, opts)
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "stalker", url = normalizeHost(portal), username = m, password = opts))
        lastPid = pid
        var n = 0
        try {
            progress("Connexion au portail...")
            c.channels(pid) { dao.insertItems(it); n += it.size; progress("Chaînes : $n") }
            if (n == 0) error("Aucune chaîne reçue : vérifiez l'adresse MAC et le portail")
        } catch (e: Exception) { rollback(pid); throw e }
        // films / séries: only the category names are stored now; items are loaded on demand when a category is opened
        runCatching {
            c.categories("vod").forEach { cat ->
                dao.insertItems(listOf(ChannelEntity(playlistId = pid, kind = "cat_movie", name = cat.title, groupTitle = cat.title, streamUrl = "stalkercat:vod:${cat.id}", ext = "1/0")))
            }
        }
        runCatching {
            c.categories("series").forEach { cat ->
                dao.insertItems(listOf(ChannelEntity(playlistId = pid, kind = "cat_series", name = cat.title, groupTitle = cat.title, streamUrl = "stalkercat:series:${cat.id}", ext = "1/0")))
            }
        }
        n
    }

    /**
     * Loads the next pages (up to [pages]) of a Stalker category into the database.
     * Returns true when more pages remain.
     */
    suspend fun loadStalkerCategory(kind: String, group: String, pages: Int = 3, force: Boolean = false): Result<Boolean> = io {
        var more = false
        for (ph in dao.placeholders("cat_$kind", group)) {
            val p = dao.playlist(ph.playlistId) ?: continue
            if (p.type != "stalker") continue
            val parts = ph.ext.split("/")
            var next = parts.getOrNull(0)?.toIntOrNull() ?: 1
            var total = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (!force && next > 1) { if (total == 0 || next <= total) more = true; continue } // already loaded once
            if (total in 1 until next) continue
            val spec = ph.streamUrl.removePrefix("stalkercat:")
            val type = spec.substringBefore(':')
            val id = spec.substringAfter(':')
            val client = StalkerClient(p.url, p.username, p.password)
            var done = 0
            while (done < pages && (total == 0 || next <= total)) {
                val (items, tp) = client.page(type, id, group, next, p.id)
                if (tp > 0) total = tp
                if (items.isNotEmpty()) dao.insertItems(items)
                next++; done++
                if (items.isEmpty()) { total = next - 1; break }
            }
            dao.setExt(ph.id, "$next/$total")
            if (total == 0 || next <= total) more = true
        }
        more
    }

    private suspend fun rollback(pid: Long) { dao.deleteItems(pid); dao.deleteEpg(pid); dao.deletePlaylist(pid); dao.unlinkCodes(pid) }

    suspend fun removePlaylist(p: PlaylistEntity) = withContext(Dispatchers.IO) { rollback(p.id) }

    // ---------------------------------------------------------------- code library

    suspend fun registerCode(c: CodeEntity): Long {
        val k = CodeScanner.key(c)
        val ex = dao.codesOnce().firstOrNull { CodeScanner.key(it) == k }
        return if (ex != null) { dao.updateCode(ex.copy(playlistId = c.playlistId)); ex.id } else dao.insertCode(c)
    }

    /** Makes a saved code the active source: re-enables its playlist when already imported, otherwise imports it. */
    suspend fun useCode(c: CodeEntity, hours: Int, progress: (String) -> Unit): Result<Int> = io {
        val existing = if (c.playlistId != 0L) dao.playlist(c.playlistId) else null
        if (existing != null) {
            dao.setEnabled(existing.id, 1); dao.disableOthers(existing.id)
            return@io dao.countItems(existing.id)
        }
        val n = when (c.type) {
            "xtream" -> addXtream(c.name, c.host, c.user, c.pass, hours, progress)
            "stalker" -> addStalker(c.name, c.host, c.mac, c.opts, progress)
            else -> addM3u(c.name, c.host, "", hours)
        }.getOrThrow()
        val pid = lastPid
        dao.updateCode(c.copy(playlistId = pid))
        dao.disableOthers(pid)
        n
    }

    // ---------------------------------------------------------------- EPG

    private suspend fun loadEpg(pid: Long, url: String, hours: Int): Int {
        var n = 0
        Net.open(url).use { r ->
            var s: InputStream = BufferedInputStream(r.body!!.byteStream())
            if (url.contains(".gz")) s = GZIPInputStream(s)
            dao.deleteEpg(pid)
            XmltvParser.parse(s, pid, hours) { dao.insertEpg(it); n += it.size }
        }
        return n
    }

    suspend fun refreshEpg(p: PlaylistEntity, hours: Int): Result<Int> = io {
        val url = when {
            p.epgUrl.isNotBlank() -> p.epgUrl
            p.type == "xtream" -> XtreamClient(p.url, p.username, p.password).epgUrl()
            else -> error("Cette source n'a pas de guide des programmes")
        }
        loadEpg(p.id, url, hours)
    }

    // ---------------------------------------------------------------- playback helpers

    /** Turns a stored item into a playable stream (Stalker links are created per play). */
    suspend fun resolve(item: ChannelEntity): Stream = withContext(Dispatchers.IO) {
        val u = item.streamUrl
        if (u.startsWith("stalker:") || u.startsWith("stalkervod:") || u.startsWith("stalkerep:")) {
            val p = dao.playlist(item.playlistId) ?: error("Source supprimée")
            val c = StalkerClient(p.url, p.username, p.password)
            val link = when {
                u.startsWith("stalker:") -> c.resolve(u.removePrefix("stalker:"))
                u.startsWith("stalkervod:") -> c.resolve(u.removePrefix("stalkervod:"), "vod")
                else -> {
                    val rest = u.removePrefix("stalkerep:")
                    c.resolve(rest.substringAfter(':'), "vod", rest.substringBefore(':'))
                }
            }
            val model = p.password.split("|").getOrNull(2)?.ifBlank { null } ?: "MAG250"
            Stream(link, MAG_UA, mapOf("Cookie" to "mac=${p.username}; stb_lang=en; timezone=Europe%2FLondon", "X-User-Agent" to "Model: $model; Link: WiFi"))
        } else Stream(u, VLC_UA)
    }

    suspend fun episodes(item: ChannelEntity): List<ChannelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val u = item.streamUrl
            when {
                u.startsWith("localseries:") ->
                    dao.localEpisodes(item.playlistId, u.removePrefix("localseries:")).map {
                        it.copy(groupTitle = "Saison " + (it.rating.toIntOrNull() ?: 1))
                    }
                u.startsWith("stalkerser:") -> {
                    val p = dao.playlist(item.playlistId) ?: return@runCatching emptyList<ChannelEntity>()
                    StalkerClient(p.url, p.username, p.password).episodes(item)
                }
                else -> {
                    val p = dao.playlist(item.playlistId) ?: return@runCatching emptyList<ChannelEntity>()
                    XtreamClient(p.url, p.username, p.password).episodes(u.removePrefix("series:"), p.id)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun trailer(item: ChannelEntity): String? = withContext(Dispatchers.IO) {
        if (item.kind != "movie" || item.ext.isBlank()) return@withContext null
        val p = dao.playlist(item.playlistId)?.takeIf { it.type == "xtream" } ?: return@withContext null
        XtreamClient(p.url, p.username, p.password).trailer(item.ext)
    }

    suspend fun nowNext(tvgId: String): List<EpgEntity> = dao.nowNext(tvgId, System.currentTimeMillis())

    /** (previous, current, next) programme of a channel, any of them may be missing. */
    suspend fun around(tvgId: String): Triple<EpgEntity?, EpgEntity?, EpgEntity?> {
        val now = System.currentTimeMillis()
        val up = dao.nowNext(tvgId, now)
        return Triple(dao.previous(tvgId, now), up.firstOrNull { it.start <= now }, up.firstOrNull { it.start > now })
    }
}
