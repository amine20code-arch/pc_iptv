package com.streamtv.iptv

import android.app.AlarmManager
import android.app.Application
import android.app.Instrumentation
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import java.io.File
import kotlin.system.exitProcess

enum class AppTheme(val label: String, val bg: Color, val bg2: Color, val primary: Color, val card: Color) {
    MIDNIGHT("Bleu Minuit", Color(0xFF0B1530), Color(0xFF050A1A), Color(0xFF2E7BFF), Color(0xFF17294F)),
    AMOLED("Noir Amoled", Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFFE50914), Color(0xFF161616)),
    NEON("Néon Verre", Color(0xFF140B33), Color(0xFF0A0420), Color(0xFFB026FF), Color(0xFF2B1B58)),
    EMERALD("Émeraude Sport", Color(0xFF07150F), Color(0xFF020806), Color(0xFF00E58F), Color(0xFF123226)),
    GOLD("Or Royal", Color(0xFF17130A), Color(0xFF0A0804), Color(0xFFD4AF37), Color(0xFF2C2616)),
    SUNSET("Coucher de soleil", Color(0xFF2B1308), Color(0xFF120804), Color(0xFFFF7A33), Color(0xFF45220F)),
    OCEAN("Océan", Color(0xFF062029), Color(0xFF030F14), Color(0xFF22D3EE), Color(0xFF0F3846)),
    ROSE("Rose Cinéma", Color(0xFF210A1A), Color(0xFF0E040B), Color(0xFFF43F8E), Color(0xFF3D1633))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamTheme(t: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = t.primary, background = t.bg, surface = t.card, surfaceVariant = t.card.copy(alpha = 0.62f))) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = t.primary)) {
            Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.bg, t.bg2)))) { content() }
            }
        }
    }
}

/**
 * Memory-aware image loading + crash guard: an unexpected crash is written to a file, the app restarts itself
 * and shows the error once, so it never just disappears.
 */
class StreamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        installCrashGuard()
    }

    private fun installCrashGuard() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(Log.getStackTraceString(e))
                val sp = getSharedPreferences("crash", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val last = sp.getLong("ts", 0L)
                sp.edit().putLong("ts", now).commit()
                if (now - last > 20_000) { // avoid restart loops
                    val i = packageManager.getLaunchIntentForPackage(packageName)
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
                        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, now + 700, pi)
                    }
                }
            } catch (t: Throwable) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
        .bitmapConfig(Bitmap.Config.RGB_565).crossfade(false).build()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) { imageLoader.memoryCache?.clear(); System.gc() }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var server: RemoteServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRemote()
        androidx.core.content.ContextCompat.registerReceiver(this, InstallReceiver(), android.content.IntentFilter(Updater.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if (vm.settings.updateRepo.isNotBlank()) vm.checkUpdate(this, true)
        val crashFile = File(filesDir, "last_crash.txt")
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val profile by vm.profile.collectAsStateWithLifecycle()
            var crash by remember { mutableStateOf(if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null) }
            val s = vm.session
            LaunchedEffect(Unit) {
                RemoteBus.play.collect { u ->
                    s.play(listOf(ChannelEntity(playlistId = 0, kind = "live", name = "Phone cast", streamUrl = u)), 0, full = true, preview = false)
                }
            }
            LaunchedEffect(Unit) { RemoteBus.file.collect { (path, name) -> vm.importPath(path, name) } }
            StreamTheme(theme) {
                when {
                    s.fullscreen && s.item != null -> FullPlayer(vm)
                    profile == null -> ProfileScreen(vm)
                    else -> HomeScreen(vm)
                }
                crash?.let { text ->
                    Dialog(onDismissRequest = { crashFile.delete(); crash = null }) {
                        Column(Modifier.width(700.dp).background(Color(0xEE111111)).padding(20.dp)) {
                            Text("L'application a redémarré après un problème. Envoyez ce texte au développeur :", fontSize = 14.sp)
                            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                                Text(text.take(1800), fontSize = 10.sp, color = Color(0xFFFF9999))
                            }
                            Button(onClick = { crashFile.delete(); crash = null }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }

    private fun startRemote() {
        val token = (1000..9999).random().toString()
        try {
            val srv = RemoteServer(8080, token, cacheDir) { code -> Thread { try { Instrumentation().sendKeyDownUpSync(code) } catch (e: Exception) {} }.start() }
            srv.start(); server = srv
            RemoteInfo.url = "http://${localIp()}:8080/?k=$token"
        } catch (e: Exception) { RemoteInfo.url = "" }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.session.fullscreen && vm.session.item != null) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.session.inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        val pip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!pip) vm.session.engine?.setPlaying(false) // never keep playing in the background
    }

    override fun onStart() {
        super.onStart()
        if (vm.session.item != null) vm.session.engine?.setPlaying(true)
    }

    // Focus-loss safety net: a D-pad key with nothing focused re-grabs focus instead of leaving the UI stuck.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && window.decorView.findFocus() == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> window.decorView.requestFocus()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
}
