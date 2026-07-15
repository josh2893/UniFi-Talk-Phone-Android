package au.josh.unifiphone.core.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders camera frames into the encoder's input surface with a rotation applied.
 *
 * Needed because the camera sensor is landscape while the handset expects an
 * upright portrait frame. MediaFormat.KEY_ROTATION only writes container
 * metadata, which is meaningless for raw H.265 over RTP — the far end just sees
 * whatever pixels we encode. So the rotation has to happen for real, in the
 * pixel path, before the encoder sees the frame.
 *
 * camera -> SurfaceTexture (external OES) -> GL quad w/ rotation -> encoder Surface
 */
class GlRotationBridge(
    private val encoderSurface: Surface,
    /** Frame size fed to the encoder (already swapped for 90/270 rotation). */
    private val outWidth: Int,
    private val outHeight: Int,
    /** Degrees to rotate clockwise: 0, 90, 180, 270. */
    private val rotationDegrees: Int,
    /** Mirror horizontally (front camera). Applied before rotation. */
    private val mirror: Boolean,
    /** true = center-crop to fill; false = letterbox to fit. */
    private val scaleFill: Boolean = true,
    /** Percent width compensation applied after aspect correction. */
    private val stretchFixPercent: Int = 100,
    /** Source (camera) dimensions, for aspect-correct scaling into the frame. */
    private val srcWidth: Int = outWidth,
    private val srcHeight: Int = outHeight,
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTexMatrix = 0

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var texBuf: FloatBuffer
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** SurfaceTexture the camera renders into. Valid after [start]. */
    var cameraTexture: SurfaceTexture? = null
        private set
    var cameraSurface: Surface? = null
        private set

    fun start(onReady: (Surface) -> Unit) {
        thread = HandlerThread("gl-rotate").apply { start() }
        handler = Handler(thread!!.looper)
        handler!!.post {
            try {
                initEgl()
                initGl()
                cameraTexture = SurfaceTexture(texId).apply {
                    // Request full capture resolution from the camera; the GL draw
                    // downscales into the (smaller) encoder surface.
                    setDefaultBufferSize(srcWidth, srcHeight)
                    setOnFrameAvailableListener({ handler?.post { drawFrame() } }, handler)
                }
                cameraSurface = Surface(cameraTexture)
                EngineLog.d(
                    "VIDEO-TX: GL bridge src=${srcWidth}x${srcHeight} " +
                        "out=${outWidth}x${outHeight} rot=$rotationDegrees " +
                        "mirror=$mirror fill=$scaleFill stretchFix=$stretchFixPercent " +
                        "srcAspect=${"%.3f".format(srcWidth.toFloat()/srcHeight)} " +
                        "outAspect=${"%.3f".format(outWidth.toFloat()/outHeight)}"
                )
                onReady(cameraSurface!!)
            } catch (e: Exception) {
                EngineLog.d("VIDEO-TX: GL bridge failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun drawFrame() {
        val st = cameraTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            // Build the rotation (and optional mirror) as an MVP transform.
            Matrix.setIdentityM(mvpMatrix, 0)
            if (mirror) Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
            Matrix.rotateM(mvpMatrix, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
            // Aspect correction between the CAMERA buffer and the ENCODER frame.
            // After the rotation above, the source aspect is expressed in the
            // pre-rotation axes, so compare src (camera) to the encoder frame in
            // the same orientation. "fill" = cover (crop edges); "fit" = contain
            // (letterbox bars).
            val srcAspect = srcWidth.toFloat() / srcHeight
            val dstAspect = if (rotationDegrees % 180 == 0)
                outWidth.toFloat() / outHeight else outHeight.toFloat() / outWidth
            if (srcAspect > 0f && dstAspect > 0f) {
                val ratio = srcAspect / dstAspect
                val (sx, sy) = if (scaleFill) {
                    if (ratio > 1f) ratio to 1f else 1f to (1f / ratio)
                } else {
                    if (ratio > 1f) 1f to (1f / ratio) else ratio to 1f
                }
                Matrix.scaleM(mvpMatrix, 0, sx, sy, 1f)
            }
            val stretchFix = stretchFixPercent.coerceIn(40, 140) / 100f
            if (stretchFix != 1f) {
                val compensation = FloatArray(16)
                val adjusted = FloatArray(16)
                Matrix.setIdentityM(compensation, 0)
                Matrix.scaleM(compensation, 0, stretchFix, 1f, 1f)
                Matrix.multiplyMM(adjusted, 0, compensation, 0, mvpMatrix, 0)
                System.arraycopy(adjusted, 0, mvpMatrix, 0, adjusted.size)
            }

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

            vertexBuf.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(aPos)
            texBuf.position(0)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glEnableVertexAttribArray(aTex)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Timestamp the encoder frame from the camera's own clock.
            android.opengl.EGLExt.eglPresentationTimeANDROID(
                eglDisplay, eglSurface, st.timestamp
            )
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            EngineLog.d("VIDEO-TX: GL draw error: ${e.message}")
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3040 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )
        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface, surfAttribs, 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGl() {
        val vs = """
            uniform mat4 uMVP;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)

        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        vertexBuf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        texBuf = ByteBuffer.allocateDirect(texs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texs); position(0) }
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            EngineLog.d("VIDEO-TX: shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    fun release() {
        handler?.post {
            runCatching { GLES20.glDeleteProgram(program) }
            runCatching { cameraSurface?.release() }
            runCatching { cameraTexture?.release() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                runCatching { EGL14.eglTerminate(eglDisplay) }
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            cameraSurface = null
            cameraTexture = null
            thread?.quitSafely()
        }
    }
}
