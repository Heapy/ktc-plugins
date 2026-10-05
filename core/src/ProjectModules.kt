package io.heapy.ktcplugins

import okio.Path
import com.charleskorn.kaml.YamlNull

/** Enumerate registered module globs plus the implicit root module, without following links. */
fun projectModuleFiles(root: Path, project: String?, excluded: Set<String>): Map<String, String> {
    val files = linkedMapOf<String, String>()
    var visited = 0
    fun relative(path: Path) = if (path == root) "." else path.relativeTo(root).toString().replace('\\', '/')
    fun add(dir: Path) {
        val module = relative(dir)
        if (excluded.any { module == it || module.startsWith("$it/") }) return
        val file = if (module == ".") "module.yaml" else "$module/module.yaml"
        if (fs.exists(contained(root, file))) files[module] = file
    }
    fun children(dir: Path): List<Path> = fs.list(dir).filter { path ->
        checkInstall(++visited <= MAX_FILES) { "Module glob scan exceeds directory entry limit" }
        path.name !in setOf(".git", ".ktc-plugins", ".kotlin", ".idea", ".agents", ".codex", "node_modules") && (Platform.isLink(path) || fs.metadata(path).isDirectory)
    }
    fun resolve(dir: Path, tokens: List<String>, at: Int, depth: Int) {
        checkInstall(depth <= 100) { "Module glob nesting exceeds limit" }
        val rel = relative(dir)
        if (excluded.any { rel == it || rel.startsWith("$it/") }) return
        checkNoLink(dir)
        if (at == tokens.size) { add(dir); return }
        val token = tokens[at]
        if (token == "**") {
            resolve(dir, tokens, at + 1, depth + 1)
            children(dir).filter { it.name != "build" }.forEach { resolve(it, tokens, at, depth + 1) }
        } else if ('*' in token || '?' in token) {
            val regex = Regex(token.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString(""))
            children(dir).filter { regex.matches(it.name) && it.name != "build" }.forEach { resolve(it, tokens, at + 1, depth + 1) }
        } else {
            val path = dir / token
            if (fs.exists(path)) { checkNoLink(path); if (fs.metadata(path).isDirectory) resolve(path, tokens, at + 1, depth + 1) }
        }
    }
    add(root)
    val modules = project?.let { parseYaml(it).map()["modules"] }
    if (modules != null && modules !is YamlNull) for (node in modules.list()) {
        val pattern = node.string().removePrefix("./")
        checkInstall(pattern.isNotEmpty() && pattern.length <= 500 && !pattern.startsWith('/') && pattern.none { it.code < 32 || it in "\\:[]{}\"<>|" }) { "Unsupported module glob for removal: $pattern" }
        val tokens = pattern.split('/')
        checkInstall(tokens.none { it.isEmpty() || it in setOf(".", "..") || "**" in it && it != "**" }) { "Unsafe module glob: $pattern" }
        resolve(root, tokens, 0, 0)
    }
    return files
}
