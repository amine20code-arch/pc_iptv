package com.streamtv.iptv

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object RemoteBus {
    val text = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val play = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val file = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 2) // (path, original name)
}

object RemoteInfo { var url by mutableStateOf("") }

fun localIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "0.0.0.0"
} catch (e: Exception) { "0.0.0.0" }

fun qr(text: String, size: Int): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Local HTTP server: a phone on the same Wi-Fi opens the QR link and gets a D-pad, keyboard, link cast and M3U upload page. */
class RemoteServer(port: Int, private val token: String, private val dir: java.io.File, private val onKey: (Int) -> Unit) : NanoHTTPD(port) {
    override fun serve(s: IHTTPSession): Response {
        val p = s.parms
        if (p["k"] != token) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Accès refusé")
        return when (s.uri) {
            "/key" -> { p["c"]?.toIntOrNull()?.let(onKey); newFixedLengthResponse("ok") }
            "/text" -> { p["t"]?.let { RemoteBus.text.tryEmit(it) }; newFixedLengthResponse("ok") }
            "/play" -> { p["u"]?.let { RemoteBus.play.tryEmit(it) }; newFixedLengthResponse("ok") }
            "/upload" -> {
                try {
                    val files = HashMap<String, String>()
                    s.parseBody(files)
                    val tmp = files["file"]
                    if (tmp != null) {
                        val name = s.parms["file"] ?: "liste.m3u"
                        val copy = java.io.File(dir, "upload_" + System.currentTimeMillis() + ".m3u")
                        java.io.File(tmp).copyTo(copy, overwrite = true)
                        RemoteBus.file.tryEmit(copy.absolutePath to name)
                    }
                    newFixedLengthResponse("ok")
                } catch (e: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "erreur") }
            }
            else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
        }
    }

    companion object {
        private const val PAGE = """<!doctype html><html lang=fr><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{background:#0b1020;color:#fff;font-family:sans-serif;text-align:center;margin:0;padding:12px}button{width:84px;height:64px;margin:5px;font-size:20px;border-radius:14px;border:0;background:#2e7bff;color:#fff}
input{width:68%;padding:12px;font-size:16px;border-radius:10px;border:0}.w{width:150px}h3{margin:18px 0 6px;color:#9fb4ff}</style></head><body>
<h2>Télécommande Stream TV</h2>
<div><button onclick="s(19)">&#9650;</button></div>
<div><button onclick="s(21)">&#9664;</button><button onclick="s(23)">OK</button><button onclick="s(22)">&#9654;</button></div>
<div><button onclick="s(20)">&#9660;</button></div>
<div><button class=w onclick="s(4)">Retour</button><button class=w onclick="s(82)">Menu</button></div>
<div><button onclick="s(166)">CH+</button><button onclick="s(167)">CH-</button><button onclick="s(85)">Lecture</button></div>
<h3>Saisir du texte</h3><input id=t><button onclick="t()">Envoyer</button>
<h3>Lire un lien direct</h3><input id=u placeholder="http://..."><button onclick="p()">Lire</button>
<h3>Envoyer un fichier M3U</h3><input type=file id=f><button onclick="up()">Envoyer</button><div id=m></div>
<script>
var k=new URLSearchParams(location.search).get('k');
function s(c){fetch('/key?k='+k+'&c='+c)}
function t(){var e=document.getElementById('t');fetch('/text?k='+k+'&t='+encodeURIComponent(e.value));e.value=''}
function p(){fetch('/play?k='+k+'&u='+encodeURIComponent(document.getElementById('u').value))}
function up(){var f=document.getElementById('f').files[0];if(!f){document.getElementById('m').textContent='Choisissez un fichier';return}
var d=new FormData();d.append('file',f);document.getElementById('m').textContent='Envoi...';
fetch('/upload?k='+k,{method:'POST',body:d}).then(function(){document.getElementById('m').textContent='Fichier envoyé, importation sur la TV'}).catch(function(){document.getElementById('m').textContent='Échec de l\'envoi'})}
</script></body></html>"""
    }
}
