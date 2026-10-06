package com.streamtv.iptv

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class EnhanceParams(val sharp: Float, val contrast: Float, val sat: Float)

/** Picks the strength from the preset; "auto" adapts to the source resolution (small pictures get more sharpening). */
fun enhanceParams(mode: String, videoHeight: Int): EnhanceParams = when (mode) {
    "soft" -> EnhanceParams(0.30f, 1.03f, 1.05f)
    "sharp" -> EnhanceParams(0.85f, 1.06f, 1.10f)
    "cinema" -> EnhanceParams(0.50f, 1.12f, 1.05f)
    "vivid" -> EnhanceParams(0.60f, 1.08f, 1.25f)
    else -> when {
        videoHeight in 1..480 -> EnhanceParams(0.90f, 1.06f, 1.12f)
        videoHeight in 481..720 -> EnhanceParams(0.65f, 1.05f, 1.10f)
        else -> EnhanceParams(0.40f, 1.03f, 1.06f)
    }
}

/**
 * Real-time GPU video enhancer: ExoPlayer renders into our own SurfaceTexture, an OpenGL ES 2 pass applies
 * adaptive sharpening (contrast-adaptive, CAS-like) + contrast + saturation and draws into the TextureView.
 * If no frame is drawn within 5 s, or GL fails, [onFail] is called so the caller can fall back to plain playback.
 */
class VideoEnhancer(private val onSurface: (Surface) -> Unit, private val onFail: () -> Unit) : TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var inST: SurfaceTexture? = null
    private var inSurface: Surface? = null
    @Volatile private var width = 1
    @Volatile private var height = 1
    @Volatile var params = EnhanceParams(0.5f, 1.05f, 1.1f)
    @Volatile var videoW = 1280
    @Volatile var videoH = 720
    @Volatile private var frames = 0
    @Volatile private var failed = false
    private val tm = FloatArray(16)
    private var aPos = 0
    private var aTex = 0
    private var uMat = 0
    private var uTex = 0
    private var uTexel = 0
    private var uSharp = 0
    private var uContrast = 0
    private var uSat = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, hh: Int) { width = w; height = hh; start(surface) }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, hh: Int) { width = w; height = hh }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { stop(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun start(out: SurfaceTexture) {
        if (thread != null) return
        val t = HandlerThread("enhancer").also { it.start() }
        thread = t
        val hd = Handler(t.looper)
        h = hd
        hd.post {
            try {
                initGl(out)
                val s = Surface(inST)
                inSurface = s
                main.post { onSurface(s) }
                hd.postDelayed({ if (frames == 0) fail() }, 5000)
            } catch (e: Throwable) { fail() }
        }
    }

    private fun fail() {
        if (failed) return
        failed = true
        main.post { onFail() }
    }

    private fun initGl(out: SurfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfgs, 0, 1, n, 0)
        val cfg = cfgs[0] ?: error("EGL config")
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, out, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos"); aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMat = GLES20.glGetUniformLocation(program, "uMat"); uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel"); uSharp = GLES20.glGetUniformLocation(program, "uSharp")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast"); uSat = GLES20.glGetUniformLocation(program, "uSat")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({ h?.post { draw() } }, h)
        inST = st
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun draw() {
        try {
            val st = inST ?: return
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            st.updateTexImage()
            st.getTransformMatrix(tm)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glUniformMatrix4fv(uMat, 1, false, tm, 0)
            val p = params
            GLES20.glUniform2f(uTexel, 1f / videoW.coerceAtLeast(1), 1f / videoH.coerceAtLeast(1))
            GLES20.glUniform1f(uSharp, p.sharp)
            GLES20.glUniform1f(uContrast, p.contrast)
            GLES20.glUniform1f(uSat, p.sat)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            frames++
        } catch (e: Throwable) { fail() }
    }

    fun stop() {
        val hd = h
        val t = thread
        h = null; thread = null
        if (hd == null) return
        hd.post {
            try {
                inST?.setOnFrameAvailableListener(null)
                inSurface?.release(); inST?.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Throwable) {}
            eglDisplay = EGL14.EGL_NO_DISPLAY
            inST = null; inSurface = null
            t?.quitSafely()
        }
    }

    companion object {
        private const val VS = "attribute vec4 aPos; attribute vec2 aTex; uniform mat4 uMat; varying vec2 vTex;\n" +
            "void main(){ gl_Position = aPos; vTex = (uMat * vec4(aTex, 0.0, 1.0)).xy; }"
        private const val FS = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex; uniform samplerExternalOES uTex; uniform vec2 uTexel;\n" +
            "uniform float uSharp; uniform float uContrast; uniform float uSat;\n" +
            "void main(){\n" +
            "  vec3 c = texture2D(uTex, vTex).rgb;\n" +
            "  vec3 n = texture2D(uTex, vTex + vec2(0.0, -uTexel.y)).rgb;\n" +
            "  vec3 s = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;\n" +
            "  vec3 e = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb;\n" +
            "  vec3 w = texture2D(uTex, vTex + vec2(-uTexel.x, 0.0)).rgb;\n" +
            "  float mn = min(min(min(n.g, s.g), min(e.g, w.g)), c.g);\n" +
            "  float mx = max(max(max(n.g, s.g), max(e.g, w.g)), c.g);\n" +
            "  float amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 0.0001), 0.0, 1.0));\n" +
            "  float k = -amp * mix(0.125, 0.2, uSharp);\n" +
            "  vec3 o = (c + (n + s + e + w) * k) / (1.0 + 4.0 * k);\n" +
            "  o = (o - 0.5) * uContrast + 0.5;\n" +
            "  float l = dot(o, vec3(0.299, 0.587, 0.114));\n" +
            "  o = mix(vec3(l), o, uSat);\n" +
            "  gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);\n" +
            "}\n"
    }
}
