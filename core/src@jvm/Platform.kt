package io.heapy.ktcplugins

import okio.Path
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

actual object Platform {
    actual val windows: Boolean = System.getProperty("os.name").startsWith("Windows")
    actual val macos: Boolean = System.getProperty("os.name").startsWith("Mac")
    actual fun error(message: String) = System.err.println(message)
    actual fun env(name: String): String? = System.getenv(name)
    actual fun run(args: List<String>): ProcessResult {
        val dir = tempDirectory()
        try {
            val out = dir / "stdout"; val err = dir / "stderr"
            val p = ProcessBuilder(args).redirectOutput(out.toFile()).redirectError(err.toFile()).start()
            return ProcessResult(p.waitFor(), readText(out), readText(err))
        } finally { deleteTree(dir) }
    }
    actual fun permissions(path: Path, executable: Boolean, private: Boolean) {
        if (!windows) Files.setPosixFilePermissions(path.toNioPath(), PosixFilePermissions.fromString(if (private) { if (executable) "rwx------" else "rw-------" } else { if (executable) "rwxr-xr-x" else "rw-r--r--" }))
    }
    actual fun executable(path: Path): Boolean = !windows && Files.getPosixFilePermissions(path.toNioPath()).any { it.name.endsWith("EXECUTE") }
    actual fun isLink(path: Path): Boolean {
        val file = path.toNioPath()
        if (Files.isSymbolicLink(file)) return true
        return windows && Files.exists(file, LinkOption.NOFOLLOW_LINKS) && file.toRealPath(LinkOption.NOFOLLOW_LINKS) != file.toRealPath()
    }
    actual fun lock(path: Path): AutoCloseable {
        val file = RandomAccessFile(path.toFile(), "rw")
        val lock = try { file.channel.tryLock() } catch (e: java.nio.channels.OverlappingFileLockException) { null }
        if (lock == null) { file.close(); fail("Another installer is using $path") }
        return AutoCloseable { lock.release(); file.close() }
    }
    actual fun exit(code: Int): Nothing = exitProcess(code)
}
