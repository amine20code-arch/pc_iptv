package com.streamtv.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/** Extracts access codes (Xtream, Stalker portal + MAC, M3U links) from free text such as a .txt file. */
object CodeScanner {
    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val URL = Regex("https?://[^\\s\"'<>|,;]+")
    private val XQ = Regex("(?i)[?&]username=([^&\\s]+).*?[&?]password=([^&\\s]+)")
    private val XP = Regex("(?i)^(https?://[^/]+)/(?:live|movie|series)/([^/]+)/([^/]+)/")

    private fun hostOf(u: String) = Regex("^(https?://[^/?#]+)").find(u)?.value ?: u
    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    fun key(c: CodeEntity) = c.type + "|" + c.host.lowercase() + "|" + c.user + "|" + c.mac.uppercase() + "|" + (if (c.type == "m3u") c.host else "")

    fun parse(text: String, model: String): List<CodeEntity> {
        val out = LinkedHashMap<String, CodeEntity>()
        fun add(c: CodeEntity) { if (out.size < 300) out.putIfAbsent(key(c), c) }
        fun short(h: String) = h.removePrefix("http://").removePrefix("https://")
        var lastPortal: String? = null
        var lastPortalLine = -100
        text.lines().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val urls = URL.findAll(line).map { it.value.trimEnd('.', ')', ']', '}') }.toList()
            val macs = MAC.findAll(line).map { it.value.uppercase() }.toList()
            val rest = ArrayList<String>()
            for (u in urls) {
                val q = XQ.find(u)
                val p = XP.find(u)
                when {
                    q != null -> add(CodeEntity(type = "xtream", name = short(hostOf(u)), host = hostOf(u), user = dec(q.groupValues[1]), pass = dec(q.groupValues[2])))
                    p != null -> add(CodeEntity(type = "xtream", name = short(p.groupValues[1]), host = p.groupValues[1], user = dec(p.groupValues[2]), pass = dec(p.groupValues[3])))
                    else -> rest.add(u)
                }
            }
            if (macs.isNotEmpty()) {
                val portal = rest.firstOrNull() ?: if (idx - lastPortalLine <= 8) lastPortal else null
                if (portal != null) macs.forEach { m ->
                    val h = normalizeHost(portal)
                    add(CodeEntity(type = "stalker", name = short(h), host = h, mac = m, opts = "||$model"))
                }
                if (rest.isNotEmpty()) { lastPortal = rest.first(); lastPortalLine = idx }
            } else {
                for (u in rest) {
                    if (u.contains(".m3u", true) || u.contains("type=m3u", true) || u.contains("output=ts", true)) {
                        add(CodeEntity(type = "m3u", name = short(hostOf(u)), host = u))
                    } else {
                        lastPortal = u; lastPortalLine = idx
                        val tokens = line.substringAfter(u).trim().split(Regex("[\\s|:;,]+")).filter { it.isNotBlank() }
                        if (tokens.size >= 2 && !MAC.containsMatchIn(line)) {
                            add(CodeEntity(type = "xtream", name = short(hostOf(u)), host = hostOf(u), user = tokens[0], pass = tokens[1]))
                        }
                    }
                }
            }
        }
        return out.values.toList()
    }
}

object CodeTester {
    /** Tests a code against its server and returns the updated record (counts, expiry, status). */
    suspend fun test(c: CodeEntity): CodeEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            when (c.type) {
                "xtream" -> {
                    val p = XtreamClient(c.host, c.user, c.pass).probe()
                    val ok = p.status.isBlank() || p.status.equals("Active", true)
                    c.copy(channels = p.channels, movies = p.movies, series = p.series, expiry = p.expiry,
                        status = if (ok) "ok" else p.status.lowercase(), note = p.note, testedAt = now)
                }
                "stalker" -> {
                    val p = StalkerClient(c.host, c.mac, c.opts).probe()
                    c.copy(channels = p.channels, movies = p.movies, series = p.series, expiry = p.expiry,
                        status = if (p.channels > 0) "ok" else "ko", note = p.note, testedAt = now)
                }
                else -> {
                    var ch = 0; var mv = 0; var se = 0
                    Net.open(c.host).use { r ->
                        M3uParser.parse(r.body!!.charStream().buffered(), 0) { list ->
                            for (e in list) when (e.kind) { "live", "radio" -> ch++; "movie" -> mv++; "series" -> se++ }
                        }
                    }
                    c.copy(channels = ch, movies = mv, series = se, status = if (ch + mv + se > 0) "ok" else "ko", note = "", testedAt = now)
                }
            }
        } catch (e: Exception) {
            c.copy(status = "ko", note = (e.message ?: "erreur").take(60), testedAt = now)
        }
    }
}
