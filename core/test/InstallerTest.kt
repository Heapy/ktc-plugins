package io.heapy.ktcplugins

import kotlin.test.*
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.decodeHex

class InstallerTest {
    private val first = "a".repeat(40)
    private val second = "b".repeat(40)
    private val decl get() = Declaration("owner/repo", Ref("branch", "main"), path = "plugin", licenseFiles = listOf("LICENSE"))
    private fun archive(source: String = "first", removed: Boolean = false): ByteArray = zip(buildMap {
        put("plugin/module.yaml", "product: jvm/amper-plugin\npluginInfo:\n  id: sample\n".encodeToByteArray())
        put("plugin/plugin.yaml", "tasks: {}\n".encodeToByteArray())
        put("plugin/src/main.kt", source.encodeToByteArray())
        if (!removed) put("plugin/src/old.kt", "old".encodeToByteArray())
        put("LICENSE", "license".encodeToByteArray())
    })
    private class FakeRemote(var head: String, val archives: Map<String, ByteArray>) : Remote {
        var resolutions = 0
        override fun resolve(repository: String, ref: Ref): String { resolutions++; return if (ref.kind == "commit") ref.value else head }
        override fun archive(repository: String, commit: String): ByteArray = archives.getValue(commit)
    }
    private fun project(test: (Path, Path) -> Unit) {
        val temp = tempDirectory()
        try { val root = temp / "project"; val cache = temp / "cache"; fs.createDirectories(root); test(root, cache) }
        finally { deleteTree(temp) }
    }
    @Test fun restoreDoesNotAdvanceBranchAndUpdatesRemoveOnlyPristineFiles() = project { root, cache ->
        val remote = FakeRemote(first, mapOf(first to archive(), second to archive("second", removed = true)))
        val installer = Installer(root, cache, remote, {})
        installer.add(decl.copy(mode = "downloaded"), "local-name", null, false)
        assertEquals(1, remote.resolutions)
        assertEquals("license", readText(root / "plugins/sample/.ktc-licenses/LICENSE"))
        assertTrue("/sample/" in readText(root / "plugins/.gitignore"))
        remote.head = second
        deleteTree(root / "plugins/sample")
        installer.sync()
        assertEquals(1, remote.resolutions)
        assertEquals("first", readText(root / "plugins/sample/src/main.kt"))
        installer.update("local-name", false, null, false)
        assertEquals("second", readText(root / "plugins/sample/src/main.kt"))
        assertFalse(fs.exists(root / "plugins/sample/src/old.kt"))
        installer.status(verify = true)
    }
    @Test fun refusesEditsAndUnexpectedFilesAndHasNoOpUpdates() = project { root, cache ->
        val remote = FakeRemote(first, mapOf(first to archive()))
        val installer = Installer(root, cache, remote, {})
        installer.add(decl, null, null, false)
        val lock = readText(root / "ktc-plugins.lock.yaml")
        installer.update("sample", false, null, false)
        assertEquals(lock, readText(root / "ktc-plugins.lock.yaml"))
        writeText(root / "plugins/sample/src/main.kt", "local edit")
        assertFailsWith<InstallError> { installer.update("sample", false, null, false) }
        assertEquals("local edit", readText(root / "plugins/sample/src/main.kt"))
        assertFailsWith<InstallError> { installer.sync() }
        writeText(root / "plugins/sample/src/main.kt", "first")
        writeText(root / "plugins/sample/unowned", "keep")
        assertFailsWith<InstallError> { installer.update("sample", false, null, false) }
        assertEquals("keep", readText(root / "plugins/sample/unowned"))
    }
    @Test fun dryRunPreservesTheEntireProjectAndManifestMismatchFailsSync() = project { root, cache ->
        writeText(root / "module.yaml", "product: jvm/app\n")
        val remote = FakeRemote(first, mapOf(first to archive()))
        val installer = Installer(root, cache, remote, {})
        val before = fingerprint(root)
        installer.add(decl, null, ".", true)
        assertEquals(before, fingerprint(root))
        installer.add(decl, null, ".", false)
        assertTrue("sample" in readText(root / "module.yaml"))
        writeText(root / "ktc-plugins.yaml", readText(root / "ktc-plugins.yaml").replace("'main'", "'other'"))
        assertFailsWith<InstallError> { installer.sync() }
    }
    @Test fun failedTransactionRollsBackMetadataAndNewDirectories() = project { root, cache ->
        writeText(root / "project.yaml", "modules: [] # keep\n")
        val before = readText(root / "project.yaml")
        val remote = FakeRemote(first, mapOf(first to archive()))
        assertFailsWith<InstallError> { Installer(root, cache, remote, {}, failAfter = 2).add(decl, null, null, false) }
        assertEquals(before, readText(root / "project.yaml"))
        assertFalse(fs.exists(root / "plugins/sample"))
        assertFalse(fs.exists(root / "ktc-plugins.yaml"))
        assertFalse(fs.exists(root / ".ktc-plugins/transaction"))
    }
    @Test fun movedTagRequiresExplicitSelection() = project { root, cache ->
        val remote = FakeRemote(first, mapOf(first to archive(), second to archive("second")))
        val installer = Installer(root, cache, remote, {})
        val tagged = decl.copy(ref = Ref("tag", "v1"))
        installer.add(tagged, null, null, false)
        remote.head = second
        assertFailsWith<InstallError> { installer.update("sample", false, null, false) }
        installer.update("sample", false, Ref("tag", "v1"), false)
        assertEquals("second", readText(root / "plugins/sample/src/main.kt"))
    }
    @Test fun producerSelectionAndInferredIdsSurviveInstallation() {
        val producer = "schemaVersion: 1\nplugins:\n  detekt:\n    module: plugins/heapy-detekt\n    licenseFiles: [LICENSE]\n"
        val payload = zip(mapOf("ktc-plugin.yaml" to producer.encodeToByteArray(), "plugins/heapy-detekt/module.yaml" to "product: jvm/amper-plugin\n".encodeToByteArray(), "plugins/heapy-detekt/plugin.yaml" to "tasks: {}\n".encodeToByteArray(), "LICENSE" to "Apache".encodeToByteArray()))
        val d = Declaration("owner/repo", Ref("tag", "v1"))
        val prepared = prepare(d, first, payload)
        assertEquals("plugins/heapy-detekt", prepared.lock.destination)
        assertEquals("detekt", prepared.lock.producer?.plugin)
        assertFailsWith<InstallError> { prepare(d.copy(destination = "plugins/detekt"), first, payload) }
        assertEquals(prepared.lock, locks(lockYaml(mapOf("detekt" to prepared.lock))).getValue("detekt"))
    }
    @Test fun rejectsCatalogAliasesAndMaliciousPaths() {
        val payload = zip(mapOf("plugin/module.yaml" to "product: jvm/amper-plugin\ndependencies:\n  - \$libs.sql.core\n".encodeToByteArray(), "plugin/plugin.yaml" to "tasks: {}\n".encodeToByteArray(), "LICENSE" to "License".encodeToByteArray()))
        assertFailsWith<InstallError> { prepare(decl, first, payload) }
        for (path in listOf("../escape", "/absolute", "C:/escape", "a/../escape", "a\\escape", "CON", "a/nul.txt")) assertFailsWith<InstallError> { safeRelative(path) }
        assertFailsWith<InstallError> { readZip(zip(mapOf("../escape" to byteArrayOf(1)))) }
        assertFailsWith<InstallError> { readZip(zip(mapOf("a" to byteArrayOf(1), "A" to byteArrayOf(2)))) }
        assertFailsWith<InstallError> { readZip(zip(mapOf("a/one" to byteArrayOf(1), "A/two" to byteArrayOf(2)))) }
        assertFailsWith<InstallError> { readZip(zip(mapOf("link" to "target".encodeToByteArray()), symlink = true)) }
    }
    @Test fun yamlInsertionPreservesCommentsAndRecognizesGlobs() {
        val original = "# project header\nmodules: [app, 'plugins/*'] # list comment\n# user repositories\nrepositories: []\n"
        val changed = registerPlugin(original, "plugins/sample")
        assertTrue(changed.startsWith(original))
        assertEquals(2, parseYaml(changed).map().required("modules").list().size)
        assertEquals(changed, registerPlugin(changed, "plugins/sample"))
        val manifest = "# mine\nschemaVersion: 1\nplugins:\n  sample:\n    repository: owner/repo\n    ref:\n      branch: main # track upstream\n    mode: vendored\n"
        val updated = changeRef(manifest, "sample", Ref("tag", "v1"))
        assertTrue("# mine" in updated && "# track upstream" in updated)
        assertEquals(Ref("tag", "v1"), declarations(updated).getValue("sample").ref)
    }
    @Test fun deflateAndChecksumsMatchKnownVectors() {
        val compressed = "cb48cdc9c957c8409000".decodeHex().toByteArray()
        assertEquals("hello hello hello", inflate(compressed, 17).decodeToString())
        assertEquals(0xcbf43926, crc32("123456789".encodeToByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc"))
        assertFailsWith<InstallError> { inflate(compressed, 2) }
        assertFailsWith<InstallError> { inflate(compressed.copyOf(2), 17) }
    }
    @Test fun projectLocksExcludeConcurrentInstallers() = project { root, _ ->
        Platform.lock(root / "lock").use { assertFailsWith<InstallError> { Platform.lock(root / "lock") } }
        Platform.lock(root / "lock").close()
    }
    @Test fun manualDeclarationsCanBeResolvedWithoutAdoptingExistingDirectories() = project { root, cache ->
        val installer = Installer(root, cache, FakeRemote(first, mapOf(first to archive())), {})
        writeText(root / "ktc-plugins.yaml", manifestYaml(mapOf("manual" to decl)))
        assertFailsWith<InstallError> { installer.sync() }
        writeText(root / "plugins/sample/keep", "mine")
        assertFailsWith<InstallError> { installer.update("manual", false, null, false) }
        assertEquals("mine", readText(root / "plugins/sample/keep"))
        deleteTree(root / "plugins/sample")
        installer.update("manual", false, null, false)
        installer.status(verify = true)
    }
    @Test fun preservesMultipleLicenseFilesAndRejectsReservedDestinations() {
        val payload = zip(mapOf(
            "plugin/module.yaml" to "product: jvm/amper-plugin\npluginInfo: {id: sample}\n".encodeToByteArray(),
            "plugin/plugin.yaml" to "tasks: {}\n".encodeToByteArray(),
            "LICENSE" to "license".encodeToByteArray(), "NOTICE" to "notice".encodeToByteArray(),
        ))
        val d = decl.copy(licenseFiles = listOf("LICENSE", "NOTICE"))
        val p = prepare(d, first, payload)
        assertEquals("notice", p.payload.getValue(".ktc-licenses/NOTICE").bytes.decodeToString())
        for (destination in listOf("project.yaml", ".ktc-plugins/a", ".git/hooks")) {
            assertFailsWith<InstallError> { prepare(d.copy(destination = destination), first, payload) }
        }
    }
    @Test fun dynamicDeflateMatchesAnIndependentZlibVector() {
        val compressed = "edc9c10980301000b0bf53dc6a27162b5cb5a0fba31fb7c82f90acd933d6f664ec3946c6d6ea739bf751d7b9a4d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badb5d65a6badf5df2f".decodeHex().toByteArray()
        assertEquals("alpha beta gamma delta epsilon\n".repeat(1000), inflate(compressed, 31000).decodeToString())
    }
    @Test fun recoveryRestoresInterruptedReplacementButProtectsLaterEdits() = project { root, _ ->
        val dir = root / ".ktc-plugins/transaction"
        fun interrupted() {
            writeText(dir / "old-0", "original")
            writeText(root / "project.yaml", "replacement")
            writeText(dir / "journal.yaml", "entries:\n  - path: project.yaml\n    hadOriginal: true\n    newDigest: '${fingerprint(root / "project.yaml")}'\n")
        }
        interrupted()
        writeText(root / "project.yaml", "later edit")
        assertFailsWith<InstallError> { Transaction(root).recover() }
        assertEquals("later edit", readText(root / "project.yaml"))
        assertEquals("original", readText(dir / "old-0"))
        writeText(root / "project.yaml", "replacement")
        Transaction(root).recover()
        assertEquals("original", readText(root / "project.yaml"))
        interrupted()
        writeText(dir / "committed", "committed\n")
        Transaction(root).recover()
        assertEquals("replacement", readText(root / "project.yaml"))
        assertFalse(fs.exists(dir))
    }
    @Test fun activationPreservesExistingSettingsAndRefusesDisabledConfiguration() {
        val enabled = "product: jvm/app\nplugins:\n  sample:\n    enabled: true\n    custom: keep\n"
        assertEquals(enabled, enablePlugin(enabled, "sample"))
        assertFailsWith<InstallError> { enablePlugin(enabled.replace("true", "false"), "sample") }
    }
    @Test fun resolvesTypedSlashRefsAndPeelsAnnotatedTagsFromTheSameRepository() = project { _, cache ->
        val urls = mutableListOf<String>()
        val remote = GitHub(cache, request = { url, _ ->
            urls += url
            when (url.substringAfter("/git/")) {
                "ref/heads/release%2Fnext" -> "{\"object\":{\"type\":\"commit\",\"sha\":\"$first\"}}"
                "ref/tags/release%2Fnext" -> "{\"object\":{\"type\":\"tag\",\"sha\":\"$second\"}}"
                "tags/$second" -> "{\"object\":{\"type\":\"commit\",\"sha\":\"$first\"}}"
                "commits/$first" -> "{\"sha\":\"$first\"}"
                else -> error("Unexpected request: $url")
            }.encodeToByteArray()
        })
        assertEquals(first, remote.resolve("owner/repo", Ref("branch", "release/next")))
        assertEquals(first, remote.resolve("owner/repo", Ref("tag", "release/next")))
        assertEquals(first, remote.resolve("owner/repo", Ref("commit", first)))
        assertEquals(4, urls.size)
        assertTrue(urls.all { it.startsWith("https://api.github.com/repos/owner/repo/git/") })
    }
    @Test fun rejectsCatalogReferencesInTaggedActionsAndMappingKeys() {
        for (module in listOf("product: jvm/amper-plugin\ndependencies:\n  - \$libs.core: exported\n", "product: jvm/amper-plugin\n")) {
            val action = "tasks:\n  generate:\n    action: !sample.generate\n      classpath: [\$libs.core]\n"
            val payload = zip(mapOf("plugin/module.yaml" to module.encodeToByteArray(), "plugin/plugin.yaml" to action.encodeToByteArray(), "LICENSE" to "License".encodeToByteArray()))
            assertFailsWith<InstallError> { prepare(decl, first, payload) }
        }
    }
    @Test fun multiPluginProducerRequiresSelectionAndDoesNotConfuseAliasWithIdentity() {
        val producer = "schemaVersion: 1\nplugins:\n  one:\n    module: plugin\n  two:\n    module: plugin\n"
        val payload = zip(mapOf("ktc-plugin.yaml" to producer.encodeToByteArray(), "plugin/module.yaml" to "product: jvm/amper-plugin\npluginInfo: {id: sample}\n".encodeToByteArray(), "plugin/plugin.yaml" to "tasks: {}\n".encodeToByteArray()))
        val d = Declaration("owner/repo", Ref("commit", first))
        assertFailsWith<InstallError> { prepare(d, first, payload) }
        assertEquals("sample", prepare(d.copy(plugin = "two"), first, payload).lock.pluginId)
        assertFailsWith<InstallError> { prepare(d.copy(plugin = "unknown"), first, payload) }
    }
    @Test fun cacheOverridesDoNotBypassTheProjectMutationLock() = project { root, cache ->
        val nested = Installer(root, cache / "other", FakeRemote(first, mapOf(first to archive())), {})
        val remote = object : Remote {
            override fun resolve(repository: String, ref: Ref): String {
                val error = assertFailsWith<InstallError> { nested.add(decl, "nested", null, false) }
                assertTrue(error.message!!.contains("Another installer"))
                return first
            }
            override fun archive(repository: String, commit: String): ByteArray = this@InstallerTest.archive()
        }
        Installer(root, cache, remote, {}).add(decl, null, null, false)
    }
    @Test fun trackedDownloadedPayloadIsReportedAndNeverSilentlyUntracked() = project { root, cache ->
        assertEquals(0, Platform.run(listOf("git", "init", "-q", root.toString())).code)
        val installer = Installer(root, cache, FakeRemote(first, mapOf(first to archive())), {})
        installer.add(decl.copy(mode = "downloaded"), null, null, false)
        assertEquals(0, Platform.run(listOf("git", "-C", root.toString(), "add", "-f", "plugins/sample/module.yaml")).code)
        assertFailsWith<InstallError> { installer.status(verify = true) }
        assertFailsWith<InstallError> { installer.sync() }
        assertFailsWith<InstallError> { installer.update("sample", false, null, false) }
        assertTrue(Platform.run(listOf("git", "-C", root.toString(), "ls-files")).stdout.contains("plugins/sample/module.yaml"))
    }
    @Test fun filesystemLinksCannotEscapeVerificationOrDeletion() = project { root, _ ->
        val outside = root.parent!! / "outside"
        fs.createDirectories(outside)
        writeText(outside / "keep", "untouched")
        val link = root / "escape"
        if (Platform.windows) {
            fun ps(value: String) = "'" + value.replace("'", "''") + "'"
            val result = Platform.run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "New-Item -ItemType Junction -Path ${ps(link.toString())} -Target ${ps(outside.toString())} | Out-Null"))
            assertEquals(0, result.code, result.stderr)
        } else fs.createSymlink(link, outside)
        try {
            assertFailsWith<InstallError> { contained(root, "escape/keep") }
            assertFailsWith<InstallError> { fingerprint(root) }
            assertFailsWith<InstallError> { deleteTree(link) }
            assertEquals("untouched", readText(outside / "keep"))
        } finally { fs.delete(link) }
    }
    @Test fun dryRunRejectsAnArchiveCacheInsideTheProject() = project { root, _ ->
        val before = fingerprint(root)
        val remote = FakeRemote(first, mapOf(first to archive()))
        assertFailsWith<InstallError> { Installer(root, root / "cache", remote, {}).add(decl, null, null, true) }
        assertEquals(0, remote.resolutions)
        assertEquals(before, fingerprint(root))
    }
    @Test fun windowsHostPathsAndReadOnlyCleanup() = project { root, cache ->
        if (!Platform.windows) return@project
        val forward = cache.toString().replace('\\', '/')
        assertTrue(systemPath(forward).isAbsolute)
        assertEquals(absoluteLocation(cache), absoluteLocation(systemPath(forward)))
        assertEquals(absoluteLocation(cache), absoluteLocation(forward.toPath()))
        Installer(root, systemPath(forward), FakeRemote(first, mapOf(first to archive())), {}).add(decl, null, null, false)
        val file = root / "readonly"
        writeText(file, "immutable Git object")
        assertEquals(0, Platform.run(listOf("attrib.exe", "+R", file.toString())).code)
        deleteTree(file)
        assertFalse(fs.exists(file))
    }
}

