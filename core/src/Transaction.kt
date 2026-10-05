package io.heapy.ktcplugins

import okio.Path
import okio.ByteString.Companion.toByteString

/** Journal every destination before replacing anything. Recovery is rollback until committed. */
class Transaction(private val root: Path) {
    private val dir get() = contained(root, ".ktc-plugins/transaction")
    fun recover() {
        if (!fs.exists(dir)) return
        val journal = dir / "journal.yaml"
        if (!fs.exists(journal)) { deleteTree(dir); return }
        val data = parseYaml(readText(journal)).map()
        val committed = fs.exists(dir / "committed")
        if (!committed) {
            for ((index, node) in data.required("entries").list().withIndex().reversed()) {
                val entry = node.map(); val path = contained(root, entry.text("path")); val backup = dir / "old-$index"
                val staged = dir / "new-$index"
                if (fs.exists(backup) || entry.text("hadOriginal") == "false") {
                    if (fs.exists(path)) {
                        // A surviving stage means this entry was never installed: do not touch an unowned path.
                        checkInstall(!fs.exists(staged)) { "Recovery found an unexpected file at $path; inspect the transaction before continuing" }
                        checkInstall(fingerprint(path) == entry.text("newDigest")) { "Recovery found edited content at $path; preserve it before continuing" }
                        deleteTree(path)
                    }
                    if (fs.exists(backup)) fs.atomicMove(backup, path)
                }
            }
        }
        val cleanup = root / ".ktc-plugins" / "cleanup-${kotlin.random.Random.nextLong().toULong().toString(16)}"
        fs.atomicMove(dir, cleanup)
        deleteTree(cleanup)
    }
    fun commit(changes: Map<String, Map<String, Payload>>, failAfter: Int? = null) {
        checkInstall(!fs.exists(dir)) { "Unrecovered transaction" }
        val paths = changes.keys.toList()
        checkInstall(paths.indices.none { i -> paths.indices.any { j -> i != j && paths[j].startsWith(paths[i] + "/") } }) { "Transaction destinations overlap" }
        val state = contained(root, ".ktc-plugins")
        fs.createDirectories(state); Platform.permissions(state, true, true)
        fs.createDirectory(dir)
        val journal = buildString {
            appendLine("entries:")
            changes.entries.forEachIndexed { index, (relative, payload) ->
                val target = contained(root, relative)
                val staged = dir / "new-$index"
                if (payload.keys == setOf("")) writeBytes(staged, payload.getValue("").bytes, payload.getValue("").executable)
                else writeTree(staged, payload)
                appendLine("  - path: ${quote(relative)}\n    hadOriginal: ${fs.exists(target)}\n    newDigest: ${quote(fingerprint(staged))}")
            }
        }
        durableWrite(dir / "journal.yaml", journal)
        try {
            changes.entries.forEachIndexed { index, (relative, _) ->
                val target = contained(root, relative)
                fs.createDirectories(target.parent!!)
                if (fs.exists(target)) fs.atomicMove(target, dir / "old-$index")
                fs.atomicMove(dir / "new-$index", target)
                if (failAfter == index + 1) fail("Injected transaction failure")
            }
            durableWrite(dir / "committed", "committed\n")
        } catch (e: Exception) { recover(); throw e }
        recover()
    }
}
private fun durableWrite(path: Path, text: String) {
    writeText(path, text)
    val handle = fs.openReadWrite(path, mustExist = true)
    try { handle.flush() } finally { handle.close() }
}
fun writeTree(path: Path, payload: Map<String, Payload>) {
    fs.createDirectories(path)
    for ((relative, file) in payload) {
        checkInstall(!file.symbolicLink) { "Refusing to write a symlink payload" }
        writeBytes(contained(path, relative), file.bytes, file.executable)
    }
}
fun fingerprint(path: Path): String {
    checkNoLink(path)
    val metadata = fs.metadata(path)
    checkInstall(metadata.symlinkTarget == null) { "Refusing symlink: $path" }
    if (metadata.isRegularFile) return fs.readBytes(path).toByteString().sha256().hex()
    checkInstall(metadata.isDirectory) { "Unsupported file type: $path" }
    val records = fs.listRecursively(path).map { file ->
        checkNoLink(file)
        val info = fs.metadata(file)
        checkInstall(info.symlinkTarget == null) { "Refusing symlink: $file" }
        checkInstall(info.isRegularFile || info.isDirectory) { "Unsupported file type: $file" }
        val name = file.relativeTo(path).toString().replace('\\', '/')
        "$name\u0000" + if (info.isDirectory) "directory" else fs.readBytes(file).toByteString().sha256().hex()
    }.sorted().joinToString("\n")
    return sha256(records)
}
