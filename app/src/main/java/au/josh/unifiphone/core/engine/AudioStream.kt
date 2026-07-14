package au.josh.unifiphone.core.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional G.711 µ-law audio over an RtpSession.
 * 8 kHz mono, 20 ms frames (160 samples / 160 bytes PCMU).
 *
 * Uses VOICE_COMMUNICATION source + platform AEC/NS, which is what keeps a
 * kiosk speakerphone usable — same echo problem as the doorbell talkback,
 * solved here by the Android audio stack.
 */
class AudioStream(private val rtp: RtpSession) {

    private val running = AtomicBoolean(false)
    @Volatile var muted = false

    /** Negotiated codec for sending: 0 = PCMU, 8 = PCMA. Set from the SDP answer/offer. */
    @Volatile var txPayloadType = Sdp.PT_PCMU

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var timestamp = 0L
    private var dtmfQueue = ArrayDeque<Int>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return

        val minRec = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, 3200)
        ).also { r ->
            if (AcousticEchoCanceler.isAvailable())
                runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true }
            if (NoiseSuppressor.isAvailable())
                runCatching { NoiseSuppressor.create(r.audioSessionId)?.enabled = true }
        }

        val minPlay = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(8000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minPlay, 3200), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track?.play()
        record?.startRecording()

        Thread(::sendLoop, "audio-tx").start()
    }

    /** Called from RtpSession receive path. Accepts PCMU (0) and PCMA (8). */
    fun onRtpAudio(payloadType: Int, payload: ByteArray) {
        val pcm = when (payloadType) {
            Sdp.PT_PCMU -> G711.decodeMuLaw(payload, 0, payload.size)
            Sdp.PT_PCMA -> G711.decodeALaw(payload, 0, payload.size)
            else -> return // telephone-event, CN, etc.
        }
        track?.write(pcm, 0, pcm.size)
    }

    fun sendDtmf(digit: Char) {
        val ev = when (digit) {
            in '0'..'9' -> digit - '0'
            '*' -> 10; '#' -> 11
            else -> return
        }
        synchronized(dtmfQueue) { dtmfQueue.add(ev) }
    }

    private fun sendLoop() {
        val pcm = ShortArray(160)
        while (running.get()) {
            val rec = record ?: return
            var read = 0
            while (read < 160 && running.get()) {
                val n = rec.read(pcm, read, 160 - read)
                if (n <= 0) break
                read += n
            }
            if (read < 160) continue
            timestamp += 160

            val pendingDtmf = synchronized(dtmfQueue) { dtmfQueue.removeFirstOrNull() }
            if (pendingDtmf != null) {
                sendDtmfBurst(pendingDtmf)
                continue
            }

            if (muted) {
                // Send comfort silence to keep the stream alive.
                for (i in pcm.indices) pcm[i] = 0
            }
            val encoded = if (txPayloadType == Sdp.PT_PCMA) G711.encodeALaw(pcm, 160)
            else G711.encodeMuLaw(pcm, 160)
            rtp.send(txPayloadType, false, timestamp, encoded)
        }
    }

    /** RFC 2833/4733: ~160 ms event, 8 update packets + 3 end packets. */
    private fun sendDtmfBurst(event: Int) {
        var duration = 0
        val startTs = timestamp
        fun pkt(end: Boolean): ByteArray = byteArrayOf(
            event.toByte(),
            ((if (end) 0x80 else 0) or 10).toByte(), // volume 10
            (duration shr 8).toByte(), duration.toByte(),
        )
        for (i in 0 until 8) {
            duration += 160
            rtp.send(Sdp.PT_DTMF, i == 0, startTs, pkt(false))
            Thread.sleep(20)
        }
        duration += 160
        repeat(3) { rtp.send(Sdp.PT_DTMF, false, startTs, pkt(true)) }
        timestamp += duration
    }

    fun stop() {
        running.set(false)
        runCatching { record?.stop(); record?.release() }
        runCatching { track?.stop(); track?.release() }
        record = null; track = null
    }
}