/** ZIP fixtures with real central directory/CRC/mode metadata, independent of production decoding. */
fun zip(files: Map<String, ByteArray>, symlink: Boolean = false): ByteArray {
    val body = Buffer(); val central = Buffer()
    for ((path, bytes) in files) {
        val name = "repo-main/$path".encodeToByteArray(); val offset = body.size.toInt(); val crc = crc32(bytes).toInt()
        body.writeIntLe(0x04034b50).writeShortLe(20).writeShortLe(0).writeShortLe(0).writeIntLe(0).writeIntLe(crc).writeIntLe(bytes.size).writeIntLe(bytes.size).writeShortLe(name.size).writeShortLe(0).write(name).write(bytes)
        central.writeIntLe(0x02014b50).writeShortLe(3 shl 8 or 20).writeShortLe(20).writeShortLe(0).writeShortLe(0).writeIntLe(0).writeIntLe(crc).writeIntLe(bytes.size).writeIntLe(bytes.size).writeShortLe(name.size).writeShortLe(0).writeShortLe(0).writeShortLe(0).writeShortLe(0).writeIntLe((if (symlink) 0xa1ff else 0x81a4) shl 16).writeIntLe(offset).write(name)
    }
    val start = body.size.toInt(); val length = central.size.toInt()
    body.writeAll(central)
    body.writeIntLe(0x06054b50).writeShortLe(0).writeShortLe(0).writeShortLe(files.size).writeShortLe(files.size).writeIntLe(length).writeIntLe(start).writeShortLe(0)
    return body.readByteArray()
}
