package com.streamtv.iptv

import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStreamReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

const val VLC_UA = "VLC/3.0.20 LibVLC/3.0.20"
const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

/** What the player needs to open a stream: URL + the User-Agent / headers the server expects. */
data class Stream(val url: String, val ua: String, val headers: Map<String, String> = emptyMap())

/** Accepts "host:port", "http://host/c/", "http://host/player_api.php?..." and returns "http://host:port". */
fun normalizeHost(h: String): String {
    var x = h.trim()
    if (x.isEmpty()) return x
    if (!x.startsWith("http://", true) && !x.startsWith("https://", true)) x = "http://$x"
    x = x.substringBefore("/player_api.php").substringBefore("/portal.php").substringBefore("/server/load.php")
    x = x.trimEnd('/')
    if (x.endsWith("/c")) x = x.removeSuffix("/c")
    return x.trimEnd('/')
}

fun enc(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()

    fun open(url: String, headers: Map<String, String> = emptyMap()): Response {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        val r = http.newCall(rb.build()).execute()
        if (!r.isSuccessful) { r.close(); error("HTTP ${r.code}") }
        return r
    }
}

fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString

class XtreamClient(base0: String, user0: String, pass0: String) {
    val base = normalizeHost(base0)
    val user = user0.trim()
    val pass = pass0.trim()
    private val eu = enc(user)
    private val ep = enc(pass)
    private fun api(action: String, extra: String = "") = "$base/player_api.php?username=$eu&password=$ep&action=$action$extra"
    fun epgUrl() = "$base/xmltv.php?username=$eu&password=$ep"

    /** Throws a readable error when the account is rejected / expired. */
    fun checkAuth() {
        Net.open("$base/player_api.php?username=$eu&password=$ep").use { r ->
            val o = JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
            val ui = o.getAsJsonObject("user_info") ?: error("Réponse inattendue du serveur")
            val auth = ui.str("auth")
            if (auth == "0") error("Identifiants refusés par le serveur")
            val status = ui.str("status")
            if (status != null && !status.equals("Active", true)) error("Compte $status")
        }
    }

