package com.streamtv.iptv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader

data class UpdateInfo(val build: Long, val name: String, val notes: String, val apkUrl: String)

/**
 * In-app updates. The source is either a GitHub repository ("user/repo", latest release with an .apk asset,
 * tag like b12) or a direct JSON link: {"build": 12, "name": "2.1", "notes": "...", "url": "https://.../app.apk"}.
 */
object Updater {
    const val ACTION = "com.streamtv.iptv.INSTALL_STATUS"

    fun currentBuild(ctx: Context): Long = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    } catch (e: Exception) { 0L }

    /** Returns the newer version when one exists, null when up to date. Throws on network / format errors. */
    suspend fun check(ctx: Context, source: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val src = source.trim()
        if (src.isEmpty()) error("Aucune source de mise à jour configurée")
        val url = if (src.startsWith("http")) src else "https://api.github.com/repos/$src/releases/latest"
        val o = Net.open(url, mapOf("Accept" to "application/json")).use { r ->
            JsonParser.parseReader(InputStreamReader(r.body!!.byteStream())).asJsonObject
        }
        val info = if (src.startsWith("http")) {
            UpdateInfo(o.str("build")?.toLongOrNull() ?: 0L, o.str("name") ?: "", o.str("notes") ?: "", o.str("url") ?: "")
        } else {
            val tag = o.str("tag_name") ?: ""
            val apk = o.getAsJsonArray("assets")?.map { it.asJsonObject }?.firstOrNull { (it.str("name") ?: "").endsWith(".apk", true) }
            UpdateInfo(tag.filter { it.isDigit() }.toLongOrNull() ?: 0L, o.str("name") ?: tag, o.str("body") ?: "", apk?.str("browser_download_url") ?: "")
        }
        if (info.apkUrl.isBlank()) error("Aucun fichier APK dans la version publiée")
        if (info.build > currentBuild(ctx)) info else null
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "update.apk")
        Net.open(url).use { r ->
            val body = r.body!!
            val total = body.contentLength()
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
        f
    }

    /** Opens the system page that allows this app to install packages (needed once). Returns true if already allowed. */
    fun ensureInstallPermission(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** Streams the APK into a PackageInstaller session; Android then asks the user to confirm. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ACTION).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the installer status and shows the system confirmation screen when needed. */
class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { context.startActivity(confirm) } }
        }
    }
}
