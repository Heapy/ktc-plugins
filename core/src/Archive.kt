package io.heapy.ktcplugins

import okio.ByteString.Companion.toByteString

const val MAX_ARCHIVE = 64 * 1024 * 1024
const val MAX_TREE = 128 * 1024 * 1024
const val MAX_FILE = 16 * 1024 * 1024
const val MAX_FILES = 10000
data class Payload(val bytes: ByteArray, val executable: Boolean = false, val symbolicLink: Boolean = false) {
    val record: FileRecord get() = FileRecord(bytes.toByteString().sha256().hex(), executable)
}

/** Bounded ZIP reader. No archive path is ever handed to an OS extraction utility. */
fun readZip(zip: ByteArray, allowUnselectedSymlinks: Boolean = false): Map<String, Payload> {
    checkInstall(zip.size <= MAX_ARCHIVE) { "Archive exceeds size limit" }
    fun u16(p: Int): Int {
        checkInstall(p >= 0 && p + 2 <= zip.size) { "Truncated ZIP" }
        return (zip[p].toInt() and 255) or ((zip[p + 1].toInt() and 255) shl 8)
    }
    fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
    val end = (zip.size - 22 downTo maxOf(0, zip.size - 65557)).firstOrNull {
        u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == zip.size
    } ?: fail("Missing ZIP end record")
    checkInstall(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "Multi-disk ZIP is unsupported" }
    val count = u16(end + 10)
    checkInstall(count in 1..MAX_FILES) { "Archive file-count limit exceeded" }
    val central = u32(end + 16)
    checkInstall(central + u32(end + 12) == end.toLong()) { "Invalid ZIP central directory" }
    var cursor = central.toInt(); var total = 0L
    val entries = linkedMapOf<String, Payload>()
    var prefix: String? = null
    val folded = mutableSetOf<String>()
    repeat(count) {
        checkInstall(u32(cursor) == 0x02014b50L) { "Invalid ZIP entry" }
        val flags = u16(cursor + 8); val method = u16(cursor + 10)
        val compressed = u32(cursor + 20); val size = u32(cursor + 24)
        val nameSize = u16(cursor + 28); val extraSize = u16(cursor + 30); val commentSize = u16(cursor + 32)
        val mode = (u32(cursor + 38) shr 16).toInt()
        val local = u32(cursor + 42)
        val next = cursor.toLong() + 46 + nameSize + extraSize + commentSize
        checkInstall(next <= end && nameSize > 0 && compressed <= MAX_ARCHIVE && size <= MAX_FILE && local < central && (flags and 1) == 0 && method in setOf(0, 8)) { "Unsupported, oversized or encrypted ZIP entry" }
        val name = zip.copyOfRange(cursor + 46, cursor + 46 + nameSize).decodeToString(throwOnInvalidSequence = true)
        val directory = name.endsWith('/')
        val clean = if (directory) name.dropLast(1) else name
        safeRelative(clean)
        val symbolicLink = (mode and 0xF000) == 0xa000
        checkInstall((mode and 0xF000) in setOf(0, 0x8000, 0x4000) || symbolicLink && allowUnselectedSymlinks) { "Archive symlinks and special files are unsupported: $name" }
        val root = clean.substringBefore('/')
        if (prefix == null) prefix = root
        checkInstall(prefix == root && (directory || '/' in clean)) { "Archive must have a single root directory" }
        val relative = clean.substringAfter('/', "")
        if (relative.isNotEmpty()) {
            checkInstall(folded.add(relative.lowercase())) { "Duplicate or case-colliding archive path: $relative" }
            checkInstall(u32(local.toInt()) == 0x04034b50L && u16(local.toInt() + 8) == method && u16(local.toInt() + 6) == flags) { "Invalid ZIP local header" }
            val start = local + 30 + u16(local.toInt() + 26) + u16(local.toInt() + 28)
            checkInstall(start + compressed <= central) { "Invalid ZIP payload bounds" }
            val localName = zip.copyOfRange(local.toInt() + 30, local.toInt() + 30 + u16(local.toInt() + 26)).decodeToString()
            checkInstall(localName == name) { "ZIP header names disagree" }
            total += size
            checkInstall(total <= MAX_TREE) { "Archive expanded-size limit exceeded" }
            if (!directory) {
                val data = zip.copyOfRange(start.toInt(), (start + compressed).toInt())
                val bytes = if (method == 0) data else inflate(data, size.toInt())
                checkInstall(bytes.size == size.toInt() && crc32(bytes) == u32(cursor + 16)) { "ZIP size or CRC mismatch: $relative" }
                entries[relative] = Payload(bytes, mode and 73 != 0, symbolicLink)
            }
        }
        cursor = next.toInt()
    }
    checkInstall(cursor == end) { "ZIP directory length mismatch" }
    rejectCaseCollisions(entries.keys)
    checkInstall(entries.keys.none { path -> path.split('/').dropLast(1).indices.any { index -> path.split('/').take(index + 1).joinToString("/") in entries } }) { "Archive file/directory collision" }
    return entries
}

