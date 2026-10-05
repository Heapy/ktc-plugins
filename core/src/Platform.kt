package io.heapy.ktcplugins

import okio.*
import okio.Path.Companion.toPath
import kotlin.random.Random

data class ProcessResult(val code: Int, val stdout: String, val stderr: String)
expect object Platform {
    val windows: Boolean
    val macos: Boolean
    fun error(message: String)
    fun env(name: String): String?
    fun run(args: List<String>): ProcessResult
    fun permissions(path: Path, executable: Boolean, private: Boolean = false)
    fun executable(path: Path): Boolean
    fun isLink(path: Path): Boolean
    fun delete(path: Path)
    fun lock(path: Path): AutoCloseable
    fun exit(code: Int): Nothing
}
val fs: FileSystem get() = FileSystem.SYSTEM
/** Okio treats C:/... as relative; normalize host paths before parsing on Windows. */
fun systemPath(value: String): Path = (if (Platform.windows) value.replace('/', '\\') else value).toPath()
fun tempDirectory(): Path {
    val base = if (Platform.windows) systemPath(Platform.env("TEMP") ?: Platform.env("TMP") ?: fail("Set TEMP to a private temporary directory"))
        else (Platform.env("TMPDIR") ?: "/tmp").toPath()
    val dir = base / "ktc-plugins-${Random.nextLong().toULong().toString(16)}"
    fs.createDirectory(dir, mustCreate = true)
    Platform.permissions(dir, true, private = true)
    return dir
}
fun FileSystem.readBytes(path: Path, limit: Long = 128L * 1024 * 1024): ByteArray {
    checkNoLink(path)
    val info = metadata(path)
    checkInstall(info.isRegularFile) { "Expected a regular file: $path" }
    checkInstall((info.size ?: Long.MAX_VALUE) <= limit) { "File exceeds size limit: $path" }
    return read(path) { readByteArray() }
}
fun readText(path: Path): String = fs.readBytes(path, 8L * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
fun writeBytes(path: Path, bytes: ByteArray, executable: Boolean = false) {
    checkNoLink(path)
    fs.createDirectories(path.parent ?: fail("Missing parent: $path"))
    fs.write(path) { write(bytes) }
    Platform.permissions(path, executable)
}
fun writeText(path: Path, value: String) = writeBytes(path, value.encodeToByteArray())
fun contained(root: Path, relative: String): Path {
    checkNoLink(root)
    safeRelative(relative)
    var current = root
    for (part in relative.split('/')) {
        current /= part
        checkNoLink(current)
    }
    return current
}
fun absoluteLocation(path: Path): Path {
    val hostPath = systemPath(path.toString())
    var ancestor = if (hostPath.isAbsolute) hostPath else fs.canonicalize(".".toPath()) / hostPath
    val suffix = mutableListOf<String>()
    while (!fs.exists(ancestor)) {
        suffix += ancestor.name
        ancestor = ancestor.parent ?: fail("Cannot resolve path: $path")
    }
    var resolved = fs.canonicalize(ancestor)
    suffix.asReversed().forEach { resolved /= it }
    return resolved.toString().toPath(normalize = true)
}
fun checkNoLink(path: Path) {
    checkInstall(!Platform.isLink(path)) { "Refusing symlink/junction path: $path" }
}
/** Avoid Okio's recursive deletion following Windows junctions (metadata does not expose them). */
fun deleteTree(path: Path) {
    checkNoLink(path)
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach(::deleteTree)
    Platform.delete(path)
}