    private suspend fun objects(url: String, f: suspend (Map<String, String>) -> Unit) {
        Net.open(url).use { r ->
            JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    val m = HashMap<String, String>()
                    j.beginObject()
                    while (j.hasNext()) {
                        val k = j.nextName()
                        when (j.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> m[k] = j.nextString()
                            JsonToken.BOOLEAN -> m[k] = j.nextBoolean().toString()
                            JsonToken.NULL -> j.nextNull()
                            else -> j.skipValue()
                        }
                    }
                    j.endObject()
                    f(m)
                }
                j.endArray()
            }
        }
    }

    private suspend fun pull(listAction: String, catAction: String, onBatch: suspend (List<ChannelEntity>) -> Unit, mk: (Map<String, String>, String) -> ChannelEntity) {
        val cats = HashMap<String, String>()
        runCatching { objects(api(catAction)) { cats[it["category_id"] ?: ""] = it["category_name"] ?: "Other" } }
        val batch = ArrayList<ChannelEntity>()
        objects(api(listAction)) { m ->
            batch += mk(m, cats[m["category_id"]] ?: "Other")
            if (batch.size >= 1000) { onBatch(ArrayList(batch)); batch.clear() }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    suspend fun live(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) =
        pull("get_live_streams", "get_live_categories", onBatch) { m, g ->
            ChannelEntity(playlistId = pid, kind = if (RADIO_RE.containsMatchIn(g)) "radio" else "live",
                name = m["name"] ?: "", logo = m["stream_icon"] ?: "", groupTitle = g,
                streamUrl = "$base/live/$eu/$ep/${m["stream_id"]}.m3u8", tvgId = m["epg_channel_id"] ?: "")
        }

    suspend fun movies(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) =
        pull("get_vod_streams", "get_vod_categories", onBatch) { m, g ->
            ChannelEntity(playlistId = pid, kind = "movie", name = m["name"] ?: "", logo = m["stream_icon"] ?: "", groupTitle = g,
                streamUrl = "$base/movie/$eu/$ep/${m["stream_id"]}.${m["container_extension"] ?: "mp4"}",
                rating = m["rating"] ?: "", ext = m["stream_id"] ?: "")
        }

    suspend fun series(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) =
        pull("get_series", "get_series_categories", onBatch) { m, g ->
            ChannelEntity(playlistId = pid, kind = "series", name = m["name"] ?: "", logo = m["cover"] ?: "", groupTitle = g,
                streamUrl = "series:${m["series_id"]}", rating = m["rating"] ?: "", plot = m["plot"] ?: "", ext = m["genre"] ?: "")
        }

    /** Episodes grouped by season: groupTitle = "Saison N", plot = episode synopsis, logo = thumbnail. */
    fun episodes(seriesId: String, pid: Long): List<ChannelEntity> =
        Net.open(api("get_series_info", "&series_id=$seriesId")).use { r ->
            val root = JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
            val cover = root.getAsJsonObject("info")?.str("cover") ?: ""
            val out = ArrayList<ChannelEntity>()
            val eps = root.get("episodes")
            val seasons: List<Pair<String, com.google.gson.JsonArray>> = when {
                eps == null || eps.isJsonNull -> emptyList()
                eps.isJsonObject -> eps.asJsonObject.entrySet().map { it.key to it.value.asJsonArray }
                eps.isJsonArray -> listOf("1" to eps.asJsonArray)
                else -> emptyList()
            }
            seasons.sortedBy { it.first.toIntOrNull() ?: 0 }.forEach { (season, arr) ->
                arr.forEach { e ->
                    val o = e.asJsonObject
                    val id = o.str("id") ?: return@forEach
                    val ext = o.str("container_extension") ?: "mp4"
                    val info = o.getAsJsonObject("info")
                    val num = o.str("episode_num") ?: ""
                    out += ChannelEntity(
                        id = EPISODE_ID_BASE + (id.toLongOrNull() ?: 0L), playlistId = pid, kind = "movie",
                        name = "Épisode $num  ${o.str("title") ?: ""}".trim(),
                        logo = info?.str("movie_image")?.takeIf { it.isNotBlank() } ?: cover,
                        groupTitle = "Saison $season",
                        plot = info?.str("plot") ?: "", ext = info?.str("duration") ?: "",
                        streamUrl = "$base/series/$eu/$ep/$id.$ext"
                    )
                }
            }
            out
        }

    private fun countArray(action: String): Int = try {
        Net.open(api(action)).use { r ->
            JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { j ->
                var n = 0
                j.beginArray()
                while (j.hasNext()) { j.skipValue(); n++ }
                j.endArray()
                n
            }
        }
    } catch (e: Exception) { 0 }

    /** Account check + counts (streamed, nothing is kept in memory). */
    suspend fun probe(): ProbeResult = coroutineScope {
        val o = Net.open("$base/player_api.php?username=$eu&password=$ep").use { r ->
            JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
        }
        val ui = o.getAsJsonObject("user_info") ?: error("Réponse inattendue du serveur")
        if (ui.str("auth") == "0") error("Identifiants refusés")
        val st = ui.str("status") ?: ""
        val exp = (ui.str("exp_date")?.toLongOrNull() ?: 0L) * 1000
        val note = "connexions ${ui.str("active_cons") ?: "?"}/${ui.str("max_connections") ?: "?"}"
        val a = async(Dispatchers.IO) { countArray("get_live_streams") }
        val b = async(Dispatchers.IO) { countArray("get_vod_streams") }
        val c = async(Dispatchers.IO) { countArray("get_series") }
        ProbeResult(a.await(), b.await(), c.await(), exp, note, st)
    }

    fun trailer(vodId: String): String? = runCatching {
        Net.open(api("get_vod_info", "&vod_id=$vodId")).use { r ->
            JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
                .getAsJsonObject("info")?.str("youtube_trailer")?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

data class StalkerCat(val id: String, val title: String)

/** Result of testing an access code: counts, expiry (epoch ms, 0 = unknown/unlimited) and a short note. */
data class ProbeResult(val channels: Int, val movies: Int, val series: Int, val expiry: Long, val note: String, val status: String = "")

fun parseDateLoose(raw0: String): Long {
    val raw = raw0.trim()
    if (raw.isEmpty() || raw.equals("null", true) || raw.contains("unlimited", true) || raw.contains("illimit", true)) return 0L
    raw.toLongOrNull()?.let { return if (raw.length <= 10) it * 1000 else it }
    for (f in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "MMMM d, yyyy, h:mm a", "MMMM d, yyyy", "MMM d, yyyy")) {
        try { return SimpleDateFormat(f, Locale.ENGLISH).parse(raw)!!.time } catch (e: Exception) {}
    }
    return 0L
}

/** Stalker / MAG middleware client: handshake with MAC, live channels (streamed), VOD / series (paged, on demand). */
class StalkerClient(portal: String, mac0: String, opts: String = "") {
    private val root = normalizeHost(portal)
    private val mac = mac0.trim()
    private val serial = opts.split("|").getOrNull(0).orEmpty()
    private val deviceId = opts.split("|").getOrNull(1).orEmpty()
    private val model = opts.split("|").getOrNull(2)?.ifBlank { null } ?: "MAG250"
    private var api = ""
    private var token = ""
    private val ua = MAG_UA

    private fun hdr() = mapOf(
        "Cookie" to "mac=$mac; stb_lang=en; timezone=Europe%2FLondon", "User-Agent" to ua,
        "X-User-Agent" to "Model: $model; Link: WiFi", "Authorization" to "Bearer $token")

    private fun url(q: String): String {
        val ids = (if (serial.isNotBlank()) "&sn=${enc(serial)}" else "") + (if (deviceId.isNotBlank()) "&device_id=${enc(deviceId)}&device_id2=${enc(deviceId)}" else "")
        return "$api?$q&mac=${enc(mac)}&stb_type=$model&stb_lang=en&timezone=Europe/London$ids&JsHttpRequest=1-xml"
    }

    private fun call(q: String): JsonElement =
        Net.open(url(q), hdr()).use { r ->
            val o = JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
            o.get("js") ?: error("Réponse invalide du portail")
        }

    private fun handshake() {
        var last: Exception? = null
        for (p in listOf("$root/portal.php", "$root/server/load.php", "$root/stalker_portal/server/load.php")) {
            try {
                api = p; token = ""
                token = call("type=stb&action=handshake&token=&prehash=0&auth_second_step=0").asJsonObject.str("token") ?: error("pas de jeton")
                runCatching { call("type=stb&action=get_profile&hd=1&num_banks=2&client_type=STB&image_version=218&video_out=hdmi&auth_second_step=1&not_valid_token=0&api_signature=262") }
                return
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Connexion au portail impossible")
    }

    private fun abs(u: String?): String = when {
        u.isNullOrBlank() -> ""
        u.startsWith("http") -> u
        u.startsWith("/") -> root + u
        else -> "$root/$u"
    }

    /** Streams get_all_channels from the socket and hands channels over in batches (no giant JSON tree in RAM). */
    suspend fun channels(pid: Long, onBatch: suspend (List<ChannelEntity>) -> Unit) {
        handshake()
        val genres = HashMap<String, String>()
        runCatching { call("type=itv&action=get_genres").asJsonArray.forEach { val o = it.asJsonObject; genres[o.str("id") ?: ""] = o.str("title") ?: "Autres" } }
        var count = 0
        val batch = ArrayList<ChannelEntity>()
        try {
            Net.open(url("type=itv&action=get_all_channels"), hdr()).use { r ->
                JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { j ->
                    j.isLenient = true
                    j.beginObject()
                    while (j.hasNext()) {
                        if (j.nextName() != "js") { j.skipValue(); continue }
                        j.beginObject()
                        while (j.hasNext()) {
                            if (j.nextName() != "data") { j.skipValue(); continue }
                            j.beginArray()
                            while (j.hasNext()) {
                                var name = ""; var cmd = ""; var logo = ""; var gid = ""
                                j.beginObject()
                                while (j.hasNext()) {
                                    val k = j.nextName()
                                    when (j.peek()) {
                                        JsonToken.STRING, JsonToken.NUMBER -> {
                                            val v = j.nextString()
                                            when (k) { "name" -> name = v; "cmd" -> cmd = v; "logo" -> logo = v; "tv_genre_id" -> gid = v }
                                        }
                                        JsonToken.NULL -> j.nextNull()
                                        else -> j.skipValue()
                                    }
                                }
                                j.endObject()
                                if (cmd.isNotBlank()) {
                                    val g = genres[gid] ?: "Autres"
                                    batch += ChannelEntity(playlistId = pid, kind = if (RADIO_RE.containsMatchIn(g)) "radio" else "live",
                                        name = name, logo = abs(logo), groupTitle = g, streamUrl = "stalker:$cmd")
                                    if (batch.size >= 1000) { count += batch.size; onBatch(ArrayList(batch)); batch.clear() }
                                }
                            }
                            j.endArray()
                        }
                        j.endObject()
                    }
                    j.endObject()
                }
            }
        } catch (e: Exception) {
            if (count + batch.size == 0) throw e // keep what was received if the portal cut the connection late
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    /** Category lists for "vod" (films) or "series". Returns empty when the portal does not offer them. */
    fun categories(type: String): List<StalkerCat> = try {
        call("type=$type&action=get_categories").asJsonArray.mapNotNull {
            val o = it.asJsonObject
            val id = o.str("id") ?: return@mapNotNull null
            if (id == "*" || id == "0") null else StalkerCat(id, o.str("title") ?: "Catégorie")
        }
    } catch (e: Exception) { emptyList() }

    /** One page of a category. Returns the items and the total number of pages (0 = unknown). */
    fun page(type: String, cat: String, group: String, page: Int, pid: Long): Pair<List<ChannelEntity>, Int> {
        val js = call("type=$type&action=get_ordered_list&category=${enc(cat)}&sortby=added&p=$page").asJsonObject
        val total = js.str("total_items")?.toIntOrNull() ?: 0
        val per = js.str("max_page_items")?.toIntOrNull() ?: 0
        val pages = if (total > 0 && per > 0) (total + per - 1) / per else 0
        val data = js.getAsJsonArray("data") ?: return emptyList<ChannelEntity>() to pages
        val out = ArrayList<ChannelEntity>()
        data.forEach { e ->
            val o = e.asJsonObject
            val id = o.str("id") ?: return@forEach
            val cmd = o.str("cmd") ?: ""
            val name = o.str("name") ?: return@forEach
            val series = o.get("series")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            val isSeries = type == "series" || o.str("is_series") == "1" || series.isNotEmpty()
            val rating = o.str("rating_imdb")?.takeIf { it.isNotBlank() && it != "0" } ?: o.str("rating_kinopoisk") ?: ""
            out += ChannelEntity(
                playlistId = pid, kind = if (isSeries) "series" else "movie", name = name,
                logo = abs(o.str("screenshot_uri") ?: o.str("cover")), groupTitle = group, rating = rating,
                plot = o.str("description") ?: "",
                streamUrl = if (isSeries) "stalkerser:$type:$id:$cmd" else "stalkervod:$cmd",
                ext = series.joinToString(",")
            )
        }
        return out to pages
    }

    /** Seasons / episodes of a series item ("stalkerser:{type}:{id}:{cmd}"). */
    fun episodes(item: ChannelEntity): List<ChannelEntity> {
        handshake()
        val rest = item.streamUrl.removePrefix("stalkerser:")
        val type = rest.substringBefore(':')
        val id = rest.substringAfter(':').substringBefore(':')
        val cmd = rest.substringAfter(':').substringAfter(':')
        val out = ArrayList<ChannelEntity>()
        fun add(season: String, c: String, nums: List<String>) {
            nums.forEach { n ->
                out += ChannelEntity(
                    id = EPISODE_ID_BASE * 2 + (item.id * 1000 + (n.toLongOrNull() ?: 0L)) , playlistId = item.playlistId, kind = "movie",
                    name = "Épisode $n", logo = item.logo, groupTitle = season, streamUrl = "stalkerep:$n:$c")
            }
        }
        if (type == "vod") {
            add("Saison 1", cmd, item.ext.split(",").filter { it.isNotBlank() })
        } else {
            val js = call("type=series&action=get_ordered_list&movie_id=${enc(id)}&season_id=0&episode_id=0&p=1").asJsonObject
            val data = js.getAsJsonArray("data")
            var sn = 0
            data?.forEach { e ->
                val o = e.asJsonObject
                sn++
                val nums = o.get("series")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
                add(o.str("name")?.let { n -> if (n.contains("aison", true) || n.contains("eason", true)) n else "Saison $sn" } ?: "Saison $sn", o.str("cmd") ?: cmd, nums)
            }
        }
        return out
    }

    /** Creates a playable link. type: itv | vod. */
    fun resolve(cmd: String, type: String = "itv", series: String = ""): String {
        val direct = cmd.substringAfterLast(' ').trim()
        return try {
            handshake()
            val js = call("type=$type&action=create_link&cmd=" + enc(cmd) +
                "&series=$series&forced_storage=undefined&disable_ad=0&download=0&force_ch_link_check=0").asJsonObject
            val link = js.str("cmd")?.substringAfterLast(' ')?.trim()
            if (!link.isNullOrBlank() && link.startsWith("http")) link else direct
        } catch (e: Exception) { direct }
    }

    /** Handshake + streamed channel count + VOD / series totals + expiry date (when the portal exposes it). */
    fun probe(): ProbeResult {
        handshake()
        var channels = 0
        try {
            Net.open(url("type=itv&action=get_all_channels"), hdr()).use { r ->
                JsonReader(InputStreamReader(r.body!!.byteStream(), "UTF-8")).use { j ->
                    j.isLenient = true
                    j.beginObject()
                    while (j.hasNext()) {
                        if (j.nextName() != "js") { j.skipValue(); continue }
                        j.beginObject()
                        while (j.hasNext()) {
                            if (j.nextName() != "data") { j.skipValue(); continue }
                            j.beginArray()
                            while (j.hasNext()) { j.skipValue(); channels++ }
                            j.endArray()
                        }
                        j.endObject()
                    }
                    j.endObject()
                }
            }
        } catch (e: Exception) { if (channels == 0) throw e }
        fun total(type: String): Int = try {
            call("type=$type&action=get_ordered_list&p=1").asJsonObject.str("total_items")?.toIntOrNull() ?: 0
        } catch (e: Exception) { 0 }
        val movies = total("vod")
        val series = total("series")
        var expiry = 0L
        var note = ""
        try {
            val js = call("type=account_info&action=get_main_info").asJsonObject
            val raw = js.str("end_date") ?: js.str("expire_billing_date") ?: js.str("expiry") ?: ""
            note = raw; expiry = parseDateLoose(raw)
        } catch (e: Exception) {}
        return ProbeResult(channels, movies, series, expiry, note)
    }
}
