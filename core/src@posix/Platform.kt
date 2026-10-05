@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package io.heapy.ktcplugins

import kotlinx.cinterop.*
import okio.Path
import platform.posix.*

actual object Platform {
    actual fun delete(path: Path) = fs.delete(path)
    actual val windows: Boolean = false
    actual val macos: Boolean = kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX
    actual fun error(message: String) { fputs("$message\n", stderr) }
    actual fun env(name: String): String? = getenv(name)?.toKString()
    actual fun run(args: List<String>): ProcessResult {
        checkInstall(args.isNotEmpty() && args.none { '\u0000' in it }) { "Invalid process arguments" }
        val dir = tempDirectory()
        try {
            val out = dir / "stdout"; val err = dir / "stderr"
            fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            val command = args.joinToString(" ", transform = ::shellQuote) + " > " + shellQuote(out.toString()) + " 2> " + shellQuote(err.toString())
            val status = system(command)
            return ProcessResult(if (status == -1) 127 else if ((status and 127) == 0) status shr 8 else 128 + (status and 127), readText(out), readText(err))
        } finally { deleteTree(dir) }
    }
    actual fun permissions(path: Path, executable: Boolean, private: Boolean) {
        val mode = if (private) { if (executable) 448 else 384 } else { if (executable) 493 else 420 }
        checkInstall(chmod(path.toString(), mode.convert()) == 0) { "Cannot set permissions: $path" }
    }
    actual fun executable(path: Path): Boolean = memScoped {
        val info = alloc<stat>()
        checkInstall(lstat(path.toString(), info.ptr) == 0) { "Cannot inspect permissions: $path" }
        (info.st_mode.toInt() and 73) != 0
    }
    actual fun isLink(path: Path): Boolean = fs.metadataOrNull(path)?.symlinkTarget != null
    actual fun lock(path: Path): AutoCloseable {
        val fd = open(path.toString(), O_CREAT or O_RDWR, 384)
        checkInstall(fd >= 0) { "Cannot open lock: $path" }
        if (fileLock(fd, LOCK_EX or LOCK_NB) != 0) { close(fd); fail("Another installer is using $path") }
        return AutoCloseable { fileLock(fd, LOCK_UN); close(fd) }
    }
    actual fun exit(code: Int): Nothing { platform.posix.exit(code); kotlin.error("unreachable") }
}
internal expect fun fileLock(fd: Int, operation: Int): Int