fun crc32(bytes: ByteArray): Long {
    var crc = -1
    for (byte in bytes) {
        crc = crc xor (byte.toInt() and 255)
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    }
    return (crc.inv().toLong() and 0xffffffffL)
}

/** RFC 1951 raw DEFLATE, bounded by the trusted maximum and declared ZIP size. */
internal fun inflate(input: ByteArray, expected: Int): ByteArray {
    checkInstall(expected in 0..MAX_FILE) { "Invalid expanded size" }
    var bit = 0
    fun bits(n: Int): Int {
        checkInstall(bit.toLong() + n <= input.size.toLong() * 8) { "Truncated DEFLATE stream" }
        var value = 0
        repeat(n) { value = value or (((input[bit / 8].toInt() ushr (bit % 8)) and 1) shl it); bit++ }
        return value
    }
    class Huffman(lengths: IntArray) {
        val tables = Array(16) { IntArray(1 shl it) { -1 } }
        init {
            checkInstall(lengths.all { it in 0..15 }) { "Invalid Huffman lengths" }
            val counts = IntArray(16)
            lengths.filter { it != 0 }.forEach { counts[it]++ }
            val next = IntArray(16); var code = 0
            for (len in 1..15) {
                code = (code + counts[len - 1]) shl 1; next[len] = code
                checkInstall(code + counts[len] <= 1 shl len) { "Oversubscribed Huffman tree" }
            }
            lengths.forEachIndexed { symbol, len -> if (len > 0) tables[len][next[len]++] = symbol }
        }
        fun decode(): Int {
            var code = 0
            for (len in 1..15) { code = (code shl 1) or bits(1); val symbol = tables[len][code]; if (symbol >= 0) return symbol }
            fail("Invalid Huffman code")
        }
    }
    val result = ByteArray(expected); var written = 0
    fun put(value: Int) { checkInstall(written < result.size) { "DEFLATE exceeds declared size" }; result[written++] = value.toByte() }
    val lengthBase = intArrayOf(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258)
    val lengthBits = intArrayOf(0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0)
    val distBase = intArrayOf(1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577)
    val distBits = intArrayOf(0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13)
    do {
        val final = bits(1) == 1
        when (val type = bits(2)) {
            0 -> {
                bit = (bit + 7) / 8 * 8
                val size = bits(16); checkInstall(bits(16) == size xor 65535) { "Bad uncompressed DEFLATE block" }
                repeat(size) { put(bits(8)) }
            }
            1, 2 -> {
                val literals: Huffman; val distances: Huffman
                if (type == 1) {
                    literals = Huffman(IntArray(288) { when (it) { in 0..143 -> 8; in 144..255 -> 9; in 256..279 -> 7; else -> 8 } })
                    distances = Huffman(IntArray(32) { 5 })
                } else {
                    val litCount = bits(5) + 257; val distCount = bits(5) + 1; val codeCount = bits(4) + 4
                    val order = intArrayOf(16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15)
                    val codeLengths = IntArray(19); repeat(codeCount) { codeLengths[order[it]] = bits(3) }
                    val codes = Huffman(codeLengths); val lengths = IntArray(litCount + distCount); var i = 0
                    while (i < lengths.size) {
                        val symbol = codes.decode()
                        if (symbol < 16) lengths[i++] = symbol else {
                            val repeat = when (symbol) { 16 -> bits(2) + 3; 17 -> bits(3) + 3; else -> bits(7) + 11 }
                            checkInstall(i + repeat <= lengths.size && (symbol != 16 || i > 0)) { "Bad Huffman repeat" }
                            val value = if (symbol == 16) lengths[i - 1] else 0
                            repeat(repeat) { lengths[i++] = value }
                        }
                    }
                    checkInstall(lengths[256] != 0) { "Missing DEFLATE end code" }
                    literals = Huffman(lengths.copyOfRange(0, litCount)); distances = Huffman(lengths.copyOfRange(litCount, lengths.size))
                }
                while (true) {
                    val symbol = literals.decode()
                    if (symbol == 256) break
                    if (symbol < 256) put(symbol) else {
                        checkInstall(symbol in 257..285) { "Invalid DEFLATE length" }
                        val length = lengthBase[symbol - 257] + bits(lengthBits[symbol - 257])
                        val d = distances.decode(); checkInstall(d < 30) { "Invalid DEFLATE distance" }
                        val distance = distBase[d] + bits(distBits[d])
                        checkInstall(distance <= written) { "DEFLATE back-reference before output" }
                        repeat(length) { put(result[written - distance].toInt()) }
                    }
                }
            }
            else -> fail("Reserved DEFLATE block type")
        }
    } while (!final)
    checkInstall(written == expected) { "DEFLATE output size mismatch" }
    return result
}
