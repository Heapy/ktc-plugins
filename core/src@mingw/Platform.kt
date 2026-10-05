@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.ktcplugins

import kotlinx.cinterop.*
import okio.Path
import platform.windows.*

actual object Platform {
    actual val windows: Boolean = true
    actual val macos: Boolean = false
    actual fun error(message: String) { platform.posix.fputs("$message\n", platform.posix.stderr) }
    actual fun env(name: String): String? = memScoped {
        val buffer = allocArray<UShortVar>(32768)
        val size = GetEnvironmentVariableW(name.wcstr.ptr, buffer, 32768u)
        if (size == 0u) null else buffer.toKStringFromUtf16()
    }
    actual fun run(args: List<String>): ProcessResult = memScoped {
        checkInstall(args.isNotEmpty() && args.none { '\u0000' in it }) { "Invalid process arguments" }
        val dir = tempDirectory()
        val security = alloc<SECURITY_ATTRIBUTES>().apply { nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt(); lpSecurityDescriptor = null; bInheritHandle = 1 }
        fun output(path: Path) = CreateFileW(path.toString().wcstr.ptr, GENERIC_WRITE.toUInt(), FILE_SHARE_READ.toUInt(), security.ptr, CREATE_ALWAYS.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null)
        val out = output(dir / "stdout"); val err = output(dir / "stderr")
        checkInstall(out != INVALID_HANDLE_VALUE && err != INVALID_HANDLE_VALUE) { "Cannot capture process output" }
        try {
            val startup = alloc<STARTUPINFOW>().apply {
                platform.posix.memset(ptr, 0, sizeOf<STARTUPINFOW>().convert())
                cb = sizeOf<STARTUPINFOW>().toUInt(); dwFlags = STARTF_USESTDHANDLES.toUInt()
                hStdOutput = out; hStdError = err; hStdInput = GetStdHandle(STD_INPUT_HANDLE)
            }
            val info = alloc<PROCESS_INFORMATION>()
            val command = args.joinToString(" ", transform = ::windowsQuote).wcstr.ptr
            checkInstall(CreateProcessW(null, command, null, null, 1, 0u, null, null, startup.ptr, info.ptr) != 0) { "Cannot start ${args.first()} (Windows error ${GetLastError()})" }
            try {
                WaitForSingleObject(info.hProcess, INFINITE)
                val status = alloc<DWORDVar>()
                GetExitCodeProcess(info.hProcess, status.ptr)
                return ProcessResult(status.value.toInt(), readText(dir / "stdout"), readText(dir / "stderr"))
            } finally { CloseHandle(info.hThread); CloseHandle(info.hProcess) }
        } finally { CloseHandle(out); CloseHandle(err); deleteTree(dir) }
    }
    actual fun permissions(path: Path, executable: Boolean, private: Boolean) { /* Windows executability is determined by the executable format. */ }
    actual fun executable(path: Path): Boolean = false
    actual fun isLink(path: Path): Boolean {
        val attributes = GetFileAttributesW(path.toString())
        return attributes != INVALID_FILE_ATTRIBUTES.toUInt() && attributes and FILE_ATTRIBUTE_REPARSE_POINT.toUInt() != 0u
    }
    actual fun lock(path: Path): AutoCloseable = memScoped {
        val handle = CreateFileW(path.toString().wcstr.ptr, GENERIC_READ.toUInt() or GENERIC_WRITE.toUInt(), 0u, null, OPEN_ALWAYS.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null)
        checkInstall(handle != INVALID_HANDLE_VALUE) { "Another installer is using $path" }
        AutoCloseable { CloseHandle(handle) }
    }
    actual fun exit(code: Int): Nothing { ExitProcess(code.toUInt()); kotlin.error("unreachable") }
}
private fun windowsQuote(value: String): String = buildString {
    append('"'); var slashes = 0
    for (c in value) {
        if (c == '\\') { slashes++; continue }
        repeat(if (c == '"') slashes * 2 + 1 else slashes) { append('\\') }
        append(c); slashes = 0
    }
    repeat(slashes * 2) { append('\\') }; append('"')
}
