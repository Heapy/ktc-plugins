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

require(args.size == 1) { "Usage: kotlinr scripts/smoke.main.kts BINARY" }
val binary = File(args.single()).canonicalFile.path
temporary("ktc plugins smoke ") { temp ->
    val root = temp.resolve("consumer project").apply { mkdir() }
    val cache = temp.resolve("source cache")
    check(command(temp, listOf("git", "init", "-q", root.path)).exitCode == 0)
    fun run(vararg arguments: String, ok: Boolean = true): String {
        val result = command(root, listOf(binary) + arguments + listOf("--project-dir", root.path, "--cache-dir", cache.path))
        check((result.exitCode == 0) == ok) { result.output }
        return result.output
    }
    run("add", "Heapy/ktc-quarkus", "--commit", "ab131cc9ee67571bd1c09993bf6096ac27b591a0", "--plugin", "quarkus")
    run("add", "Heapy/detekt-config", "--commit", "95f58d20f7fd1d2ad6c65d62c777f97af17b37c2", "--plugin", "detekt", "--mode", "downloaded")
    run("verify")
    check(root.resolve("plugins/quarkus/.ktc-licenses/LICENSE").isFile)
    check(root.resolve("plugins/heapy-detekt/.ktc-licenses/LICENSE").isFile)
    val ignored = command(root, listOf("git", "check-ignore", "plugins/heapy-detekt/module.yaml"))
    check(ignored.exitCode == 0 && "plugins/heapy-detekt/module.yaml" in ignored.output)
    val changed = command(root, listOf("git", "status", "--porcelain", "--untracked-files=all"))
    check(changed.exitCode == 0 && "plugins/quarkus/module.yaml" in changed.output && "plugins/heapy-detekt/module.yaml" !in changed.output)
    val lock = root.resolve("ktc-plugins.lock.yaml")
    var inventory = lock.readBytes()
    check(root.resolve("plugins/heapy-detekt").deleteRecursively())
    run("sync", "--offline")
    check(lock.readBytes().contentEquals(inventory))
    run("verify")
    run("update", "quarkus", "--dry-run")
    check(lock.readBytes().contentEquals(inventory))
    check("pinned" in run("outdated", "--all"))
    run("diff", "quarkus")
    check(lock.readBytes().contentEquals(inventory))
    val sql = run("add", "Heapy/kotgent", "--commit", "9c98f3e33dcecff5abd354a6517d691b65b501d0", "--path", "plugins/sqldelight-gen", "--license-file", "LICENSE", ok = false)
    check("producer version catalog" in sql) { sql }
    check(lock.readBytes().contentEquals(inventory))
    run("add", "Heapy/kotgent", "--commit", "2a000743c6e77540e959ba641b7c844f6d10871e", "--plugin", "sqldelight")
    run("verify")
    check(root.resolve("plugins/sqldelight-gen/.ktc-licenses/LICENSE").isFile)
    inventory = lock.readBytes()
    val producer = temp.resolve("producer fixture")
    val quarkus = root.resolve("plugins/quarkus")
    quarkus.walkTopDown().onEnter { it.name != ".ktc-licenses" }.filter { it.isFile }.forEach {
        copy(it, producer.resolve("plugins/quarkus").resolve(it.relativeTo(quarkus)))
    }
    copy(quarkus.resolve(".ktc-licenses/LICENSE"), producer.resolve("LICENSE"))
    write(producer, "ktc-plugin.yaml", "schemaVersion: 1\nplugins:\n  quarkus:\n    module: plugins/quarkus\n    licenseFiles: [LICENSE]\n")
    val validated = command(producer, listOf(binary, "validate", "--project-dir", producer.path))
    check(validated.exitCode == 0 && "quarkus: valid" in validated.output) { validated.output }
    val detektModule = root.resolve("plugins/heapy-detekt/module.yaml")
    val original = detektModule.readBytes()
    detektModule.appendText("\n# local edit\n")
    check("Modified plugin file" in run("remove", "heapy-detekt", ok = false))
    detektModule.writeBytes(original)
    val preview = run("remove", "heapy-detekt", "--dry-run")
    check("deleted file mode" in preview && lock.readBytes().contentEquals(inventory))
    run("remove", "heapy-detekt")
    check(!detektModule.exists())
    run("verify")
    check("/heapy-detekt/" !in root.resolve("plugins/.gitignore").readText())
    run("wrapper", "update", "--version", "0.1.0", "--dry-run")
    check(!root.resolve("ktc-plugins").exists())
    run("wrapper", "update", "--version", "0.1.0")
    check(root.resolve("ktc-plugins").isFile && root.resolve("ktc-plugins.bat").isFile)
    check("version='0.1.0'" in root.resolve("ktc-plugins").readText())
    println("Real installs, offline restore, diff/outdated, safe removal, producer validation and verified release launcher update passed")
}
