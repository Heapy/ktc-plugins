#!/usr/bin/env kotlinr
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val repo = __FILE__.canonicalFile.parentFile.parentFile
val windows = System.getProperty("os.name").startsWith("Windows")

fun temporary(prefix: String, block: (File) -> Unit) {
    val directory = Files.createTempDirectory(prefix).toFile()
    try { block(directory) } finally { directory.deleteRecursively() }
}

fun write(root: File, path: String, text: String) {
    root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
}

fun copy(source: File, destination: File) {
    check(source.copyRecursively(destination, overwrite = true)) { "Could not copy $source to $destination" }
    if (!windows) {
        source.walkTopDown().filter { it.isFile && it.canExecute() }.forEach { file ->
            val target = if (source.isDirectory) destination.resolve(file.relativeTo(source)) else destination
            check(target.setExecutable(true, false)) { "Could not preserve executable permission: $target" }
        }
    }
}

data class CommandResult(val exitCode: Int, val output: String)
fun command(directory: File, arguments: List<String>, environment: Map<String, String?> = emptyMap(), timeout: Long = 600): CommandResult {
    val log = Files.createTempFile("ktc-command-", ".log").toFile()
    try {
        val process = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(log).apply {
            environment.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
        }.start()
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Timed out: $arguments\n${log.readText()}" }
            return CommandResult(process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
    } finally { log.delete() }
}

fun toolchain(project: File, vararg arguments: String, succeeds: Boolean = true, diagnostic: String? = null): String {
    val wrapper = project.resolve(if (windows) "kotlin.bat" else "kotlin").absolutePath
    val invocation = if (windows) listOf("cmd.exe", "/c", wrapper) else listOf("sh", wrapper)
    val result = command(project, invocation + arguments)
    check((result.exitCode == 0) == succeeds) { "Unexpected exit ${result.exitCode}: ${arguments.toList()}\n${result.output}" }
    check(diagnostic == null || diagnostic in result.output) { "Missing diagnostic $diagnostic:\n${result.output}" }
    println("PASS: ${arguments.joinToString(" ")} (${if (succeeds) "success" else "expected failure"})")
    return result.output
}

require(args.size == 1) { "Usage: kotlinr scripts/test-wrappers.main.kts BINARY" }
val binary = File(args.single()).canonicalFile
val version = Regex("const val VERSION = \"([^\"]+)\"").find(repo.resolve("core/src/Cli.kt").readText())!!.groupValues[1]
val target = when {
    windows -> "windows-x64"
    System.getProperty("os.name") == "Mac OS X" -> "macos-arm64"
    System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "linux-arm64"
    else -> "linux-x64"
}
temporary("ktc wrapper test ") { tmp ->
    val binaries = tmp.resolve("input").apply { mkdir() }
    for (platform in listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")) {
        binary.copyTo(binaries.resolve("ktc-plugins-$version-$platform" + if (platform == "windows-x64") ".exe" else ""))
    }
    val output = tmp.resolve("release bundle")
    // Use the same distribution that is running this script, not the Toolchain CLI on PATH.
    val runner = File(System.getProperty("kotlin.home"), "bin/${if (windows) "kotlinr.bat" else "kotlinr"}")
    val invocation = if (windows) listOf("cmd.exe", "/c", runner.path) else listOf(runner.path)
    val packaged = command(repo, invocation + listOf(repo.resolve("scripts/package-release.main.kts").path, "--version", version, "--binaries", binaries.path, "--output", output.path))
    check(packaged.exitCode == 0) { packaged.output }
    val cached = tmp.resolve("offline binary cache/$version/$target").apply { mkdirs() }
    val name = "ktc-plugins-$version-$target" + if (windows) ".exe" else ""
    val cachedBinary = output.resolve(name).copyTo(cached.resolve(name))
    if (!windows) check(cachedBinary.setExecutable(true, false))
    val environment = mutableMapOf<String, String?>("KTC_PLUGINS_BINARY_CACHE" to tmp.resolve("offline binary cache").path, "KTC_PLUGINS_BINARY" to null)
    val wrapper = output.resolve(if (windows) "ktc-plugins.bat" else "ktc-plugins").path
    fun run(vararg arguments: String) = command(tmp, (if (windows) listOf("cmd.exe", "/c", wrapper) else listOf(wrapper)) + arguments, environment)
    fun concurrentVersions() {
        java.util.concurrent.Executors.newFixedThreadPool(4).use { pool ->
            val futures = (1..4).map { pool.submit<CommandResult> { run("--version") } }
            for (future in futures) {
                val result = future.get()
                check(result.exitCode == 0 && result.output.trim() == version) { result.output }
            }
        }
    }
    concurrentVersions()
    val project = tmp.resolve("project with spaces").apply { mkdir() }
    val status = run("status", "--project-dir", project.path, "--cache-dir", tmp.resolve("sources with spaces").path)
    check(status.exitCode == 0) { status.output }
    cachedBinary.appendText("corrupt")
    val corrupt = run("--version")
    check(corrupt.exitCode != 0 && "checksum mismatch" in corrupt.output) { corrupt.output }
    if (!windows) {
        val stub = tmp.resolve("curl stub").apply { mkdir() }
        stub.resolve("curl").apply {
            writeText($$"""
                #!/bin/sh
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = -o ]; then
                    cp "$KTC_TEST_SOURCE_BINARY" "$2" || exit 1
                    if [ "${KTC_TEST_CORRUPT:-}" = 1 ]; then printf corrupt >> "$2"; fi
                    exit 0
                  fi
                  shift
                done
                exit 1
            """.trimIndent() + "\n")
            check(setExecutable(true, false))
        }
        environment["PATH"] = stub.path + File.pathSeparator + System.getenv("PATH")
        environment["KTC_TEST_SOURCE_BINARY"] = binary.path
        check(cachedBinary.delete())
        concurrentVersions()
        check(cachedBinary.delete())
        environment["KTC_TEST_CORRUPT"] = "1"
        val downloaded = run("--version")
        check(downloaded.exitCode != 0 && "checksum mismatch" in downloaded.output) { downloaded.output }
        check(!cachedBinary.exists())
    }
    println("Wrapper paths, argument forwarding, concurrent bootstrap/offline launches and checksum rejection passed")
}
