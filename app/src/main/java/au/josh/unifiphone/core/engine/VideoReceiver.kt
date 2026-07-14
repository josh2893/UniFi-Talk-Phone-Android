package au.josh.unifiphone.core.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP H.265 -> MediaCodec hardware decoder -> Surface.
 *
 * The Talk handsets stream Annex-B with VPS/SPS/PPS preceding each IDR, so
 * the decoder is configured without csd buffers and started once the first
 * keyframe access unit arrives. On packet loss the depacketizer discards
 * until the next keyframe and we fire a PLI at the sender.
 */
class VideoReceiver(private val rtp: RtpSession) {

    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val queue = LinkedBlockingQueue<ByteArray>(60)
    private var seenKeyframe = false
    private var lastPliMs = 0L

    private val depacketizer = H265Rtp.Depacketizer(
        onAccessUnit = { data, keyframe ->
            if (!seenKeyframe && !keyframe) { requestKeyframe(); return@Depacketizer }
            seenKeyframe = true
            if (!queue.offer(data)) { queue.clear(); queue.offer(data) }
        },
        onNeedKeyframe = { seenKeyframe = false; requestKeyframe() },
    )

    fun attachSurface(s: Surface) {
        surface = s
        if (running.get() && codec == null) startCodec()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (surface != null) startCodec()
        requestKeyframe()
    }

    fun onRtpVideo(seq: Int, marker: Boolean, payload: ByteArray) {
        if (!running.get()) return
        depacketizer.push(seq, marker, payload)
    }

    private fun startCodec() {
        val s = surface ?: return
        EngineLog.d("VIDEO-RX: starting HEVC decoder")
        runCatching {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            c.configure(fmt, s, null, 0)
            c.start()
            codec = c
            Thread(::decodeLoop, "h265-decode").start()
        }
    }

    private fun decodeLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        while (running.get()) {
            val au = queue.poll(100, TimeUnit.MILLISECONDS) ?: run {
                drainOutput(c, info); null
            } ?: continue
            try {
                val inIdx = c.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx) ?: continue
                    buf.clear(); buf.put(au)
                    c.queueInputBuffer(inIdx, 0, au.size, pts, 0)
                    pts += 33_000
                }
                drainOutput(c, info)
            } catch (e: Exception) {
                // Decoder in a bad state: reset and wait for a keyframe.
                EngineLog.d("VIDEO-RX: decoder error ${e.javaClass.simpleName}, flushing + PLI")
                runCatching { c.flush() }
                seenKeyframe = false
                requestKeyframe()
            }
        }
    }

    private fun drainOutput(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outIdx = try { c.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            if (outIdx < 0) return
            c.releaseOutputBuffer(outIdx, true) // render to surface
        }
    }

    private fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastPliMs > 500) {
            lastPliMs = now
            EngineLog.d("VIDEO-RX: sending PLI (keyframe request)")
            rtp.sendPli()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { codec?.stop(); codec?.release() }
        codec = null
        queue.clear()
        seenKeyframe = false
    }
}
