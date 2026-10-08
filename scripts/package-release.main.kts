#!/usr/bin/env kotlinr
// Generate checksum-pinned release launchers. Run with --version, --binaries and --output.
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

val repo = __FILE__.canonicalFile.parentFile.parentFile
val targets = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")
fun sha256(file: File) = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toHexString()
}
fun executable(file: File) {
    if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
    }
}
fun packageRelease(version: String, binaries: File, output: File) {
    require(Regex("""\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?""").matches(version)) { "Expected a release version such as 0.1.0" }
    require("const val VERSION = \"$version\"" in repo.resolve("core/src/Cli.kt").readText()) { "Release version must match core/src/Cli.kt" }
    output.mkdirs()
    val sums = StringBuilder()
    var unix = repo.resolve("ktc-plugins").readText().replace("\r\n", "\n")
    var windows = repo.resolve("ktc-plugins.bat").readText().replace("\r\n", "\n")
    unix = Regex("^version='[^']+' # VERSION$", RegexOption.MULTILINE).replace(unix) { "version='$version' # VERSION" }
    windows = Regex("^set \"ktc_version=[^\"]+\"$", RegexOption.MULTILINE).replace(windows) { "set \"ktc_version=$version\"" }
    for (target in targets) {
        val name = "ktc-plugins-$version-$target" + if (target.startsWith("windows")) ".exe" else ""
        val binary = binaries.resolve(name)
        require(binary.isFile && binary.length() > 0) { "Missing release binary: $binary" }
        val digest = sha256(binary)
        sums.append("$digest  $name\n")
        val marker = "SHA_" + target.uppercase().replace('-', '_')
        val pattern = Regex(if (target == "windows-x64") """\${'$'}sha = '[^']+'(?= # $marker$)""" else "sha='[^']+'(?= ;; # $marker$)", RegexOption.MULTILINE)
        val text = if (target == "windows-x64") windows else unix
        require(pattern.findAll(text).count() == 1) { "Missing/duplicate wrapper pin marker: $marker" }
        if (target == "windows-x64") windows = pattern.replace(windows) { "\$sha = '$digest'" }
        else unix = pattern.replace(unix) { "sha='$digest'" }
        val destination = output.resolve(name)
        if (binary.canonicalFile != destination.canonicalFile) binary.copyTo(destination, overwrite = true)
        if (!target.startsWith("windows")) executable(destination)
    }
    output.resolve("ktc-plugins").apply { writeText(unix); executable(this) }
    output.resolve("ktc-plugins.bat").writeText(windows.replace("\n", "\r\n"))
    for (name in listOf("ktc-plugins", "ktc-plugins.bat")) sums.append("${sha256(output.resolve(name))}  $name\n")
    output.resolve("SHA256SUMS").writeText(sums.toString())
}

if (args.contentEquals(arrayOf("--help"))) {
    println("Usage: kotlinr scripts/package-release.main.kts --version VERSION --binaries DIR --output DIR")
} else {
    require(args.size == 6) { "Required: --version VERSION --binaries DIR --output DIR" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    require(options.keys == setOf("--version", "--binaries", "--output")) { "Required: --version, --binaries, --output (once each)" }
    packageRelease(options.getValue("--version"), File(options.getValue("--binaries")), File(options.getValue("--output")))
}
