package com.movy

import java.util.Base64

// movy.sx enc=2 wire format: base64url payload that starts with the literal
// "mvm1", followed by json XORed with a keystream derived from the seed and
// the tmdb media id. ported from the site bundle.
object MovyCrypto {

    // 0x9e3779b9 written as its int32 form, the hex literal would be a long
    private const val GOLDEN = -1640531527
    private const val MAGIC_M = 109
    private const val MAGIC_V = 118
    private const val MAGIC_1 = 49

    private fun fmix(value: Int): Int {
        var v = value
        v = v xor (v ushr 16)
        v *= 0x85ebca6b.toInt()
        v = v xor (v ushr 13)
        v *= 0xc2b2ae35.toInt()
        v = v xor (v ushr 16)
        return v
    }

    private fun rotl(value: Int, shift: Int): Int {
        val s = shift and 31
        if (s == 0) return value
        return (value shl s) or (value ushr (32 - s))
    }

    // js computes this on a >>> 0 value
    private fun mod61(value: Int): Int = ((value.toLong() and 0xFFFFFFFFL) % 61L).toInt()

    private fun fnv1aMixed(text: String): Int {
        // 0x811c9dc5 also overflows int, same trick as GOLDEN
        var hash = -2128831035
        for (ch in text) {
            hash = hash xor ch.code
            hash *= 0x1000193
        }
        return fmix(hash)
    }

    // js ToUint32 applied to the media id string before it enters the mix
    private fun toUint32(text: String): Int {
        val parsed = text.trim().toDoubleOrNull() ?: return 0
        if (parsed.isNaN() || parsed.isInfinite()) return 0
        val truncated = parsed.toLong()
        return (truncated and 0xFFFFFFFFL).toInt()
    }

    private class State(val slots: IntArray, val filled: BooleanArray, var acc: Int)

    private fun initState(seed: String, mediaId: String): State {
        val slots = IntArray(61)
        val filled = BooleanArray(61)
        var n = fmix(fnv1aMixed(seed) xor fmix(toUint32(mediaId) xor GOLDEN))
        for (round in 0 until 8) {
            val slot = mod61(n)
            n = rotl(n + GOLDEN, 7 + (7 and round))
            slots[slot] = n xor fmix(n)
            filled[slot] = true
            n = fmix(n + slot)
        }
        val acc = fmix(0xa5a5a5a5.toInt() xor n)
        return State(slots, filled, acc)
    }

    private fun nextWord(state: State, counter: Int): Int {
        val slot = mod61(state.acc)
        val known = state.filled[slot]
        val picked = if (known) state.slots[slot] else 0
        val salt = picked xor (GOLDEN * (counter + 1))
        val acc = state.acc
        val blended = if (known) acc or salt else acc xor salt
        val rotated = rotl(blended + acc, slot and 31) xor rotl(acc, 31 and (slot * 7))
        val word = fmix(rotated + GOLDEN)
        state.slots[slot] = word
        state.filled[slot] = true
        state.acc = word
        return word
    }

    private fun keystream(seed: String, mediaId: String, length: Int): ByteArray {
        val state = initState(seed, mediaId)
        val out = ByteArray(length)
        var index = 0
        var counter = 0
        while (index < length) {
            val word = nextWord(state, counter++)
            out[index++] = word.toByte()
            if (index < length) out[index++] = (word ushr 8).toByte()
            if (index < length) out[index++] = (word ushr 16).toByte()
            if (index < length) out[index++] = (word ushr 24).toByte()
        }
        return out
    }

    private fun decodeBase64Url(payload: String): ByteArray {
        var text = payload.replace("-", "+").replace("_", "/")
        text = text.trimEnd('=')
        while (text.length % 4 != 0) text += "="
        return Base64.getDecoder().decode(text)
    }

    // null means the marker did not match, i.e. the seed was wrong
    fun decrypt(payload: String, seed: String, mediaId: String): String? {
        return try {
            val raw = decodeBase64Url(payload)
            if (raw.size <= 4) return null
            val mask = keystream(seed, mediaId, raw.size)
            val plain = ByteArray(raw.size)
            for (i in raw.indices) {
                plain[i] = (raw[i].toInt() xor mask[i].toInt()).toByte()
            }
            if (plain[0] != MAGIC_M.toByte() || plain[1] != MAGIC_V.toByte() ||
                plain[2] != MAGIC_M.toByte() || plain[3] != MAGIC_1.toByte()
            ) {
                return null
            }
            String(plain, 4, plain.size - 4, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
