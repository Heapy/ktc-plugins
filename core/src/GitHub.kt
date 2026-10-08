package io.heapy.ktcplugins

import okio.Path
import okio.ByteString.Companion.toByteString

interface Remote {
    fun resolve(repository: String, ref: Ref): String
    fun archive(repository: String, commit: String): ByteArray
}

class GitHub(private val cache: Path, private val offline: Boolean = false, private val request: ((String, Int) -> ByteArray)? = null) : Remote {
    private val token = Platform.env("GITHUB_TOKEN") ?: Platform.env("GH_TOKEN")
    override fun resolve(repository: String, ref: Ref): String {
        checkInstall(!offline) { "Ref resolution needs network access" }
        Declaration(repository, ref) // validate before composing URLs
        if (ref.kind == "commit") {
            val body = get("https://api.github.com/repos/$repository/git/commits/${ref.value}")
            return parseYaml(body.decodeToString()).map().text("sha").also(::validSha)
        }
        val namespace = if (ref.kind == "tag") "tags" else "heads"
        var obj = parseYaml(get("https://api.github.com/repos/$repository/git/ref/$namespace/${urlEncode(ref.value)}").decodeToString()).map().required("object").map()
        repeat(10) {
            val sha = obj.text("sha").also(::validSha)
            when (obj.text("type")) {
                "commit" -> return sha
                "tag" -> obj = parseYaml(get("https://api.github.com/repos/$repository/git/tags/$sha").decodeToString()).map().required("object").map()
                else -> fail("GitHub ref does not point to a commit")
            }
        }
        fail("Annotated tag chain is too deep")
    }
    override fun archive(repository: String, commit: String): ByteArray {
        validSha(commit); Declaration(repository, Ref("commit", commit))
        fs.createDirectories(cache)
        Platform.permissions(cache, true, private = true)
        val key = sha256("archive-v1\n$repository\n$commit")
        Platform.lock(cache / "$key.lock").use {
            val file = cache / "$key.zip"
            if (fs.exists(file)) return fs.readBytes(file, MAX_ARCHIVE.toLong())
            checkInstall(!offline) { "Offline cache miss for $repository@$commit" }
            val bytes = get("https://api.github.com/repos/$repository/zipball/$commit", MAX_ARCHIVE)
            readZip(bytes, allowUnselectedSymlinks = true) // symlinks are never extracted; selection rejects them
            val temp = cache / "$key.tmp"
            writeBytes(temp, bytes)
            Platform.permissions(temp, false, private = true)
            fs.atomicMove(temp, file)
            return bytes
        }
    }
    internal fun get(url: String, limit: Int = 8 * 1024 * 1024, releaseAsset: Boolean = false): ByteArray {
        request?.let { return it(url, limit) }
        val dir = tempDirectory()
        try {
            val config = dir / "curl.config"
            val auth = token?.let {
                checkInstall(it.none { c -> c.code < 32 }) { "Invalid GitHub token" }
                "header = \"Authorization: Bearer ${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"\n"
            } ?: ""
            val accept = if (releaseAsset) "application/octet-stream" else "application/vnd.github+json"
            writeText(config, "header = \"Accept: $accept\"\nheader = \"X-GitHub-Api-Version: 2022-11-28\"\n$auth")
            Platform.permissions(config, false, private = true)
            var next = url
            repeat(4) { attempt ->
                val body = dir / "body"; val headers = dir / "headers"
                checkInstall(next.none { it.code < 32 }) { "Invalid GitHub redirect URL" }
                val requestConfig = dir / "request.config"
                val escapedUrl = next.replace("\\", "\\\\").replace("\"", "\\\"")
                // Private archive redirects may contain a short-lived token in their URL.
                // Keep the URL private too, rather than exposing it in process arguments.
                writeText(requestConfig, (if (next.startsWith("https://api.github.com/")) readText(config) else "") + "url = \"$escapedUrl\"\n")
                Platform.permissions(requestConfig, false, private = true)
                val args = mutableListOf("curl", "--disable", "--silent", "--show-error", "--proto", "=https", "--connect-timeout", "15", "--max-time", "120", "--retry", "2", "--retry-max-time", "60", "--max-filesize", limit.toString(), "--user-agent", "ktc-plugins/$VERSION", "--dump-header", headers.toString(), "--output", body.toString(), "--write-out", "%{http_code}")
                args += listOf("--config", requestConfig.toString())
                val result = Platform.run(args)
                checkInstall(result.code == 0) { "GitHub request failed (curl ${result.code}); check network and curl installation" }
                val status = result.stdout.trim().toIntOrNull() ?: fail("Invalid HTTP status")
                if (status in 200..299) return fs.readBytes(body, limit.toLong())
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = readText(headers).lineSequence().lastOrNull { it.startsWith("location:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: fail("Missing GitHub redirect location")
                    val allowed = if (releaseAsset) listOf("https://release-assets.githubusercontent.com/", "https://objects.githubusercontent.com/") else listOf("https://codeload.github.com/")
                    checkInstall(location.startsWith("https://api.github.com/") || allowed.any(location::startsWith)) { "Rejected redirect to an unapproved host" }
                    next = location
                } else fail("GitHub returned HTTP $status; check the repository/ref, access token and rate limit")
            }
            fail("Too many GitHub redirects")
        } finally { deleteTree(dir) }
    }
}
private fun validSha(value: String) { checkInstall(Regex("[0-9a-f]{40}").matches(value)) { "Invalid commit SHA returned by GitHub" } }
fun urlEncode(value: String): String = value.encodeToByteArray().joinToString("") {
    val c = it.toInt() and 255
    if (c in 65..90 || c in 97..122 || c in 48..57 || c in listOf(45, 46, 95, 126)) c.toChar().toString()
    else "%" + c.toString(16).uppercase().padStart(2, '0')
}

data class Prepared(val lock: Locked, val payload: Map<String, Payload>)
fun prepare(declaration: Declaration, commit: String, zip: ByteArray, diagnostic: (String) -> Unit = {}): Prepared {
    return prepareRepository(declaration, commit, readZip(zip, allowUnselectedSymlinks = true), diagnostic)
}
fun prepareRepository(declaration: Declaration, commit: String, repo: Map<String, Payload>, diagnostic: (String) -> Unit = {}): Prepared {
    checkInstall(repo["ktc-plugin.yaml"]?.symbolicLink != true) { "Producer manifest must not be a symlink" }
    val producerBytes = repo["ktc-plugin.yaml"]?.bytes
    val root = producerBytes?.let { schema(it.decodeToString()).required("plugins").map() }
    val selected = declaration.plugin ?: if (root?.size == 1) root.keys.single() else null
    if (root != null && declaration.path == null) checkInstall(selected != null) { "Repository declares several plugins; select one with --plugin" }
    val entry = selected?.let { root?.get(it)?.map() ?: if (declaration.path == null) fail("Unknown producer plugin: $it") else null }
    entry?.keysAllowed("module", "licenseFiles", "catalog")
    val source = declaration.path ?: entry?.text("module") ?: fail("No producer manifest: specify --path (use . for a root plugin)")
    safeRelative(source, true)
    val prefix = if (source == ".") "" else "$source/"
    val payload = repo.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }.toMutableMap()
    checkInstall(payload.values.none { it.symbolicLink }) { "Selected plugin contains a symlink; only regular files are supported" }
    val module = payload["module.yaml"]?.bytes?.decodeToString() ?: fail("Selected directory has no module.yaml")
    val m = parseYaml(module).map()
    checkInstall(m.text("product") == "jvm/amper-plugin" && "plugin.yaml" in payload) { "Selected module is not a Kotlin Toolchain build plugin" }
    val originalName = if (source == ".") declaration.repository.substringAfter('/') else source.substringAfterLast('/')
    val id = m["pluginInfo"]?.map()?.optional("id") ?: originalName
    safeName(id)
    val destination = declaration.destination ?: "plugins/$id"
    validateDestination(destination)
    checkInstall(m["pluginInfo"]?.map()?.optional("id") != null || destination.substringAfterLast('/') == originalName) { "Keep destination basename '$originalName' to preserve the inferred plugin ID" }
    val catalog = entry?.get("catalog")?.let { node ->
        val spec = catalogSpec(node)
        val file = repo[spec.file] ?: fail("Missing producer catalog: ${spec.file}")
        checkInstall(!file.symbolicLink) { "Producer catalog must not be a symlink" }
        val resolver = ProducerCatalog(file.bytes.decodeToString())
        for ((path, contents) in payload.toMap()) {
            if (path == "module.yaml" || path == "plugin.yaml" || path.endsWith(".module-template.yaml")) {
                payload[path] = contents.copy(bytes = resolveCatalogYaml(contents.bytes.decodeToString(), resolver).encodeToByteArray())
            }
        }
        fun exportedAlias(alias: String) = catalogAlias("ktc-${id.replace('.', '-').replace('_', '-')}-${alias.replace('.', '-').replace('_', '-')}")
        val exports = spec.exports.associate { alias -> exportedAlias(alias) to resolver.resolve(alias) }
        val refs = spec.exports.mapNotNull { alias -> resolver.versionRef(alias)?.let { alias to it } }.toMap()
        checkInstall(refs.values.distinct().map(::exportedAlias).distinct().size == refs.values.distinct().size) {
            "Catalog version aliases have colliding accessors"
        }
        LockedCatalog(spec.file, file.record.sha256, exports,
            versionRefs = refs.entries.associate { (alias, ref) -> exportedAlias(alias) to exportedAlias(ref) })
    }
    validatePortable(payload)
    val licenses = declaration.licenseFiles ?: entry?.get("licenseFiles")?.list()?.map { it.string() } ?: repo.keys.filter { '/' !in it && Regex("(?i)(LICENSE|NOTICE|COPYING)(\\.[a-z]+)?").matches(it) }
    if (licenses.isEmpty()) diagnostic("No license/notice files detected for ${declaration.repository}; check upstream permissions")
    val reservedLicenses = payload.keys.any { it.substringBefore('/').lowercase() == ".ktc-licenses" }
    for (license in licenses) {
        safeRelative(license)
        val file = repo[license] ?: fail("Missing declared license file: $license")
        checkInstall(!file.symbolicLink) { "License file must not be a symlink: $license" }
        if (!license.startsWith(prefix) || prefix.isEmpty()) {
            if (prefix.isNotEmpty()) {
                val target = ".ktc-licenses/$license"
                checkInstall(!reservedLicenses) { "License destination collides with the reserved .ktc-licenses directory" }
                payload[target] = file
            }
        }
    }
    val files = payload.mapValues { it.value.record }
    checkInstall(files.isNotEmpty()) { "Empty plugin payload" }
    val producer = if (producerBytes != null && selected != null) Producer(producerBytes.toByteString().sha256().hex(), selected) else null
    return Prepared(Locked(declaration.repository, declaration.digest, commit, source, destination, declaration.mode, id, producer, treeDigest(files), files, catalog), payload)
}
private fun validatePortable(payload: Map<String, Payload>) {
    for ((path, file) in payload.filterKeys { it.endsWith(".yaml") }) {
        val text = file.bytes.decodeToString()
        if (path != "module.yaml" && path != "plugin.yaml" && !path.endsWith(".module-template.yaml")) continue
        val node = parseYaml(text)
        fun visit(node: com.charleskorn.kaml.YamlNode) {
            when (node) {
                is com.charleskorn.kaml.YamlScalar -> checkInstall(!node.content.contains("\$libs.")) { "$path depends on a producer version catalog; replace \$libs.* aliases with pinned coordinates" }
                is com.charleskorn.kaml.YamlMap -> { node.entries.keys.forEach(::visit); node.entries.values.forEach(::visit) }
                is com.charleskorn.kaml.YamlList -> node.items.forEach(::visit)
                is com.charleskorn.kaml.YamlTaggedNode -> visit(node.innerNode)
                else -> Unit
            }
        }
        visit(node)
    }
    val module = parseYaml(payload.getValue("module.yaml").bytes.decodeToString()).map()
    for (key in listOf("dependencies", "test-dependencies", "apply")) {
        module.filterKeys { it == key || it.startsWith("$key@") }.values.forEach { list ->
            list.list().forEach { n ->
                val value = if (n is com.charleskorn.kaml.YamlScalar) n.content else n.map().keys.single()
                checkInstall(!value.startsWith("//") && !value.startsWith("../")) { "Plugin has an external local path: $value" }
                if (key == "apply" || value.startsWith("./")) {
                    val relative = value.removePrefix("./")
                    safeRelative(relative)
                    checkInstall(if (key == "apply") relative in payload else "$relative/module.yaml" in payload) { "Plugin references a missing local file/module: $value" }
                    checkInstall(key == "apply") { "Local helper modules require bundle support" }
                }
            }
        }
    }
}
