package com.streamtv.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

val RADIO_RE = Regex("(?i)radio|راديو|إذاعة|اذاعة")

private val MOVIE_RE = Regex("(?i)movie|film|vod|cin[eé]ma|أفلام|افلام")
private val SERIES_RE = Regex("(?i)series|s[eé]rie|مسلسل")
private val EP_RE = Regex("^(.*?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})")
private val VIDEO_EXT = Regex("(?i)\\.(mp4|mkv|avi|mov|webm|m4v)(\\?.*)?$")

/** Streaming M3U parser: reads line by line and emits batches, so 100k+ entries never block the UI or exhaust RAM.
 *  Episodes named "Title S01E02" are grouped into one series (kind "series") with hidden episode rows (kind "episode"). */
object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    suspend fun parse(reader: BufferedReader, pid: Long, batchSize: Int = 1000, onBatch: suspend (List<ChannelEntity>) -> Unit) {
        val batch = ArrayList<ChannelEntity>(batchSize)
        val seriesSeen = HashSet<String>()
        var name = ""; var logo = ""; var group = ""; var tvg = ""; var pending = false
        suspend fun flush() { if (batch.size >= batchSize) { onBatch(ArrayList(batch)); batch.clear() } }
        while (true) {
            val line = reader.readLine()?.trim() ?: break
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    val a = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = a["tvg-logo"].orEmpty(); group = a["group-title"].orEmpty().ifBlank { "Autres" }
                    tvg = a["tvg-id"].orEmpty()
                    name = line.substringAfterLast(',', "").trim().ifBlank { a["tvg-name"].orEmpty() }
                    pending = true
                }
                line.startsWith("#") -> {}
                pending -> {
                    pending = false
                    val ep = EP_RE.find(name)
                    when {
                        RADIO_RE.containsMatchIn(group) ->
                            batch += ChannelEntity(playlistId = pid, kind = "radio", name = name, logo = logo, groupTitle = group, streamUrl = line, tvgId = tvg)
                        ep != null && !RADIO_RE.containsMatchIn(group) -> {
                            val title = ep.groupValues[1].trim().ifBlank { name }
                            if (seriesSeen.add(group + "\u0001" + title))
                                batch += ChannelEntity(playlistId = pid, kind = "series", name = title, logo = logo, groupTitle = group, streamUrl = "localseries:$title")
                            batch += ChannelEntity(
                                playlistId = pid, kind = "episode", name = "Épisode ${ep.groupValues[3].toInt()}", logo = logo, groupTitle = title,
                                rating = ep.groupValues[2].padStart(3, '0'), ext = ep.groupValues[3].padStart(3, '0'), streamUrl = line)
                        }
                        VIDEO_EXT.containsMatchIn(line) || MOVIE_RE.containsMatchIn(group) || SERIES_RE.containsMatchIn(group) || line.contains("/movie/") || line.contains("/series/") ->
                            batch += ChannelEntity(playlistId = pid, kind = "movie", name = name, logo = logo, groupTitle = group, streamUrl = line)
                        else ->
                            batch += ChannelEntity(playlistId = pid, kind = "live", name = name, logo = logo, groupTitle = group, streamUrl = line, tvgId = tvg)
                    }
                    flush()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }
}

/** Streaming XMLTV parser (pull parser). Keeps only programmes ending after now-1h and starting within the configured window (hours). */
object XmltvParser {
    suspend fun parse(input: InputStream, pid: Long, hours: Int, onBatch: suspend (List<EpgEntity>) -> Unit) {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun ts(s: String?): Long = try { fmt.parse(s!!.trim())!!.time } catch (e: Exception) { 0L }
        val p = Xml.newPullParser(); p.setInput(input, null)
        val now = System.currentTimeMillis(); val batch = ArrayList<EpgEntity>()
        var ch = ""; var s = 0L; var e = 0L; var title = ""; var desc = ""; var tag = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "programme") {
                        ch = p.getAttributeValue(null, "channel") ?: ""
                        s = ts(p.getAttributeValue(null, "start")); e = ts(p.getAttributeValue(null, "stop"))
                        title = ""; desc = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    if (tag == "title" && title.isEmpty()) title = p.text
                    else if (tag == "desc" && desc.isEmpty()) desc = p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "programme" && e > now - 3_600_000 && s < now + hours * 3_600_000L) {
                        batch += EpgEntity(playlistId = pid, channelTvg = ch, start = s, stop = e, title = title, descr = desc)
                        if (batch.size >= 2000) { onBatch(ArrayList(batch)); batch.clear() }
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }
}
