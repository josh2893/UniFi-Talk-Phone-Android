package au.josh.unifiphone.core.engine

import java.io.ByteArrayOutputStream

/**
 * RFC 7798 (RTP payload format for HEVC).
 *
 * Packetizer: Annex-B access unit -> RTP payloads.
 *   - NAL <= mtu: single NAL unit packet (payload = the NAL, incl. 2-byte NAL header)
 *   - NAL >  mtu: FU packets (PayloadHdr type=49 + FU header S/E + FuType)
 * Marker bit is set on the last packet of the access unit.
 *
 * Depacketizer: RTP payloads -> Annex-B access units for MediaCodec.
 * Tracks sequence gaps and signals when a keyframe (PLI) should be requested.
 */
object H265Rtp {

    const val MTU_PAYLOAD = 1200
    private const val FU_TYPE = 49

    /** Split an Annex-B buffer into NAL units (without start codes). */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        var start = -1
        while (i + 3 <= data.size) {
            val isStart4 = i + 4 <= data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (isStart4 || isStart3) {
                if (start >= 0) nals.add(data.copyOfRange(start, i))
                i += if (isStart4) 4 else 3
                start = i
            } else i++
        }
        if (start in 0 until data.size) nals.add(data.copyOfRange(start, data.size))
        return nals.filter { it.size >= 2 }
    }

    fun nalType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3F

    fun isKeyframeNal(type: Int): Boolean = type in 16..21 // BLA/IDR/CRA
    fun isParameterSet(type: Int): Boolean = type in 32..34 // VPS/SPS/PPS

    /** Packetize one access unit; invoke [emit] per RTP payload. */
    fun packetize(accessUnit: ByteArray, emit: (payload: ByteArray, marker: Boolean) -> Unit) {
        val nals = splitAnnexB(accessUnit)
        for ((idx, nal) in nals.withIndex()) {
            val last = idx == nals.lastIndex
            if (nal.size <= MTU_PAYLOAD) {
                emit(nal, last)
            } else {
                val type = nalType(nal)
                val layerTid = byteArrayOf(
                    (((FU_TYPE shl 1) or ((nal[0].toInt() shr 0) and 0x01))).toByte(),
                    nal[1],
                )
                var off = 2 // skip original 2-byte NAL header; carried in FU header
                var first = true
                while (off < nal.size) {
                    val take = minOf(MTU_PAYLOAD - 3, nal.size - off)
                    val end = off + take >= nal.size
                    val fu = ByteArray(3 + take)
                    fu[0] = layerTid[0]
                    fu[1] = layerTid[1]
                    fu[2] = ((if (first) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
                    System.arraycopy(nal, off, fu, 3, take)
                    emit(fu, last && end)
                    off += take
                    first = false
                }
            }
        }
    }

    /**
     * Stateful depacketizer. Feed RTP payloads in arrival order; emits complete
     * Annex-B access units. Not resilient to reordering — on a LAN with a
     * single switch hop that's an acceptable v1 tradeoff.
     */
    class Depacketizer(
        private val onAccessUnit: (data: ByteArray, isKeyframe: Boolean) -> Unit,
        private val onNeedKeyframe: () -> Unit,
    ) {
        private val au = ByteArrayOutputStream()
        private var auHasKeyframe = false
        private var expectedSeq = -1
        private var fuBuffer: ByteArrayOutputStream? = null
        private var fuType = 0
        private var corrupted = false
        private val startCode = byteArrayOf(0, 0, 0, 1)

        fun push(seq: Int, marker: Boolean, payload: ByteArray) {
            if (expectedSeq >= 0 && seq != (expectedSeq + 1) and 0xFFFF) {
                // Loss: drop in-progress state, wait for next keyframe.
                corrupted = true
                fuBuffer = null
                au.reset(); auHasKeyframe = false
                onNeedKeyframe()
            }
            expectedSeq = seq
            if (payload.size < 2) return
            val type = (payload[0].toInt() shr 1) and 0x3F

            when (type) {
                48 -> { /* AP aggregation packets: handsets not observed sending these; skip */ }
                FU_TYPE -> {
                    if (payload.size < 3) return
                    val fuHdr = payload[2].toInt()
                    val startBit = fuHdr and 0x80 != 0
                    val endBit = fuHdr and 0x40 != 0
                    val origType = fuHdr and 0x3F
                    if (startBit) {
                        fuBuffer = ByteArrayOutputStream()
                        fuType = origType
                        // Reconstruct 2-byte NAL header from PayloadHdr layer/tid + orig type
                        val b0 = ((origType shl 1) or (payload[0].toInt() and 0x01)).toByte()
                        fuBuffer!!.write(byteArrayOf(b0, payload[1]))
                    }
                    fuBuffer?.write(payload, 3, payload.size - 3)
                    if (endBit) {
                        fuBuffer?.let { appendNal(it.toByteArray()) }
                        fuBuffer = null
                    }
                }
                else -> appendNal(payload)
            }

            if (marker) flush()
        }

        private fun appendNal(nal: ByteArray) {
            val t = nalType(nal)
            if (isKeyframeNal(t)) { auHasKeyframe = true; corrupted = false }
            if (corrupted && !isParameterSet(t) && !auHasKeyframe) return
            au.write(startCode)
            au.write(nal)
        }

        private fun flush() {
            if (au.size() == 0) return
            if (!corrupted) onAccessUnit(au.toByteArray(), auHasKeyframe)
            au.reset()
            auHasKeyframe = false
        }
    }
}
