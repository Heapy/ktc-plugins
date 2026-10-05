package io.heapy.ktcplugins

import com.charleskorn.kaml.*

/** Validate with a full YAML parser, but insert text without round-tripping unrelated content. */
fun appendYamlList(text: String, key: String, values: List<String>, covers: (String, String) -> Boolean = { a, b -> a == b }): String {
    var result = text
    for (value in values) {
        val root = parseYaml(result) as? YamlMap ?: fail("Expected a root YAML map")
        val pair = root.entries.entries.firstOrNull { it.key.content == key }
        if (pair == null) { result = appendRoot(result, "$key:\n  - ${quote(value)}\n"); continue }
        val existing = if (pair.value is YamlNull) emptyList() else pair.value.list().map { it.string() }
        if (existing.any { covers(it, value) }) continue
        val lines = result.split('\n').toMutableList()
        val index = pair.key.location.line - 1
        checkInstall(pair.key.location.column == 1 && index in lines.indices) { "Cannot safely edit '$key'; use a plain root mapping" }
        val line = lines[index]; val tail = line.substringAfter(':').trimStart()
        if (tail.startsWith('[')) {
            val close = flowClose(line, line.indexOf('['))
            checkInstall(close >= 0) { "Cannot edit multiline/anchored '$key' flow list" }
            val prefix = if (existing.isEmpty()) "" else ", "
            lines[index] = line.substring(0, close) + prefix + quote(value) + line.substring(close)
        } else {
            checkInstall(tail.isEmpty() || tail.startsWith('#')) { "Cannot safely edit anchored or complex '$key'; use a plain block list" }
            val nextKey = root.entries.keys.map { it.location.line - 1 }.filter { it > index }.minOrNull() ?: lines.size
            val end = insertionLine(lines, nextKey)
            val indent = (index + 1 until nextKey).firstOrNull { lines[it].trimStart().startsWith("- ") }?.let { lines[it].takeWhile(Char::isWhitespace) } ?: "  "
            lines.add(end, "$indent- ${quote(value)}")
        }
        result = lines.joinToString("\n")
        parseYaml(result) // fail before any project mutation
    }
    return result
}
private fun insertionLine(lines: List<String>, before: Int): Int {
    var i = before
    while (i > 0 && (lines[i - 1].isBlank() || lines[i - 1].trimStart().startsWith('#'))) i--
    return i
}
private fun appendRoot(text: String, addition: String): String {
    checkInstall(text.lineSequence().none { it.trim() == "..." }) { "Cannot append YAML after a document end marker" }
    return text + if (text.endsWith('\n') || text.isEmpty()) addition else "\n$addition"
}
private fun flowClose(line: String, start: Int): Int {
    var quoted: Char? = null; var escaped = false
    for (i in start + 1 until line.length) {
        val c = line[i]
        if (escaped) { escaped = false; continue }
        if (quoted == '"' && c == '\\') { escaped = true; continue }
        if (quoted != null) { if (c == quoted) quoted = null; continue }
        if (c == '\'' || c == '"') quoted = c
        else if (c == ']') return i
        else if (c == '#') return -1
    }
    return -1
}
fun moduleGlob(pattern: String, path: String): Boolean {
    val p = pattern.removePrefix("./")
    if ("**" in p || p.startsWith("//")) return false
    val regex = p.map { when (it) { '*' -> "[^/]*"; '?' -> "[^/]"; else -> Regex.escape(it.toString()) } }.joinToString("")
    return Regex(regex).matches(path)
}
fun registerPlugin(text: String?, destination: String): String {
    val base = text ?: "modules: []\n"
    val modules = appendYamlList(base, "modules", listOf(destination), ::moduleGlob)
    return appendYamlList(modules, "plugins", listOf("//$destination")) { a, b -> a.removePrefix("//").removePrefix("./") == b.removePrefix("//") }
}
fun addMapEntry(text: String, key: String, name: String, body: String): String {
    val root = parseYaml(text) as? YamlMap ?: fail("Expected a root map")
    val pair = root.entries.entries.firstOrNull { it.key.content == key }
        ?: return appendRoot(text, "$key:\n  ${quote(name)}:\n$body")
    val existing = if (pair.value is YamlNull) emptyMap() else pair.value.map()
    checkInstall(name !in existing) { "Entry '$name' already exists" }
    val lines = text.split('\n').toMutableList(); val index = pair.key.location.line - 1
    val tail = lines[index].substringAfter(':').trimStart()
    checkInstall(pair.key.location.column == 1) { "Cannot safely edit $key" }
    if (tail.startsWith("{}")) lines[index] = lines[index].replaceFirst("{}", "\n  ${quote(name)}:\n${body.trimEnd()}")
    else {
        checkInstall(tail.isEmpty() || tail.startsWith('#')) { "Cannot edit a nonempty flow/anchored mapping '$key'; use a block map" }
        val next = root.entries.keys.map { it.location.line - 1 }.filter { it > index }.minOrNull() ?: lines.size
        lines.add(insertionLine(lines, next), "  ${quote(name)}:\n${body.trimEnd()}")
    }
    return lines.joinToString("\n").also(::parseYaml)
}
fun changeRef(text: String, alias: String, ref: Ref): String {
    val node = parseYaml(text).map().required("plugins").map().required(alias).map().required("ref")
    val key = (node as? YamlMap)?.entries?.keys?.singleOrNull() ?: fail("Cannot safely replace ref")
    val lines = text.split('\n').toMutableList(); val index = key.location.line - 1
    val line = lines[index]
    checkInstall(line.takeWhile(Char::isWhitespace).length + 1 == key.location.column && line.substringAfter(':').trimStart().firstOrNull() !in listOf('{', '[', '&', '*')) { "Use a block ref mapping before changing refs" }
    // Preserve an inline comment, recognizing quoted # characters first.
    var quote: Char? = null; var escaped = false; var comment = ""
    for (i in line.indices) {
        val c = line[i]
        if (escaped) { escaped = false; continue }
        if (quote == '"' && c == '\\') { escaped = true; continue }
        if (quote != null) { if (c == quote) quote = null }
        else if (c == '\'' || c == '"') quote = c
        else if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) { comment = " " + line.substring(i); break }
    }
    lines[index] = line.takeWhile(Char::isWhitespace) + ref.kind + ": " + io.heapy.ktcplugins.quote(ref.value) + comment
    return lines.joinToString("\n").also(::declarations)
}
fun enablePlugin(text: String, id: String): String {
    val plugins = parseYaml(text).map()["plugins"]
    if (plugins != null && plugins !is YamlNull && id in plugins.map()) {
        val config = plugins.map().getValue(id)
        val enabled = when (config) {
            is YamlScalar -> config.content == "enabled"
            is YamlMap -> config.map()["enabled"]?.string() == "true"
            else -> false
        }
        checkInstall(enabled) { "Plugin '$id' already has configuration; enable it explicitly in module.yaml" }
        return text
    }
    // A map scalar is simpler than the nested body used by declarations.
    val inserted = addMapEntry(text, "plugins", id, "    enabled: true\n")
    return inserted
}
fun ignoredEntries(text: String, names: List<String>): String {
    val additions = names.map { "/$it/" }.filter { it !in text.lineSequence().map(String::trim).toSet() }
    if (additions.isEmpty()) return text
    return text + (if (text.endsWith('\n') || text.isEmpty()) "" else "\n") + "# ktc-plugins managed entries\n" + additions.joinToString("\n", postfix = "\n")
}
