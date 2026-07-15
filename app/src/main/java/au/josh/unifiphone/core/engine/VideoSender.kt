package au.josh.unifiphone.core.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 -> MediaCodec HEVC encoder (input surface) -> RFC 7798 packetizer -> RTP.
 *
 * 640x360 @ 15fps, ~700 kbps: comfortably decodable on a UTP-Touch and cheap
 * on a kiosk tablet. PLI/FIR from the far end forces an IDR via
 * PARAMETER_KEY_REQUEST_SYNC_FRAME. Codec-config (VPS/SPS/PPS) is cached and
 * prepended to every keyframe so a late-joining decoder can always sync.
 */
class VideoSender(
    private val context: Context,
    private val rtp: RtpSession,
    private val tuning: Tuning = Tuning(),
) {
    /** Live video-tuning knobs, read from settings at call setup. */
    data class Tuning(
        val rotationOffset: Int = 0,
        val extraMirror: Boolean = false,
        val useFrontCamera: Boolean = true,
        val resolutionShortEdge: Int = 0, // 0 = auto
        val bitrateKbps: Int = 800,
        val scaleMode: String = "fill",   // "fill" (crop) or "fit" (letterbox)
    )

    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var glBridge: GlRotationBridge? = null
    private var configData: ByteArray? = null
    private var timestamp = 0L // 90 kHz

    fun requestKeyframe() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    @SuppressLint("MissingPermission")
    fun start(useFrontCamera: Boolean = tuning.useFrontCamera) {
        if (!running.compareAndSet(false, true)) return
        try {
            cameraThread = HandlerThread("cam").apply { start() }
            val handler = Handler(cameraThread!!.looper)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val wantFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            val camId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wantFacing
            } ?: cm.cameraIdList.firstOrNull()
            if (camId == null) { EngineLog.d("VIDEO-TX: no camera on device"); stop(); return }

            val chars = cm.getCameraCharacteristics(camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // CAPTURE at the sensor's widest full field-of-view size (largest area
            // at the native aspect). Picking a small size makes Camera2 hand back a
            // cropped, zoomed-in region — which is why faces looked telephoto vs the
            // stock camera app. We downscale to the encode size in the GL bridge.
            val camSize = pickCaptureSize(map)
            // ENCODE at the requested target (short edge), keeping capture aspect.
            val encSize = pickEncodeSize(camSize)

            // The sensor is mounted landscape; the handset expects upright portrait.
            // SENSOR_ORIENTATION is the clockwise rotation needed to make the sensor
            // image upright for a device held in its natural (portrait) orientation.
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
            // Front cameras are mirrored, so the upright rotation runs OPPOSITE to
            // the sensor constant — using sensorOrientation directly comes out 180
            // off. Back cameras use it as-is. Manual offset handles odd mounts.
            val base = if (isFront) (360 - sensorOrientation) else sensorOrientation
            val rotation = (((base + tuning.rotationOffset) % 360) + 360) % 360
            val mirror = isFront xor tuning.extraMirror
            // Rotating 90/270 swaps the encoded frame dimensions.
            val encW = if (rotation % 180 == 0) encSize.width else encSize.height
            val encH = if (rotation % 180 == 0) encSize.height else encSize.width

            EngineLog.d(
                "VIDEO-TX: camera $camId capture ${camSize.width}x${camSize.height} " +
                    "encode ${encSize.width}x${encSize.height} rot=$rotation " +
                    "front=$isFront -> frame ${encW}x${encH}"
            )

            val fmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC, encW, encH
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, tuning.bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = enc.createInputSurface()
            enc.start()
            codec = enc
            Thread(::drainLoop, "h265-encode").start()

            // Camera renders into the GL bridge, which rotates each frame and
            // draws it into the encoder surface. Encoding the camera surface
            // directly is what produced the sideways image on the handset.
            val bridge = GlRotationBridge(
                encoderSurface = encoderSurface,
                outWidth = encW,
                outHeight = encH,
                rotationDegrees = rotation,
                mirror = mirror,
                scaleFill = tuning.scaleMode == "fill",
                srcWidth = camSize.width,
                srcHeight = camSize.height,
            )
            glBridge = bridge

            bridge.start { camTarget ->
                cm.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(dev: CameraDevice) {
                        EngineLog.d("VIDEO-TX: camera opened")
                        camera = dev
                        dev.createCaptureSession(
                            listOf(camTarget),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    EngineLog.d("VIDEO-TX: capture session configured, streaming")
                                    session = s
                                    val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    req.addTarget(camTarget)
                                    // Force full-sensor readout: no digital zoom / crop.
                                    chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                                        ?.let { req.set(CaptureRequest.SCALER_CROP_REGION, it) }
                                    runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                                        .onFailure { EngineLog.d("VIDEO-TX: setRepeatingRequest failed: ${it.message}") }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    EngineLog.d("VIDEO-TX: capture session CONFIGURE FAILED"); stop()
                                }
                            },
                            handler,
                        )
                    }
                    override fun onDisconnected(dev: CameraDevice) { EngineLog.d("VIDEO-TX: camera disconnected"); stop() }
                    override fun onError(dev: CameraDevice, error: Int) { EngineLog.d("VIDEO-TX: camera error $error"); stop() }
                }, handler)
            }
        } catch (e: Exception) {
            EngineLog.d("VIDEO-TX: start failed ${e.javaClass.simpleName}: ${e.message} " +
                "(SecurityException here = CAMERA permission not granted)")
            stop()
        }
    }

    /**
     * Choose a real, advertised output size close to 640x360, preferring 16:9-ish
     * and capping at 1280x720 so the handset's decoder isn't overwhelmed.
     */
    /** Widest full-FOV capture size: largest area, capped so encode stays cheap. */
    private fun pickCaptureSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(MediaCodec::class.java)?.toList()
            ?: map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList()
            ?: return Size(1280, 960)
        // Prefer the largest advertised size at or below 1280 on the long edge —
        // big enough for full field of view, small enough to stay efficient.
        val capped = sizes.filter { maxOf(it.width, it.height) <= 1280 }
            .ifEmpty { sizes }
        return capped.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 960)
    }

    /** Encode size: scale the capture size down so its short edge ~= target. */
    private fun pickEncodeSize(capture: Size): Size {
        val target = if (tuning.resolutionShortEdge > 0) tuning.resolutionShortEdge else 480
        val shortEdge = minOf(capture.width, capture.height)
        if (shortEdge <= target) return capture
        val scale = target.toDouble() / shortEdge
        // Keep dimensions even (H.265 requires it).
        fun even(v: Int) = (v / 2) * 2
        return Size(even((capture.width * scale).toInt()), even((capture.height * scale).toInt()))
    }

    private fun drainLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val idx = try { c.dequeueOutputBuffer(info, 100_000) } catch (_: Exception) { return }
            if (idx < 0) continue
            val buf = c.getOutputBuffer(idx) ?: continue
            val data = ByteArray(info.size)
            buf.position(info.offset); buf.get(data)
            c.releaseOutputBuffer(idx, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                configData = data // VPS/SPS/PPS Annex-B
                EngineLog.d("VIDEO-TX: got codec config (${data.size} B)")
                continue
            }
            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (timestamp == 0L) EngineLog.d("VIDEO-TX: first frame out (key=$isKey)")
            val au = if (isKey && configData != null) configData!! + data else data
            timestamp += 90_000 / 15
            H265Rtp.packetize(au) { payload, marker ->
                rtp.send(Sdp.PT_H265, marker, timestamp, payload)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { codec?.stop(); codec?.release() }
        runCatching { glBridge?.release() }
        glBridge = null
        runCatching { previewSurface?.release() }
        runCatching { previewTexture?.release() }
        runCatching { cameraThread?.quitSafely() }
        session = null; camera = null; codec = null; cameraThread = null
        previewSurface = null; previewTexture = null
    }
}
