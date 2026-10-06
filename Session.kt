package com.streamtv.iptv

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One playback session shared by the small preview window and the fullscreen player, so a channel keeps
 * playing (no reload) when the user opens it fullscreen. Handles engine fallback, retries and progress saving.
 */
class PlaybackSession(
    private val app: Application,
    private val settings: AppSettings,
    private val repo: Repository,
    private val saver: (ChannelEntity, Long, Long) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var engine: Engine? = null
        private set
    var engineKind by mutableStateOf("")
        private set
    var engineGen by mutableIntStateOf(0)
        private set
    var items by mutableStateOf<List<ChannelEntity>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
    var previewMode by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
        private set
    var aspect by mutableIntStateOf(settings.aspect.int)
        private set
    var stable by mutableStateOf(false)
        private set
    var subDelay by mutableLongStateOf(0L)
        private set
    var audioDelay by mutableLongStateOf(0L)
        private set
    var sleepLabel by mutableStateOf("Non")
        private set

    var recording by mutableStateOf(false)
        private set
    var recSeconds by mutableLongStateOf(0L)
        private set
    var recFile by mutableStateOf("")
        private set
    var enhance by mutableStateOf(settings.enhance.v)
        private set
    var notice by mutableStateOf("")
        private set

    val item: ChannelEntity? get() = items.getOrNull(index)
    fun clearNotice() { notice = "" }

    private var stream: Stream? = null
    private var attempts: List<Pair<String, String>> = emptyList()
    private var attemptIdx = 0
    private var rebuffers = 0
    private var lastResume = 0L
    private var lastStart = 0L
    private var host: View? = null
    private var loadJob: Job? = null
    private var sleepAt = 0L
    private var sleepIdx = 0

    init {
        scope.launch {
            while (true) {
                delay(10_000)
                tick()
            }
        }
    }

    private fun tick() {
        saveNow()
        if (recording && engine?.recording != true) { recording = false; notice = "Enregistrement interrompu" }
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
            sleepAt = 0; sleepIdx = 0; sleepLabel = "Non"
            stop()
        }
    }

    // ---------------------------------------------------------------- starting playback

    fun play(list: List<ChannelEntity>, idx: Int, resumeMs: Long = 0L, full: Boolean = false, preview: Boolean = false) {
        if (list.isEmpty()) return
        items = list
        index = idx.coerceIn(0, list.size - 1)
        previewMode = preview
        if (full) fullscreen = true
        start(resumeMs)
    }

    fun zap(d: Int) {
        if (items.size > 1) { index = (index + d + items.size) % items.size; start(0L) }
    }

    fun jumpTo(i: Int) {
        if (i in items.indices) { index = i; start(0L) }
    }

    fun retry() = start(lastResume)

    private fun start(resume: Long) {
        val cur = item ?: return
        loadJob?.cancel()
        if (recording) stopRecording()
        error = null; loading = true; rebuffers = 0
        lastResume = resume; lastStart = System.currentTimeMillis()
        speed = 1f; subDelay = 0L; audioDelay = 0L
        val first = settings.engine.v
        ensureEngine(first)
        engine?.setSpeed(1f)
        if (cur.kind != "movie") saver(cur, 0L, 0L)
        loadJob = scope.launch {
            val st = try {
                repo.resolve(cur)
            } catch (e: Exception) {
                error = "Serveur injoignable : ${e.message ?: ""}"; loading = false
                return@launch
            }
            stream = st
            // fallback chain: alternate Xtream extension, then the other engine
            val alt = if (st.url.contains("/live/") && st.url.endsWith(".m3u8")) st.url.removeSuffix(".m3u8") + ".ts" else null
            val other = if (first == "exo") "vlc" else "exo"
            val list = ArrayList<Pair<String, String>>()
            list.add(first to st.url)
            if (alt != null) list.add(first to alt)
            list.add(other to st.url)
            attempts = list
            attemptIdx = 0
            execute(resume)
        }
    }

    private fun execute(resume: Long) {
        val st = stream ?: return
        val att = attempts.getOrNull(attemptIdx) ?: return
        ensureEngine(att.first)
        loading = false
        try { engine?.load(st, att.second, resume) } catch (t: Throwable) { onEngineError(t.message ?: "échec du chargement") }
    }

    private fun onEngineError(m: String) {
        if (error != null) return
        if (attemptIdx + 1 < attempts.size) { attemptIdx++; execute(lastResume) }
        else { error = "Lecture impossible : $m"; loading = false }
    }

    private fun onEngineEnded() {
        val c = item ?: return
        if (c.kind == "movie") {
            saver(c, 0L, 0L)
            if (settings.autoNext.on && index + 1 < items.size) jumpTo(index + 1)
        } else if (System.currentTimeMillis() - lastStart > 5000) {
            start(0L) // live stream dropped: reconnect
        }
    }

    private fun onRebuffer() {
        val c = item ?: return
        rebuffers++
        if (c.kind != "movie" && !stable && engine?.kind == "exo" && rebuffers >= 4) toggleStable()
    }

    // ---------------------------------------------------------------- engine management

    private fun ensureEngine(kind: String) {
        val cur = engine
        if (cur != null && cur.kind == kind) return
        cur?.let { dispose(it) }
        host = null
        val e: Engine = try {
            if (kind == "vlc") VlcEngine(app, settings, stable) else ExoEngine(app, settings, stable)
        } catch (t: Throwable) {
            ExoEngine(app, settings, stable)
        }
        e.setAspect(aspect)
        e.setEnhance(enhance)
        e.onEnhanceFailed = { main.post { onEnhanceFailed() } }
        e.onError = { m -> main.post { onEngineError(m) } }
        e.onEnded = { main.post { onEngineEnded() } }
        e.onRebuffer = { main.post { onRebuffer() } }
        engine = e
        engineKind = e.kind
        engineGen++
    }

    private fun dispose(e: Engine) {
        e.onError = null; e.onEnded = null; e.onRebuffer = null; e.onEnhanceFailed = null
        e.release()
    }

    fun createHost(ctx: Context): View {
        val e = engine
        val v = e?.createHost(ctx) ?: View(ctx)
        host = v
        e?.attach(v)
        return v
    }

    fun releaseHost(v: View) {
        if (host === v) { engine?.detach(); host = null }
    }

    fun switchEngine() {
        val e = engine ?: return
        val st = stream ?: return
        val k = if (e.kind == "exo") "vlc" else "exo"
        val url = attempts.getOrNull(attemptIdx)?.second ?: st.url
        val pos = if (item?.kind == "movie") e.position else 0L
        attempts = listOf(k to url, (if (k == "exo") "vlc" else "exo") to url)
        attemptIdx = 0; error = null; lastResume = pos
        execute(pos)
    }

    fun toggleStable() {
        stable = !stable
        val e = engine ?: return
        val kind = e.kind
        val pos = if (item?.kind == "movie") e.position else 0L
        dispose(e)
        engine = null
        host = null
        ensureEngine(kind)
        if (stream != null && attempts.isNotEmpty()) execute(pos)
    }

    // ---------------------------------------------------------------- controls

    fun togglePlay() { engine?.let { it.setPlaying(!it.isPlaying) } }

    fun seekBy(ms: Long) {
        val e = engine ?: return
        val t = (e.position + ms).coerceAtLeast(0L)
        val d = e.duration
        e.seekTo(if (d > 0) t.coerceAtMost(d - 500L).coerceAtLeast(0L) else t)
    }

    fun cycleSpeed() {
        val l = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val i = l.indexOf(speed).let { if (it < 0) 2 else it }
        speed = l[(i + 1) % l.size]
        engine?.setSpeed(speed)
    }

    fun cycleAspect() {
        aspect = (aspect + 1) % 5
        engine?.setAspect(aspect)
    }

    fun addSubDelay(ms: Long) { subDelay = engine?.addSubtitleDelay(ms) ?: 0L }
    fun addAudioDelayMs(ms: Long) { audioDelay = engine?.addAudioDelay(ms) ?: 0L }
    fun audioTracks(): List<Track> = engine?.audioTracks() ?: emptyList()
    fun subtitleTracks(): List<Track> = engine?.subtitleTracks() ?: emptyList()
    fun currentUrl(): String = attempts.getOrNull(attemptIdx)?.second ?: stream?.url ?: ""

    fun cycleSleep() {
        val mins = listOf(0, 15, 30, 60, 90)
        sleepIdx = (sleepIdx + 1) % mins.size
        val m = mins[sleepIdx]
        sleepAt = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L
        sleepLabel = if (m == 0) "Non" else "$m min"
    }

    // ---------------------------------------------------------------- video enhancement + recording

    private fun onEnhanceFailed() {
        if (enhance == "off") return
        enhance = "off"; settings.enhance.set("off")
        engine?.setEnhance("off")
        engineGen++
        notice = "Amélioration vidéo désactivée (appareil trop lent ou non compatible)"
    }

    fun setEnhanceMode(mode: String) {
        enhance = mode; settings.enhance.set(mode)
        val e = engine ?: return
        if (e.kind != "exo") { notice = "L'amélioration vidéo fonctionne avec ExoPlayer"; return }
        e.setEnhance(mode)
        engineGen++ // new video surface, playback continues
    }

    fun cycleEnhance() {
        val l = settings.enhance.options
        setEnhanceMode(l[(l.indexOf(enhance) + 1) % l.size])
    }

    /** First mounted secondary storage (USB stick / SD card) that gives this app a writable folder. */
    fun usbDir(ctx: Context): File? {
        val dirs = try { ctx.getExternalFilesDirs(null) } catch (e: Exception) { return null }
        for (i in 1 until dirs.size) {
            val d = dirs[i] ?: continue
            if (android.os.Environment.getExternalStorageState(d) == android.os.Environment.MEDIA_MOUNTED) return d
        }
        return null
    }

    fun toggleRecording(ctx: Context) {
        if (recording) { stopRecording(); notice = "Enregistrement terminé : $recFile"; return }
        val e = engine ?: return
        val cur = item ?: return
        if (cur.kind == "movie") { notice = "L'enregistrement concerne les chaînes en direct"; return }
        if (!e.canRecord) { switchEngine(); notice = "Lecteur changé en ExoPlayer : appuyez de nouveau sur Enregistrer"; return }
        val dir = usbDir(ctx) ?: run { notice = "Aucune clé USB détectée. Branchez une clé USB pour enregistrer."; return }
        val name = cur.name.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)
        val f = File(dir, "StreamTV_" + name + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".ts")
        if (e.startRecording(f)) {
            recording = true; recFile = f.name; recSeconds = 0L
            notice = "Enregistrement sur la clé USB : ${f.name}"
            scope.launch { while (recording) { delay(1000); recSeconds++ } }
        } else notice = "Impossible d'écrire sur la clé USB"
    }

    fun stopRecording() { engine?.stopRecording(); recording = false }

    // ---------------------------------------------------------------- stopping

    fun saveNow() {
        val c = item ?: return
        val e = engine ?: return
        if (c.kind == "movie" && e.duration > 0 && e.position > 0) saver(c, e.position, e.duration)
    }

    fun stop() {
        saveNow()
        if (recording) stopRecording()
        loadJob?.cancel()
        engine?.let { dispose(it) }
        engine = null; engineKind = ""; host = null
        items = emptyList(); index = 0
        error = null; loading = false
        fullscreen = false; previewMode = false
        speed = 1f
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
