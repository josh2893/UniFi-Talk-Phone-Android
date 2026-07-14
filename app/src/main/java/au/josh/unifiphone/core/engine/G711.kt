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

    // ---- A-law (PCMA, payload type 8) ----

    private val aLawSegEnd = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

    private val aLawDecodeTable = ShortArray(256) { i ->
        val a = i xor 0x55
        var t = (a and 0x0F) shl 4
        when (val seg = (a and 0x70) shr 4) {
            0 -> t += 8
            1 -> t += 0x108
            else -> { t += 0x108; t = t shl (seg - 1) }
        }
        (if (a and 0x80 != 0) t else -t).toShort()
    }

    fun encodeALaw(pcm: ShortArray, len: Int): ByteArray {
        val out = ByteArray(len)
        for (i in 0 until len) {
            var v = pcm[i].toInt() shr 3 // 16-bit -> 13-bit magnitude domain
            val mask: Int
            if (v >= 0) mask = 0xD5 else { mask = 0x55; v = -v - 1 }
            var seg = 8
            for (s in 0 until 8) if (v <= aLawSegEnd[s]) { seg = s; break }
            val aval = if (seg >= 8) 0x7F else {
                (seg shl 4) or (if (seg < 2) (v shr 1) and 0x0F else (v shr seg) and 0x0F)
            }
            out[i] = (aval xor mask).toByte()
        }
        return out
    }

    fun decodeALaw(data: ByteArray, offset: Int, len: Int): ShortArray {
        val out = ShortArray(len)
        for (i in 0 until len) out[i] = aLawDecodeTable[data[offset + i].toInt() and 0xFF]
        return out
    }
}
