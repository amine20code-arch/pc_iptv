package com.streamtv.iptv

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

data class Track(val id: Int, val label: String, val selected: Boolean)

/** Common contract of the two embedded players (ExoPlayer and VLC). */
interface Engine {
    val kind: String
    var onError: ((String) -> Unit)?
    var onEnded: (() -> Unit)?
    var onRebuffer: (() -> Unit)?
    val isBuffering: Boolean
    val isPlaying: Boolean
    val position: Long
    val duration: Long
    val videoInfo: String
    val seekable: Boolean
    val canRecord: Boolean
    val recording: Boolean
    var onEnhanceFailed: (() -> Unit)?
    fun startRecording(file: java.io.File): Boolean
    fun stopRecording()
    fun setEnhance(mode: String)
    fun createHost(ctx: Context): View
    fun attach(host: View)
    fun detach()
    fun load(st: Stream, url: String, resumeMs: Long)
    fun setPlaying(p: Boolean)
    fun seekTo(ms: Long)
    fun setSpeed(f: Float)
    fun audioTracks(): List<Track>
    fun subtitleTracks(): List<Track>
    fun selectAudio(id: Int)
    fun selectSubtitle(id: Int)
    fun setAspect(mode: Int)
    fun supportsDelay(): Boolean
    fun addAudioDelay(ms: Long): Long
    fun addSubtitleDelay(ms: Long): Long
    fun release()
}

/** TextureView based host: survives clipping / scaling / moving between the preview window and fullscreen. */
class ExoVideoView(ctx: Context) : FrameLayout(ctx) {
    val frame = AspectRatioFrameLayout(ctx)
    val texture = TextureView(ctx)
    val subs = SubtitleView(ctx)

    init {
        frame.addView(texture, FrameLayout.LayoutParams(-1, -1))
        addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        addView(subs, FrameLayout.LayoutParams(-1, -1))
        subs.setUserDefaultStyle()
        isFocusable = false
        keepScreenOn = true
        setBackgroundColor(android.graphics.Color.BLACK)
    }
}

/** Copies the bytes read by the player into a file (live TS streams / HLS segments) while recording. */
class RecordSink : DataSink {
    @Volatile var out: java.io.OutputStream? = null
    @Volatile private var accept = true
    override fun open(dataSpec: DataSpec) { accept = !dataSpec.uri.toString().contains(".m3u8") }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val o = out ?: return
        if (!accept) return
        try { o.write(buffer, offset, length) } catch (e: Exception) { out = null }
    }
    override fun close() {}
}

