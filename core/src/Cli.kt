package io.heapy.ktcplugins


const val VERSION = "0.2.0"
private val usage = """
ktc-plugins $VERSION — install GitHub sources as local Kotlin Toolchain plugins

  add OWNER/REPO (--tag TAG | --branch BRANCH | --commit SHA)
      [--name NAME] [--plugin SELECTOR] [--path DIRECTORY]
      [--license-file FILE]... [--destination PATH]
      [--mode vendored|downloaded] [--enable-in MODULE] [--dry-run]
  update NAME | update --all
      [--tag TAG | --branch BRANCH | --commit SHA] [--dry-run]
  sync [--offline] [--dry-run]
  diff NAME | diff --all [--tag TAG | --branch BRANCH | --commit SHA]
  outdated [NAME | --all]
  remove NAME [--disable-in MODULE]... [--dry-run]
  wrapper update --version VERSION [--dry-run]
  validate [--plugin SELECTOR]
  status
  verify

Global options: --project-dir DIRECTORY, --cache-dir DIRECTORY, --help, --version
Authentication: GITHUB_TOKEN or GH_TOKEN. No credentials are written to manifests.
""".trimIndent()

fun runCli(args: Array<String>) {
    try { executeCli(args.toList()) }
    catch (e: Exception) { Platform.error("Error: ${e.message ?: e::class.simpleName}"); Platform.exit(1) }
}
fun executeCli(args: List<String>) {
    if (args.isEmpty() || "--help" in args || args == listOf("help")) { println(usage); return }
    if (args == listOf("--version")) { println(VERSION); return }
    val flags = setOf("--offline", "--dry-run", "--all")
    val options = setOf("--project-dir", "--cache-dir", "--tag", "--branch", "--commit", "--name", "--plugin", "--path", "--license-file", "--destination", "--mode", "--enable-in", "--disable-in", "--version")
    val values = mutableMapOf<String, String>(); val enabled = mutableSetOf<String>(); val positional = mutableListOf<String>(); val licenses = mutableListOf<String>()
    val disableIn = mutableSetOf<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i++]
        when {
            arg in flags -> checkInstall(enabled.add(arg)) { "Repeated option: $arg" }
            arg in options -> {
                checkInstall(i < args.size && !args[i].startsWith("--")) { "Missing value for $arg" }
                val value = args[i++]
                when (arg) {
                    "--license-file" -> licenses += value
                    "--disable-in" -> checkInstall(disableIn.add(value)) { "Repeated module: $value" }
                    else -> checkInstall(values.put(arg, value) == null) { "Repeated option: $arg" }
                }
            }
            arg.startsWith('-') -> fail("Unknown option: $arg")
            else -> positional += arg
        }
    }
    val command = positional.firstOrNull() ?: fail("Missing command")
    val refs = listOf("tag", "branch", "commit").mapNotNull { kind -> values["--$kind"]?.let { Ref(kind, it) } }
    checkInstall(refs.size <= 1) { "Choose exactly one tag, branch, or commit" }
    val ref = refs.singleOrNull()
    val root = fs.canonicalize(systemPath(values["--project-dir"] ?: "."))
    if (Platform.windows) {
        var ancestor: okio.Path? = root
        while (ancestor != null) { checkNoLink(ancestor); ancestor = ancestor.parent }
    }
    checkInstall(fs.metadata(root).isDirectory) { "Project root must be a directory" }
    val common = setOf("--project-dir", "--cache-dir")
    val allowed = when (command) {
        "add" -> options - common - setOf("--disable-in", "--version")
        "update", "diff" -> setOf("--tag", "--branch", "--commit")
        "remove" -> setOf("--disable-in")
        "wrapper" -> setOf("--version")
        "validate" -> setOf("--plugin")
        "sync", "status", "verify", "outdated" -> emptySet()
        else -> fail("Unknown command '$command'; use --help")
    }
    checkInstall((values.keys - common - allowed).isEmpty() && (licenses.isEmpty() || command == "add") && (disableIn.isEmpty() || command == "remove")) { "Options do not apply to $command" }
    checkInstall(enabled.all { when (it) { "--all" -> command in setOf("update", "diff", "outdated"); "--offline" -> command == "sync"; else -> command in setOf("add", "update", "sync", "diff", "remove", "wrapper") } }) { "Flags do not apply to $command" }
    if (command == "validate") {
        checkInstall(positional.size == 1) { "validate accepts only --plugin and --project-dir" }
        validateProducer(root, values["--plugin"])
        return
    }
    val cache = systemPath(values["--cache-dir"] ?: Platform.env("KTC_PLUGINS_CACHE_DIR") ?: defaultCache())
    val github = GitHub(cache / "archives", "--offline" in enabled)
    val installer = Installer(root, cache, github)
    val dry = "--dry-run" in enabled
    when (command) {
        "add" -> {
            checkInstall(positional.size == 2 && ref != null) { "add requires OWNER/REPO and one explicit ref" }
            installer.add(Declaration(positional[1], ref!!, values["--mode"] ?: "vendored", values["--plugin"], values["--destination"], values["--path"], if (licenses.isEmpty()) null else licenses), values["--name"], values["--enable-in"], dry)
        }
        "update" -> { checkInstall(positional.size <= 2) { "update takes one name or --all" }; installer.update(positional.getOrNull(1), "--all" in enabled, ref, dry) }
        "diff" -> { checkInstall(positional.size <= 2) { "diff takes one name or --all" }; installer.update(positional.getOrNull(1), "--all" in enabled, ref, true) }
        "outdated" -> { checkInstall(positional.size <= 2) { "outdated takes one name or --all" }; installer.outdated(positional.getOrNull(1), "--all" in enabled || positional.size == 1) }
        "remove" -> { checkInstall(positional.size == 2) { "remove requires one plugin name" }; installer.remove(positional[1], disableIn, dry) }
        "wrapper" -> {
            checkInstall(positional == listOf("wrapper", "update")) { "Use wrapper update --version VERSION" }
            val version = releaseVersion(values["--version"] ?: fail("wrapper update requires --version"))
            installer.updateWrappers(version, GitHubReleaseSource(github), dry)
        }
        "sync" -> { checkInstall(positional.size == 1) { "sync takes no names" }; installer.sync(dry) }
        "status", "verify" -> { checkInstall(positional.size == 1) { "$command takes no names" }; installer.status(command == "verify") }
    }
}
internal fun defaultCache(): String {
    if (Platform.windows) return (Platform.env("LOCALAPPDATA") ?: fail("Set --cache-dir or LOCALAPPDATA")) + "/ktc-plugins"
    val home = Platform.env("HOME") ?: fail("Set --cache-dir or HOME")
    // macOS follows the native cache convention; other POSIX hosts honor XDG.
    return if (Platform.macos) "$home/Library/Caches/ktc-plugins" else (Platform.env("XDG_CACHE_HOME") ?: "$home/.cache") + "/ktc-plugins"
}
