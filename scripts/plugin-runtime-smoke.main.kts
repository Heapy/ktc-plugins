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

val usage = "Usage: kotlinr scripts/plugin-runtime-smoke.main.kts BINARY [--work-dir DIR] [--toolchain-wrapper FILE] [--plugins quarkus detekt sql] [--sql-producer-dir DIR]"
require(args.isNotEmpty()) { usage }
if (args.contentEquals(arrayOf("--help"))) { println(usage); kotlin.system.exitProcess(0) }
val binary = File(args[0]).canonicalFile
var workOption: File? = null
var wrapper = repo.resolve(if (windows) "kotlin.bat" else "kotlin")
var plugins = listOf("quarkus", "detekt", "sql")
var sqlProducer: File? = null
var index = 1
while (index < args.size) {
    val option = args[index++]
    require(index < args.size) { "Missing value for $option" }
    when (option) {
        "--work-dir" -> workOption = File(args[index++]).canonicalFile
        "--toolchain-wrapper" -> wrapper = File(args[index++]).canonicalFile
        "--sql-producer-dir" -> sqlProducer = File(args[index++]).canonicalFile
        "--plugins" -> {
            val selected = mutableListOf<String>()
            while (index < args.size && !args[index].startsWith("--")) selected += args[index++]
            require(selected.isNotEmpty() && selected.all { it in listOf("quarkus", "detekt", "sql") }) { "Unknown or empty plugin selection: $selected" }
            require(selected.distinct().size == selected.size) { "Duplicate plugin selection: $selected" }
            plugins = selected
        }
        else -> error("Unknown option $option\n$usage")
    }
}
val work = (workOption ?: Files.createTempDirectory("ktc-plugin-runtime-").toFile()).apply { mkdirs() }
val cache = work.resolve("source-cache")
fun checked(root: File, arguments: List<String>): String {
    val result = command(root, arguments, timeout = 1800)
    val log = root.resolve("fixture-validation.log")
    log.appendText(result.output)
    check(result.exitCode == 0) { result.output.take(4000) + "\nFull log: $log" }
    return result.output
}
fun consumer(name: String): File {
    val root = work.resolve(name)
    if (root.exists()) {
        require(root.resolve(".ktc-runtime-fixture").isFile) { "Refusing to replace an unowned fixture: $root" }
        check(root.deleteRecursively())
    }
    check(root.mkdir())
    root.resolve(".ktc-runtime-fixture").createNewFile()
    copy(wrapper, root.resolve(if (windows) "kotlin.bat" else "kotlin"))
    return root
}
fun runToolchain(root: File, vararg arguments: String): String {
    val file = root.resolve(if (windows) "kotlin.bat" else "kotlin").path
    return checked(root, (if (windows) listOf("cmd.exe", "/c", file) else listOf("sh", file)) + arguments)
}
fun install(root: File, repository: String, commit: String, selector: String, module: String? = "app") {
    val invocation = listOf(binary.path, "add", repository, "--commit", commit, "--plugin", selector, "--cache-dir", cache.path)
    checked(root, invocation + if (module == null) emptyList() else listOf("--enable-in", module))
}
fun quarkus() {
    val root = consumer("quarkus-app")
    write(root, "project.yaml", "modules: [app]\n")
    write(root, "app/module.yaml", "product: jvm/app\ndependencies:\n  - bom: io.quarkus.platform:quarkus-bom:3.39.4\n  - io.quarkus:quarkus-rest\n  - io.quarkus:quarkus-kotlin\n")
    write(root, "app/src/example/HelloResource.kt", "package example\n\nimport jakarta.ws.rs.GET\nimport jakarta.ws.rs.Path\n\n@Path(\"/hello\")\nclass HelloResource {\n    @GET\n    fun hello(): String = \"ok\"\n}\n")
    write(root, "app/resources/application.properties", "quarkus.analytics.disabled=true\n")
    install(root, "Heapy/ktc-quarkus", "ab131cc9ee67571bd1c09993bf6096ac27b591a0", "quarkus")
    val output = runToolchain(root, "do", "quarkusBuild", "-m", "app")
    check(root.resolve("build").walkTopDown().any { it.name == "quarkus-run.jar" }) { output }
    println("Quarkus packaging passed")
}
fun detekt() {
    val root = consumer("detekt")
    write(root, "project.yaml", "modules: [app]\n")
    write(root, "app/module.yaml", "product: jvm/lib\n")
    write(root, "app/src/io/heapy/fixture/Greeting.kt", "package io.heapy.fixture\n\nfun greeting(\n    name: String,\n): String = \"hello \$name\"\n")
    install(root, "Heapy/detekt-config", "95f58d20f7fd1d2ad6c65d62c777f97af17b37c2", "detekt")
    runToolchain(root, "check", "-m", "app")
    println("Detekt execution and config artifact resolution passed")
}
fun sqlConsumer(root: File, exportedCatalog: Boolean = false) {
    val modulePath = if (exportedCatalog) "plugins/sqldelight" else "plugins/sqldelight-gen"
    val dependency = if (exportedCatalog) "\$libs.ktc.sqldelight.runtime" else "app.cash.sqldelight:runtime:2.3.2"
    val configuration = if (exportedCatalog) "  sqldelight:\n    enabled: true\n    packageName: io.kotgent.db\n    className: KotgentDatabase\n" else "  sqldelight-gen: enabled\n"
    write(root, "project.yaml", "modules: [$modulePath]\nplugins: [//$modulePath]\n")
    write(root, "module.yaml", "product: jvm/lib\ndependencies:\n  - $dependency\nplugins:\n$configuration")
    write(root, "sqldelight/io/kotgent/db/Item.sq", "CREATE TABLE item (id INTEGER NOT NULL PRIMARY KEY);\n\nselectAll:\nSELECT * FROM item;\n")
    val output = runToolchain(root, "build", "-m", root.name)
    check(root.resolve("build").walkTopDown().any { it.name == "KotgentDatabase.kt" }) { output }
    println("SQLDelight generation and generated-source compilation passed with " + if (exportedCatalog) "exported catalog version references" else "local self-contained producer")
}
fun sql() {
    val root = consumer("sqldelight-app")
    val source = sqlProducer
    if (source != null) {
        val validated = checked(root, listOf(binary.path, "validate", "--project-dir", source.path, "--plugin", "sqldelight"))
        check(", plugins/sqldelight-gen," in validated)
        val modulePath = "plugins/sqldelight-gen"
        val files = checked(root, listOf("git", "-C", source.path, "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", modulePath)).split('\u0000').filter { it.isNotEmpty() }
        for (name in files) copy(source.resolve(name), root.resolve(name))
        copy(source.resolve("LICENSE"), root.resolve("$modulePath/.ktc-licenses/LICENSE"))
        check("\$libs." !in root.resolve("$modulePath/module.yaml").readText())
        sqlConsumer(root)
        return
    }
    install(root, "Heapy/ktc-sqldelight", "0686ee58431b6477fa92fa380b5d321f21e1a8b8", "sqldelight", module = null)
    check(root.resolve("plugins/sqldelight/.ktc-licenses/LICENSE").isFile)
    val catalog = root.resolve("libs.versions.toml").readText()
    check(catalog.split("\"2.3.2\"").size - 1 == 1) { catalog }
    check(catalog.split("version.ref = \"ktc-sqldelight-sqldelight\"").size - 1 == 4) { catalog }
    checked(root, listOf(binary.path, "verify", "--cache-dir", cache.path))
    sqlConsumer(root, exportedCatalog = true)
}
val failures = mutableListOf<String>()
java.util.concurrent.Executors.newFixedThreadPool(3).use { executor ->
    val futures = plugins.map { name -> executor.submit { when (name) { "quarkus" -> quarkus(); "detekt" -> detekt(); "sql" -> sql() } } }
    for (future in futures) {
        try { future.get() } catch (error: java.util.concurrent.ExecutionException) { failures += (error.cause ?: error).toString() }
    }
}
println("Consumer fixtures retained at $work")
check(failures.isEmpty()) { failures.joinToString("\n") }
