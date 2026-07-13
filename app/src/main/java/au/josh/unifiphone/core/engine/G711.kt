package au.josh.unifiphone.core.engine

/**
 * G.711 µ-law (PCMU, payload type 0) encode/decode. Pure Kotlin, no deps.
 * 8 kHz mono 16-bit PCM <-> 8-bit µ-law.
 */
object G711 {

    private const val BIAS = 0x84
    private const val CLIP = 32635

    private val muLawDecodeTable = ShortArray(256) { i ->
        val u = i.inv() and 0xFF
        var t = ((u and 0x0F) shl 3) + BIAS
        t = t shl ((u and 0x70) shr 4)
        (if (u and 0x80 != 0) BIAS - t else t - BIAS).toShort()
    }

    fun encodeMuLaw(pcm: ShortArray, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) {
            var sample = pcm[i].toInt()
            val sign = if (sample < 0) 0x80 else 0
            if (sample < 0) sample = -sample
            if (sample > CLIP) sample = CLIP
            sample += BIAS
            var exponent = 7
            var mask = 0x4000
            while (exponent > 0 && sample and mask == 0) { exponent--; mask = mask shr 1 }
            val mantissa = (sample shr (exponent + 3)) and 0x0F
            out[i] = ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
        }
        return out
    }

    fun decodeMuLaw(data: ByteArray, offset: Int, len: Int): ShortArray {
        val out = ShortArray(len)
        for (i in 0 until len) out[i] = muLawDecodeTable[data[offset + i].toInt() and 0xFF]
        return out
    }
}
