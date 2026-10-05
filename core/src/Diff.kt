package io.heapy.ktcplugins

import okio.Path
import okio.ByteString.Companion.toByteString

/** A bounded, single-hunk unified diff. Binary/large files are summarized by digest. */
fun fileDiff(path: String, before: Payload?, after: Payload?): String {
    if (before == after) return ""
    if (before != null && after != null && before.bytes.contentEquals(after.bytes) && before.executable == after.executable) return ""
    fun decode(file: Payload?): String? {
        if (file == null) return ""
        if (file.bytes.size > 256 * 1024) return null
        val text = try { file.bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { return null }
        return text.takeIf { it.none { c -> c.code < 32 && c !in "\n\r\t" || c.code == 127 } }
    }
    val old = decode(before); val new = decode(after)
    return buildString {
        appendLine("diff --git a/$path b/$path")
        if (before == null) appendLine("new file mode ${if (after!!.executable) "100755" else "100644"}")
        else if (after == null) appendLine("deleted file mode ${if (before.executable) "100755" else "100644"}")
        else if (before.executable != after.executable) {
            appendLine("old mode ${if (before.executable) "100755" else "100644"}")
            appendLine("new mode ${if (after.executable) "100755" else "100644"}")
        }
        if (before != null && after != null && before.bytes.contentEquals(after.bytes)) return@buildString
        if (old == null || new == null) {
            fun identity(file: Payload?) = file?.let { "${it.bytes.size} bytes, sha256 ${it.bytes.toByteString().sha256().hex()}" } ?: "absent"
            appendLine("Binary/large file: ${identity(before)} -> ${identity(after)}")
            return@buildString
        }
        appendLine("--- ${if (before == null) "/dev/null" else "a/$path"}")
        appendLine("+++ ${if (after == null) "/dev/null" else "b/$path"}")
        fun lines(text: String) = if (text.isEmpty()) emptyList() else text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }
        val a = lines(old); val b = lines(new)
        var prefix = 0
        if (old.endsWith('\n') == new.endsWith('\n')) while (prefix < minOf(a.size, b.size) && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(a.size, b.size) - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
        if (old.endsWith('\n') != new.endsWith('\n')) suffix = 0
        val start = maxOf(0, prefix - 3)
        val endA = minOf(a.size, a.size - suffix + 3); val endB = minOf(b.size, b.size - suffix + 3)
        fun range(start: Int, count: Int) = "${if (count == 0) start else start + 1},$count"
        appendLine("@@ -${range(start, endA - start)} +${range(start, endB - start)} @@")
        fun emit(sign: Char, line: String, last: Boolean, terminated: Boolean) {
            appendLine("$sign$line")
            if (last && !terminated) appendLine("\\ No newline at end of file")
        }
        for (i in start until prefix) emit(' ', a[i], i == a.lastIndex, old.endsWith('\n'))
        for (i in prefix until a.size - suffix) emit('-', a[i], i == a.lastIndex, old.endsWith('\n'))
        for (i in prefix until b.size - suffix) emit('+', b[i], i == b.lastIndex, new.endsWith('\n'))
        for (i in a.size - suffix until endA) emit(' ', a[i], i == a.lastIndex, old.endsWith('\n'))
    }
}

fun reportChanges(root: Path, changes: Map<String, Map<String, Payload>?>, report: (String) -> Unit) {
    for ((relative, payload) in changes) {
        val target = contained(root, relative)
        val before = linkedMapOf<String, Payload>()
        if (fs.exists(target)) {
            checkNoLink(target)
            if (fs.metadata(target).isRegularFile) before[""] = Payload(fs.readBytes(target), Platform.executable(target))
            else for (file in fs.listRecursively(target)) {
                checkNoLink(file)
                val info = fs.metadata(file)
                checkInstall(info.isRegularFile || info.isDirectory) { "Unsupported diff path: $file" }
                if (info.isRegularFile) before[file.relativeTo(target).toString().replace('\\', '/')] = Payload(fs.readBytes(file), Platform.executable(file))
            }
        }
        val after = payload ?: emptyMap()
        for (name in (before.keys + after.keys).sorted()) {
            // Windows does not expose Unix modes: retain locked mode for existing bytes.
            val old = before[name]?.let { if (Platform.windows && after[name] != null) it.copy(executable = after.getValue(name).executable) else it }
            val diff = fileDiff(if (name.isEmpty()) relative else "$relative/$name", old, after[name])
            if (diff.isNotEmpty()) report(diff.trimEnd())
        }
    }
}