class ExoEngine(ctx: Context, private val s: AppSettings, stable: Boolean) : Engine {
    override val kind = "exo"
    override var onError: ((String) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
    override var onRebuffer: (() -> Unit)? = null

    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(if (stable) 15_000 else 8_000).setReadTimeoutMs(if (stable) 25_000 else 12_000)
    private val player: ExoPlayer
    private var host: ExoVideoView? = null
    private var aspectMode = s.aspect.int
    private var vw = 0
    private var vh = 0
    private var par = 1f
    private var wasReady = false
    private val recSink = RecordSink()
    private var enhanceMode = s.enhance.v
    private var enhancer: VideoEnhancer? = null
    private var slowCount = 0
    override var onEnhanceFailed: (() -> Unit)? = null

    init {
        val mode = if (stable) "stable" else s.buffer.v
        val lc = when (mode) {
            "stable" -> DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
            "balanced" -> DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 30_000, 1_500, 3_000)
            else -> DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 20_000, 500, 1_500)
        }.build()
        // exponential backoff 1s,2s,4s,8s; non-retryable errors (unsupported format, 404) fail immediately
        val policy = object : DefaultLoadErrorHandlingPolicy(if (stable) 5 else 3) {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val d = super.getRetryDelayMsFor(info)
                return if (d == C.TIME_UNSET) C.TIME_UNSET else minOf(1000L shl (info.errorCount - 1).coerceAtLeast(0), 8000L)
            }
        }
        val rf = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val teeFactory = DataSource.Factory { TeeDataSource(http.createDataSource(), recSink) }
        player = ExoPlayer.Builder(ctx, rf).setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(teeFactory).setLoadErrorHandlingPolicy(policy)).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onError?.invoke(error.errorCodeName) }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING && wasReady) onRebuffer?.invoke()
                wasReady = playbackState == Player.STATE_READY
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                vw = videoSize.width; vh = videoSize.height; par = videoSize.pixelWidthHeightRatio
                applyAspect()
                enhancer?.let { it.videoW = vw; it.videoH = vh; it.params = enhanceParams(enhanceMode, vh) }
            }
            override fun onCues(cueGroup: CueGroup) { host?.subs?.setCues(cueGroup.cues) }
        })
        // too many dropped frames with the enhancer on = device too slow: switch it off automatically
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                if (enhancer != null && droppedFrames > 25 && ++slowCount >= 3) onEnhanceFailed?.invoke()
            }
        })
    }

    override val isBuffering: Boolean get() = player.playbackState == Player.STATE_BUFFERING
    override val isPlaying: Boolean get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    override val position: Long get() = player.currentPosition
    override val duration: Long get() = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
    override val videoInfo: String get() = "${vw}x${vh} ${player.videoFormat?.sampleMimeType ?: ""}".trim()
    override val seekable: Boolean get() = try { player.isCurrentMediaItemSeekable } catch (e: Exception) { false }
    override val canRecord: Boolean get() = true
    override val recording: Boolean get() = recSink.out != null

    override fun startRecording(file: java.io.File): Boolean = try {
        recSink.out = file.outputStream().buffered(256 * 1024); true
    } catch (e: Exception) { false }

    override fun stopRecording() {
        val o = recSink.out
        recSink.out = null
        try { o?.flush(); o?.close() } catch (e: Exception) {}
    }

    override fun setEnhance(mode: String) { enhanceMode = mode }

    override fun createHost(ctx: Context): View = ExoVideoView(ctx)

    override fun attach(host: View) {
        val h = host as? ExoVideoView ?: return
        this.host = h
        if (enhanceMode != "off") {
            val e = VideoEnhancer({ surf -> player.setVideoSurface(surf) }, { onEnhanceFailed?.invoke() })
            e.videoW = vw.coerceAtLeast(1); e.videoH = vh.coerceAtLeast(1)
            e.params = enhanceParams(enhanceMode, vh)
            enhancer = e
            h.texture.surfaceTextureListener = e
            val st = h.texture.surfaceTexture
            if (h.texture.isAvailable && st != null) e.onSurfaceTextureAvailable(st, h.texture.width, h.texture.height)
        } else {
            player.setVideoTextureView(h.texture)
        }
        h.subs.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.subSize.int / 100f)
        applyAspect()
    }

    override fun detach() {
        val e = enhancer
        if (e != null) {
            player.clearVideoSurface()
            e.stop()
            host?.texture?.surfaceTextureListener = null
            enhancer = null
        } else {
            host?.let { player.clearVideoTextureView(it.texture) }
        }
        host = null
    }

    private fun applyAspect() {
        val h = host ?: return
        h.frame.resizeMode = when (aspectMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val ratio = when {
            aspectMode == 3 -> 16f / 9f
            aspectMode == 4 -> 4f / 3f
            vh > 0 -> vw * par / vh
            else -> 0f
        }
        h.frame.setAspectRatio(ratio)
    }

    override fun load(st: Stream, url: String, resumeMs: Long) {
        wasReady = false
        http.setUserAgent(st.ua)
        http.setDefaultRequestProperties(st.headers)
        player.setMediaItem(MediaItem.fromUri(url), if (resumeMs > 0) resumeMs else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    override fun setPlaying(p: Boolean) { player.playWhenReady = p }
    override fun seekTo(ms: Long) { player.seekTo(ms) }
    override fun setSpeed(f: Float) { player.setPlaybackSpeed(f) }

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type && it.isSupported }

    private fun tracks(type: Int): List<Track> = groups(type).mapIndexed { i, g ->
        val f = g.getTrackFormat(0)
        Track(i, f.label ?: f.language?.let { l -> Locale(l).displayLanguage } ?: "Track ${i + 1}", g.isSelected)
    }

    override fun audioTracks() = tracks(C.TRACK_TYPE_AUDIO)
    override fun subtitleTracks() = tracks(C.TRACK_TYPE_TEXT)

    private fun select(type: Int, id: Int) {
        val gs = groups(type)
        val b = player.trackSelectionParameters.buildUpon()
        if (id < 0 || id >= gs.size) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(gs[id].mediaTrackGroup, 0))
        player.trackSelectionParameters = b.build()
    }

    override fun selectAudio(id: Int) = select(C.TRACK_TYPE_AUDIO, id)
    override fun selectSubtitle(id: Int) = select(C.TRACK_TYPE_TEXT, id)
    override fun setAspect(mode: Int) { aspectMode = mode; applyAspect() }
    override fun supportsDelay() = false
    override fun addAudioDelay(ms: Long) = 0L
    override fun addSubtitleDelay(ms: Long) = 0L

    override fun release() {
        onError = null; onEnded = null; onRebuffer = null; onEnhanceFailed = null
        stopRecording()
        detach()
        player.release()
    }
}

