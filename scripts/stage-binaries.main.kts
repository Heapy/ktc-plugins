#!/usr/bin/env kotlinr
// Stage native release binaries: kotlinr scripts/stage-binaries.main.kts PLATFORM... [--output DIR]
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

val targets = mapOf(
    "macosArm64" to Triple("cli-macos", "macos-arm64", ".kexe"),
    "linuxX64" to Triple("cli-linux", "linux-x64", ".kexe"),
    "linuxArm64" to Triple("cli-linux", "linux-arm64", ".kexe"),
    "mingwX64" to Triple("cli-windows", "windows-x64", ".exe"),
)
val platforms = mutableListOf<String>()
var output = File("build/binaries")
var index = 0
while (index < args.size) {
    val argument = args[index++]
    when (argument) {
        "--help" -> { println("Usage: kotlinr scripts/stage-binaries.main.kts ${targets.keys}... [--output DIR]"); kotlin.system.exitProcess(0) }
        "--output" -> { require(index < args.size) { "--output requires a directory" }; output = File(args[index++]) }
        else -> { require(argument in targets) { "Unknown platform: $argument" }; platforms += argument }
    }
}
require(platforms.isNotEmpty()) { "Provide at least one platform: ${targets.keys}" }
val version = Regex("const val VERSION = \"([^\"]+)\"").find(File("core/src/Cli.kt").readText())!!.groupValues[1]
output.mkdirs()
for (platform in platforms) {
    val (module, target, extension) = targets.getValue(platform)
    val source = File("build/tasks/_${module}_link${platform.replaceFirstChar { it.uppercaseChar() }}Release/$module$extension")
    val name = "ktc-plugins-$version-$target" + if (platform == "mingwX64") ".exe" else ""
    val destination = source.copyTo(output.resolve(name), overwrite = true)
    if (platform != "mingwX64" && Files.getFileStore(destination.toPath()).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(destination.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
    }
    println(destination)
}
