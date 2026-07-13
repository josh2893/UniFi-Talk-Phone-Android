package au.josh.unifiphone.core.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
class VideoSender(private val context: Context, private val rtp: RtpSession) {

    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var configData: ByteArray? = null
    private var timestamp = 0L // 90 kHz

    fun requestKeyframe() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    @SuppressLint("MissingPermission")
    fun start(useFrontCamera: Boolean = true) {
        if (!running.compareAndSet(false, true)) return
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 640, 360).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 700_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            enc.start()
            codec = enc
            Thread(::drainLoop, "h265-encode").start()

            cameraThread = HandlerThread("cam").apply { start() }
            val handler = Handler(cameraThread!!.looper)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val wantFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            val camId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wantFacing
            } ?: cm.cameraIdList.firstOrNull() ?: return

            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    camera = dev
                    dev.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            req.addTarget(inputSurface)
                            runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = stop()
                    }, handler)
                }
                override fun onDisconnected(dev: CameraDevice) = stop()
                override fun onError(dev: CameraDevice, error: Int) = stop()
            }, handler)
        } catch (_: Exception) {
            stop()
        }
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
                continue
            }
            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
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
        runCatching { cameraThread?.quitSafely() }
        session = null; camera = null; codec = null; cameraThread = null
    }
}