/** libVLC engine: plays almost anything and offers audio/subtitle delay, more aspect modes, hardware toggle. */
class VlcEngine(ctx: Context, private val s: AppSettings, stable: Boolean) : Engine {
    override val kind = "vlc"
    override var onError: ((String) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
    override var onRebuffer: (() -> Unit)? = null

    private val cache = if (stable) 5000 else when (s.buffer.v) { "balanced" -> 2500; "stable" -> 5000; else -> 1000 }
    private val lib = LibVLC(ctx, arrayListOf(
        "--network-caching=$cache", "--http-reconnect",
        "--freetype-rel-fontsize=" + when (s.subSize.int) { 80 -> "20"; 100 -> "16"; 130 -> "12"; else -> "9" }))
    private val mp = MediaPlayer(lib)
    private var layout: VLCVideoLayout? = null
    private var buffering = false
    private var playedOnce = false
    private var aspectMode = s.aspect.int
    private var audioDelayMs = 0L
    private var subDelayMs = 0L

    init {
        mp.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) {
                when (event.type) {
                    MediaPlayer.Event.EncounteredError -> onError?.invoke("VLC playback error")
                    MediaPlayer.Event.EndReached -> onEnded?.invoke()
                    MediaPlayer.Event.Playing -> { playedOnce = true; buffering = false }
                    MediaPlayer.Event.Buffering -> {
                        val b = event.buffering < 100f
                        if (b && !buffering && playedOnce) onRebuffer?.invoke()
                        buffering = b
                    }
                }
            }
        })
    }

    override val isBuffering: Boolean get() = buffering
    override val isPlaying: Boolean get() = try { mp.isPlaying } catch (e: Exception) { false }
    override val position: Long get() = try { mp.time } catch (e: Exception) { 0L }
    override val duration: Long get() = try { mp.length.coerceAtLeast(0L) } catch (e: Exception) { 0L }
    override val videoInfo: String get() = "VLC"
    override val seekable: Boolean get() = try { mp.isSeekable } catch (e: Exception) { false }
    override val canRecord: Boolean get() = false
    override val recording: Boolean get() = false
    override var onEnhanceFailed: (() -> Unit)? = null
    override fun startRecording(file: java.io.File): Boolean = false
    override fun stopRecording() {}
    override fun setEnhance(mode: String) {}

    override fun createHost(ctx: Context): View = VLCVideoLayout(ctx).apply { isFocusable = false; keepScreenOn = true }

    override fun attach(host: View) {
        val l = host as? VLCVideoLayout ?: return
        if (layout != null) detach()
        layout = l
        try { mp.attachViews(l, null, true, true) } catch (e: Exception) { layout = null; return }
        applyAspect()
    }

    override fun detach() {
        if (layout == null) return
        try { mp.detachViews() } catch (e: Exception) {}
        layout = null
    }

    private fun applyAspect() {
        if (layout == null) return
        try {
            mp.setVideoScale(when (aspectMode) {
                1 -> MediaPlayer.ScaleType.SURFACE_FILL
                2 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                3 -> MediaPlayer.ScaleType.SURFACE_16_9
                4 -> MediaPlayer.ScaleType.SURFACE_4_3
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            })
        } catch (e: Exception) {}
    }

    override fun load(st: Stream, url: String, resumeMs: Long) {
        playedOnce = false; buffering = true
        audioDelayMs = 0L; subDelayMs = 0L
        val m = Media(lib, Uri.parse(url))
        m.setHWDecoderEnabled(s.hw.on, false)
        m.addOption(":network-caching=$cache")
        m.addOption(":http-user-agent=${st.ua}")
        if (resumeMs > 5000) m.addOption(":start-time=${resumeMs / 1000}")
        mp.setMedia(m)
        m.release()
        mp.play()
        applyAspect()
    }

    override fun setPlaying(p: Boolean) { try { if (p) mp.play() else mp.pause() } catch (e: Exception) {} }
    override fun seekTo(ms: Long) { try { mp.setTime(ms) } catch (e: Exception) {} }
    override fun setSpeed(f: Float) { try { mp.setRate(f) } catch (e: Exception) {} }

    override fun audioTracks(): List<Track> = try {
        val arr = mp.audioTracks
        val cur = mp.audioTrack
        if (arr == null) emptyList() else arr.filter { it.id >= 0 }.map { Track(it.id, it.name ?: "Audio ${it.id}", it.id == cur) }
    } catch (e: Exception) { emptyList() }

    override fun subtitleTracks(): List<Track> = try {
        val arr = mp.spuTracks
        val cur = mp.spuTrack
        if (arr == null) emptyList() else arr.filter { it.id >= 0 }.map { Track(it.id, it.name ?: "Subtitle ${it.id}", it.id == cur) }
    } catch (e: Exception) { emptyList() }

    override fun selectAudio(id: Int) { try { mp.setAudioTrack(id) } catch (e: Exception) {} }
    override fun selectSubtitle(id: Int) { try { mp.setSpuTrack(id) } catch (e: Exception) {} }
    override fun setAspect(mode: Int) { aspectMode = mode; applyAspect() }
    override fun supportsDelay() = true

    override fun addAudioDelay(ms: Long): Long {
        audioDelayMs += ms
        try { mp.setAudioDelay(audioDelayMs * 1000L) } catch (e: Exception) {}
        return audioDelayMs
    }

    override fun addSubtitleDelay(ms: Long): Long {
        subDelayMs += ms
        try { mp.setSpuDelay(subDelayMs * 1000L) } catch (e: Exception) {}
        return subDelayMs
    }

    override fun release() {
        onError = null; onEnded = null; onRebuffer = null
        try { mp.setEventListener(null) } catch (e: Exception) {}
        try { mp.stop() } catch (e: Exception) {}
        detach()
        val p = mp
        val l = lib
        Thread { try { p.release(); l.release() } catch (t: Throwable) {} }.start() // never block the UI thread
    }
}
