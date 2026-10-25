/*
 * Edge read-aloud token adaptation from edge-tts (LGPL-3.0).
 * Upstream copyright: edge-tts contributors. See assets/voice/licenses/edge-tts-LICENSE.txt.
 */
package local.dshx.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Edge 服务的版本 2 请求令牌；与用户的云服务密钥无关。 */
internal object EdgeToken {
    private val initial = intArrayOf(
        1779033703, -1150833019, 1013904242, -1521486534, 1359893119, -1694144372, 528734635, 1541459225,
    )
    private val start = intArrayOf(
        -39862622, -1859896089, -1102475116, -852615670, 1811967817, 79508907, -21977116, 66059218,
    )
    private val schedule = intArrayOf(
        -349913186, 358115615, -1597989150, 217723578, -1507660148, -774007663, -1059248736, -102298954,
        -255650445, 1884868174, 1904797190, 15051571, -765939616, 1920361828,
    )
    private val rounds = intArrayOf(
        1116352408, 1899447441, -1245643825, -373957723, 961987163, 1508970993, -1841331548, -1424204075,
        -670586216, 310598401, 607225278, 1426881987, 1925078388, -2132889090, -1680079193, -1046744716,
        -459576895, -272742522, 264347078, 604807628, 770255983, 1249150122, 1555081692, 1996064986,
        -1740746414, -1473132947, -1341970488, -1084653625, -958395405, -710438585, 113926993, 338241895,
        666307205, 773529912, 1294757372, 1396182291, 1695183700, 1986661051, -2117940946, -1838011259,
        -1564481375, -1474664885, -1035236496, -949202525, -778901479, -694614492, -200395387, 275423344,
        430227734, 506948616, 659060556, 883997877, 958139571, 1322822218, 1537002063, 1747873779,
        1955562222, 2024104815, -2067236844, -1933114872, -1866530822, -1538233109, -1090935817, -965641998,
    )
    private val coefficients = intArrayOf(
        -399601231, 1155409094, 1005282748, -929277645, -1927364079, 827258325, -383479375,
        -257574443, 101076211, -629540256, -1093532320, -1980118536, 905590982, -884350010,
    )
    private val offsets = intArrayOf(
        -1289289520, -1998588643, 693190712, -1289172001, -737823531, 652566332, 1618477510,
        -2102214149, -95312934, 545047652, 1010595059, -758035615, -1157324095, -983408281,
    )

    fun generate(time: Long): String {
        val seconds = time / 1000 + 11644473600L
        val ticks = (seconds - seconds % 300) * 10000000L
        val data = "${ticks}6A5AA1D4EAFF4E9FB37E23D68491D6F4".toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        return hex(checksum(data, digest)) + "-" + hex(digest)
    }

    private fun sigma0(value: Int) = Integer.rotateRight(value, 7) xor Integer.rotateRight(value, 18) xor (value ushr 3)
    private fun sigma1(value: Int) = Integer.rotateRight(value, 17) xor Integer.rotateRight(value, 19) xor (value ushr 10)

    private fun expand(words: IntArray, first: Int) {
        for (i in first until 64) words[i] = sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]
    }

    private fun compress(state: IntArray, words: IntArray, first: Int = 0): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in first until 64) {
            val sum1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val sum0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val constant = if (first == 14 && i == 14) 0xC9DC06A7.toInt() else rounds[i]
            val t1 = h + sum1 + (g xor (e and (f xor g))) + constant + words[i]
            val t2 = sum0 + ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
        words[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16 until 30) {
            var value = schedule[i - 16]
            if (i - 2 >= 14) value += sigma1(words[i - 2]) xor (if (i == 16) 0x000B92C0 else 0)
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) value += sigma0(words[i - 15]) xor (if (i == 29) 0x059C0B80 else 0)
            words[i] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + 0xC9DC06A7.toInt() - rounds[14]
        expand(words, 31)
        val working = compress(start, words, 14)
        var state = IntArray(8) { initial[it] + working[it] }
        val length = 57 + data.size
        val padded = ByteBuffer.allocate(data.size - 7 + 1 + (55 - length).mod(64) + 8)
            .put(data, 7, data.size - 7).put(0x80.toByte())
        padded.position(padded.capacity() - 8)
        padded.putLong(length.toLong() * 8).rewind()
        while (padded.hasRemaining()) {
            for (i in 0 until 16) words[i] = padded.int
            expand(words, 16)
            val next = compress(state, words)
            state = IntArray(8) { state[it] + next[it] }
        }
        val output = ByteBuffer.allocate(32)
        state.forEach(output::putInt)
        return output.array()
    }

    private fun checksum(data: ByteArray, digest: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val a = keys.int or 1; val b = keys.int or 1
        val factors = intArrayOf(a * 0x82893933.toInt(), b * 0x82893933.toInt())
        val terms = coefficients.mapIndexed { i, value -> value * factors[i % 2] }.toMutableList()
        val shifts = offsets.toMutableList()
        val padded = ByteBuffer.allocate(((data.size + 1 + 7) / 8) * 8).order(ByteOrder.LITTLE_ENDIAN)
        padded.put(0.toByte()).put(data).rewind()
        var index = 0
        while (padded.hasRemaining()) {
            val word = padded.int
            var term = word * (if (index % 2 == 0) a else b)
            var shift = Integer.rotateLeft(word, 16) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) { term += 0xF2F90B1A.toInt() * factors[0]; shift += 0x0B1A0000 }
            terms += term; shifts += shift; index++
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        for (i in terms.indices step 2) {
            val x = terms[i]; val y = terms[i + 1]
            var q = Integer.rotateLeft(shifts[i] + x, 16) * 0x76F50F34
            swapAcc += Integer.rotateLeft(q, 16) * 0xC3EFEA97.toInt() + q
            q = Integer.rotateLeft(shifts[i + 1] + y, 16) * 0x1881CE3C
            swapLast = Integer.rotateLeft(q, 16) * 0x35BD1EC9 + q
            swapAcc += swapLast
            state = state * a + x
            for (factor in intArrayOf(0xCF98B111.toInt(), 0x87085B9F.toInt(), 0x12CEB96D, 0x257E1D83)) state = Integer.rotateLeft(state, 16) * factor
            acc += state
            var t = state * b + y
            t = Integer.rotateLeft(t, 16) * 0xA27416F5.toInt()
            t = Integer.rotateLeft(t, 16) * 0xD38396FF.toInt()
            state = Integer.rotateLeft(Integer.rotateLeft(t, 16) * 0x7C932B89, 16) * 0xBFA49F69.toInt()
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
}
