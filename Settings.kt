package com.streamtv.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One persisted option that cycles through a fixed list of values. */
class Opt(private val sp: SharedPreferences, val key: String, val options: List<String>, val labels: List<String>, def: String) {
    var v by mutableStateOf(sp.getString(key, def)?.takeIf { it in options } ?: def)
        private set
    val label: String get() = labels.getOrElse(options.indexOf(v)) { v }
    val on: Boolean get() = v == "on"
    val int: Int get() = v.toIntOrNull() ?: 0
    fun set(x: String) { v = x; sp.edit().putString(key, x).apply() }
    fun cycle() { set(options[(options.indexOf(v) + 1) % options.size]) }
}

class AppSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun toggle(key: String, def: String) = Opt(sp, key, listOf("on", "off"), listOf("Oui", "Non"), def)

    val engine = Opt(sp, "engine", listOf("exo", "vlc"), listOf("ExoPlayer", "VLC"), "exo")
    val buffer = Opt(sp, "buffer", listOf("fast", "balanced", "stable"), listOf("Zapping rapide", "Équilibré", "Stable (connexion faible)"), "fast")
    val hw = toggle("hw", "on")
    val aspect = Opt(sp, "aspect", listOf("0", "1", "2", "3", "4"), listOf("Ajusté", "Étiré", "Zoom", "16:9", "4:3"), "0")
    val seek = Opt(sp, "seek", listOf("10", "20", "30", "60"), listOf("10 s", "20 s", "30 s", "60 s"), "10")
    val resume = toggle("resume", "on")
    val autoNext = toggle("autonext", "on")
    val subSize = Opt(sp, "subsize", listOf("80", "100", "130", "170"), listOf("Petite", "Moyenne", "Grande", "Très grande"), "100")
    val clickMode = Opt(sp, "click", listOf("preview", "full"), listOf("Aperçu d'abord", "Plein écran direct"), "preview")
    val iconSize = Opt(sp, "icon", listOf("32", "44", "60"), listOf("Petites", "Moyennes", "Grandes"), "32")
    val lite = toggle("lite", "on")
    val epgHours = Opt(sp, "epg", listOf("0", "6", "12", "24", "48"), listOf("Désactivé", "6 h", "12 h", "24 h", "48 h"), "6")
    val hideAdult = toggle("adult", "off")
    var updateRepo by mutableStateOf(sp.getString("updrepo", "amine20code-arch/PRO_TV-v1-pc") ?: "")
        private set
    fun setUpdateRepo(v: String) { updateRepo = v.trim(); sp.edit().putString("updrepo", updateRepo).apply() }

    val enhance = Opt(sp, "enhance", listOf("off", "auto", "soft", "sharp", "cinema", "vivid"),
        listOf("Désactivée", "Auto (adaptative)", "Douce", "Nette", "Cinéma", "Éclatante"), "off")
    val stbModel = Opt(sp, "stb", listOf("MAG250", "MAG322", "MAG420", "MAG424"), listOf("MAG 250", "MAG 322", "MAG 420", "MAG 424"), "MAG250")
    val navStyle = Opt(sp, "nav", listOf("side", "top", "tiles"), listOf("Barre latérale", "Barre supérieure", "Tuiles"), "side")
}
