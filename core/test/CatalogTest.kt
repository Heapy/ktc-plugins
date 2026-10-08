package io.heapy.ktcplugins

import kotlin.test.*
import okio.Path

class CatalogTest {
    private val first = "a".repeat(40)
    private val second = "b".repeat(40)
    private val declaration = Declaration("owner/repo", Ref("branch", "main"))
    private fun repository(version: String = "2.3.2", exports: String = "[sql-runtime, sql-driver]") = mapOf(
        "ktc-plugin.yaml" to """
            schemaVersion: 1
            plugins:
              sql:
                module: plugin
                licenseFiles: [LICENSE]
                catalog:
                  file: gradle/libs.versions.toml
                  export: $exports
        """.trimIndent(),
        "gradle/libs.versions.toml" to """
            [versions]
            sql = "$version"
            [libraries]
            sql-compiler = { module = "app.cash.sqldelight:compiler", version.ref = "sql" }
            sql-runtime = { group = "app.cash.sqldelight", name = "runtime", version.ref = "sql" }
            sql-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sql" }
            unrelated = { module = "example:unused", version = { strictly = "1.0" } }
        """.trimIndent(),
        "plugin/module.yaml" to """
            product: jvm/amper-plugin
            pluginInfo: {id: sql}
            dependencies:
              - ${'$'}libs.sql.compiler: exported # keep scope and comment
            test-dependencies@jvm: ['${'$'}libs.sql.runtime']
            apply: [./local.module-template.yaml]
        """.trimIndent(),
        "plugin/local.module-template.yaml" to "dependencies: [\"\$libs.sql.driver\"]\n",
        "plugin/plugin.yaml" to "tasks:\n  generate:\n    action: !sample.generate\n      classpath: [\$libs.sql.runtime]\n",
        "plugin/src/main.kt" to "// preserve source \$libs.sql.compiler\n",
        "LICENSE" to "License",
    ).mapValues { Payload(it.value.encodeToByteArray()) }
    private class RemoteFixture(var head: String, val archives: Map<String, ByteArray>) : Remote {
        override fun resolve(repository: String, ref: Ref) = head
        override fun archive(repository: String, commit: String) = archives.getValue(commit)
    }
    private fun project(test: (Path, Path) -> Unit) {
        val temp = tempDirectory()
        try { val root = temp / "project"; fs.createDirectories(root); test(root, temp / "cache") }
        finally { deleteTree(temp) }
    }
    private fun remote() = RemoteFixture(first, mapOf(
        first to zip(repository().mapValues { it.value.bytes }),
        second to zip(repository("2.4.0", "[sql-runtime]").mapValues { it.value.bytes }),
    ))
    @Test fun resolvesProducerCatalogAcrossYamlAndLocksOnlyExplicitExports() {
        val p = prepareRepository(declaration, first, repository())
        val module = p.payload.getValue("module.yaml").bytes.decodeToString()
        assertTrue("'app.cash.sqldelight:compiler:2.3.2': exported # keep scope and comment" in module)
        assertTrue("'app.cash.sqldelight:runtime:2.3.2'" in module)
        assertTrue("'app.cash.sqldelight:sqlite-driver:2.3.2'" in p.payload.getValue("local.module-template.yaml").bytes.decodeToString())
        assertTrue("\$libs.sql.compiler" in p.payload.getValue("src/main.kt").bytes.decodeToString())
        assertEquals(setOf("ktc-sql-sql-runtime", "ktc-sql-sql-driver"), p.lock.catalog!!.libraries.keys)
        assertEquals(p.lock, locks(lockYaml(mapOf("sql" to p.lock))).getValue("sql"))
    }
    @Test fun installUpdateRestoreAndRemovePreserveUserCatalogAndModule() = project { root, cache ->
        val original = "# user catalog\n[versions]\nuser = '1.0'\n[libraries]\nuser-lib = { module = 'example:user', version.ref = 'user' } # retain\n"
        writeText(root / "gradle/libs.versions.toml", original)
        writeText(root / "module.yaml", "product: jvm/app\n")
        val remote = remote()
        val installer = Installer(root, cache, remote, {})
        val before = fingerprint(root)
        installer.add(declaration, "client-alias", null, true)
        assertEquals(before, fingerprint(root))
        installer.add(declaration, "client-alias", null, false)
        val catalog = root / "gradle/libs.versions.toml"
        assertTrue("user-lib = { module = 'example:user', version.ref = 'user' } # retain\n" in readText(catalog))
        assertEquals("product: jvm/app\n", readText(root / "module.yaml"))
        assertFalse(fs.exists(root / "libs.versions.toml"))
        remote.head = second
        deleteTree(root / "plugins/sql")
        installer.sync()
        assertTrue("2.3.2" in readText(root / "plugins/sql/module.yaml"))
        val installed = fingerprint(root)
        installer.update("client-alias", false, null, true)
        assertEquals(installed, fingerprint(root))
        installer.update("client-alias", false, null, false)
        assertTrue("2.4.0" in readText(catalog))
        assertFalse("ktc-sql-sql-driver" in readText(catalog))
        installer.status(verify = true)
        val updated = fingerprint(root)
        installer.update("client-alias", false, null, false)
        assertEquals(updated, fingerprint(root))
        installer.remove("client-alias", dryRun = true)
        assertEquals(updated, fingerprint(root))
        installer.remove("client-alias")
        assertEquals(original, readText(catalog))
    }
    @Test fun collisionAndNormalizedAliasCollisionNeverAdoptUserEntries() = project { root, cache ->
        for (alias in listOf("ktc-sql-sql-runtime", "ktc_sql_sql_runtime", "ktc.sql.sql.runtime")) {
            writeText(root / "libs.versions.toml", "[libraries]\n'$alias' = 'example:mine:1.0'\n")
            val before = fingerprint(root)
            assertFailsWith<InstallError> { Installer(root, cache, remote(), {}).add(declaration, null, null, false) }
            assertEquals(before, fingerprint(root))
        }
    }
    @Test fun editsAndMissingExportsBlockUpdateSyncVerifyAndRemove() = project { root, cache ->
        val remote = remote()
        val installer = Installer(root, cache, remote, {})
        installer.add(declaration, null, null, false)
        val original = readText(root / "libs.versions.toml")
        remote.head = second
        for (changed in listOf(original.replace("2.3.2", "9.0"), "[libraries]\n", original.replace("version.ref = \"ktc-sql-sql\"", "version.ref = \"ktc-sql-sql\", extra = \"mine\""))) {
            writeText(root / "libs.versions.toml", changed)
            val before = fingerprint(root)
            assertFailsWith<InstallError> { installer.update("sql", false, null, false) }
            assertFailsWith<InstallError> { installer.sync() }
            assertFailsWith<InstallError> { installer.status(verify = true) }
            assertFailsWith<InstallError> { installer.remove("sql") }
            assertEquals(before, fingerprint(root))
        }
    }
    @Test fun transactionRollsBackCatalogSourcesAndLockTogether() = project { root, cache ->
        val remote = remote()
        Installer(root, cache, remote, {}).add(declaration, null, null, false)
        remote.head = second
        val before = fingerprint(root)
        assertFailsWith<InstallError> { Installer(root, cache, remote, {}, failAfter = 3).update("sql", false, null, false) }
        assertEquals(before, fingerprint(root))
    }
    @Test fun removingCatalogDeclarationRemovesOldExports() = project { root, cache ->
        val old = repository()
        val next = old.toMutableMap().apply {
            put("ktc-plugin.yaml", Payload("schemaVersion: 1\nplugins:\n  sql:\n    module: plugin\n    licenseFiles: [LICENSE]\n".encodeToByteArray()))
            for ((path, value) in toMap()) if (path.startsWith("plugin/") && path.endsWith(".yaml")) {
                put(path, Payload(value.bytes.decodeToString().replace(Regex("\\\$libs\\.[a-z.]+"), "example:lib:1.0").encodeToByteArray()))
            }
        }
        val remote = RemoteFixture(first, mapOf(first to zip(old.mapValues { it.value.bytes }), second to zip(next.mapValues { it.value.bytes })))
        val installer = Installer(root, cache, remote, {})
        installer.add(declaration, null, null, false)
        remote.head = second
        installer.update("sql", false, null, false)
        assertFalse("ktc-sql-" in readText(root / "libs.versions.toml"))
        installer.status(verify = true)
    }
    @Test fun malformedMissingAmbiguousAndUnpinnedProducerCatalogsFail() {
        val bad = listOf(
            "sql-compiler = 'g:a:1.+'" to "Catalog library must have fixed group:artifact:version coordinates: g:a:1.+",
            "sql-compiler = { module = 'g:a', version.ref = 'absent' }" to "Unknown catalog version: absent",
            "sql-compiler = { module = 'g:a', version = { strictly = '1' } }" to "Rich catalog versions are unsupported: sql-compiler",
            "sql-compiler = 'g:a:1'\nsql_compiler = 'g:a:2'" to "Catalog aliases have colliding accessors",
            "sql-compiler = 'g:a:1'\nsql-compiler = 'g:a:2'" to "Duplicate catalog key: sql-compiler",
            "sql-compiler = { module = 'g:a' }" to "Missing fixed catalog version: sql-compiler",
            "invalid TOML" to "Invalid TOML catalog:",
        )
        fun withCompiler(entry: String): Map<String, Payload> {
            // Keep every other referenced/exported alias valid so it cannot mask the intended failure.
            val text = "[libraries]\nsql-runtime = 'g:runtime:1'\nsql-driver = 'g:driver:1'\n$entry\n"
            return repository() + ("gradle/libs.versions.toml" to Payload(text.encodeToByteArray()))
        }
        prepareRepository(declaration, first, withCompiler("sql-compiler = 'g:a:1'"))
        for ((entry, expectedReason) in bad) {
            val error = assertFailsWith<InstallError>(entry) { prepareRepository(declaration, first, withCompiler(entry)) }
            assertTrue(error.message.orEmpty().startsWith(expectedReason), "Expected '$expectedReason' for $entry, got '${error.message}'")
        }
        assertFailsWith<InstallError> { prepareRepository(declaration, first, repository() - "gradle/libs.versions.toml") }
        assertFailsWith<InstallError> { prepareRepository(declaration, first, repository(exports = "[missing]")) }
        assertFailsWith<InstallError> { prepareRepository(declaration, first, repository(exports = "[sql-runtime, sql-runtime]")) }
        val symlink = repository().getValue("gradle/libs.versions.toml").copy(symbolicLink = true)
        assertFailsWith<InstallError> { prepareRepository(declaration, first, repository() + ("gradle/libs.versions.toml" to symlink)) }
    }
    @Test fun producerValidationIncludesExternalCatalogAndHonorsGitIgnores() = project { root, _ ->
        for ((file, value) in repository()) writeBytes(root / file, value.bytes)
        val before = fingerprint(root)
        validateProducer(root, report = {})
        assertEquals(before, fingerprint(root))
        assertEquals(0, Platform.run(listOf("git", "init", "-q", root.toString())).code)
        writeText(root / ".gitignore", "/gradle/\n")
        assertFailsWith<InstallError> { validateProducer(root, report = {}) }
    }
    @Test fun rejectsAmbiguousConsumerCatalogs() = project { root, cache ->
        for (file in catalogLocations) writeText(root / file, "[libraries]\n")
        val before = fingerprint(root)
        assertFailsWith<InstallError> { Installer(root, cache, remote(), {}).add(declaration, null, null, false) }
        assertEquals(before, fingerprint(root))
    }
    @Test fun multiplePluginsShareCatalogAndUpdateAllWithoutOwningUserEntries() = project { root, cache ->
        fun combined(version: String) = repository(version).toMutableMap().apply {
            put("ktc-plugin.yaml", Payload((getValue("ktc-plugin.yaml").bytes.decodeToString() + "\n  other:\n    module: other\n    licenseFiles: [LICENSE]\n    catalog: {file: gradle/libs.versions.toml, export: [sql-runtime]}\n").encodeToByteArray()))
            put("other/module.yaml", Payload("product: jvm/amper-plugin\npluginInfo: {id: other}\n".encodeToByteArray()))
            put("other/plugin.yaml", Payload("tasks: {}\n".encodeToByteArray()))
        }
        val remote = RemoteFixture(first, mapOf(first to zip(combined("2.3.2").mapValues { it.value.bytes }), second to zip(combined("2.4.0").mapValues { it.value.bytes })))
        writeText(root / "libs.versions.toml", "# mine\r\n[libraries] # keep\r\nuser = \"example:user:1.0\"\r\n[versions]\r\nv = \"1.0\"\r\n")
        val installer = Installer(root, cache, remote, {})
        installer.add(declaration.copy(plugin = "sql"), null, null, false)
        installer.add(declaration.copy(plugin = "other"), null, null, false)
        val before = fingerprint(root)
        installer.update("sql", false, null, false)
        assertEquals(before, fingerprint(root))
        remote.head = second
        installer.update(null, true, null, false)
        val catalog = readText(root / "libs.versions.toml")
        assertTrue("# mine\r\n[libraries] # keep\r\n" in catalog && "user = \"example:user:1.0\"\r\n" in catalog)
        assertTrue("ktc-sql-sql-runtime" in catalog && "ktc-other-sql-runtime" in catalog)
        assertFalse("2.3.2" in catalog)
        installer.remove("sql")
        installer.status(verify = true)
        assertTrue("ktc-other-sql-runtime" in readText(root / "libs.versions.toml"))
    }
    @Test fun catalogsWithEmptyExportsResolveInternalDependenciesOnly() = project { root, cache ->
        val remote = RemoteFixture(first, mapOf(first to zip(repository(exports = "[]").mapValues { it.value.bytes })))
        val installer = Installer(root, cache, remote, {})
        installer.add(declaration, null, null, false)
        assertTrue("app.cash.sqldelight:compiler:2.3.2" in readText(root / "plugins/sql/module.yaml"))
        assertFalse(fs.exists(root / "libs.versions.toml"))
        deleteTree(root / "plugins/sql")
        installer.sync()
        installer.status(verify = true)
    }
    @Test fun exportedAliasesNormalizeSeparatorsAndConsumerFormatUsesDoubleQuotes() {
        val repo = repository().toMutableMap()
        for ((path, contents) in repo.toMap()) if (path.endsWith(".toml") || path == "ktc-plugin.yaml") {
            repo[path] = contents.copy(bytes = contents.bytes.decodeToString().replace("sql-runtime", "sql_runtime").encodeToByteArray())
        }
        val prepared = prepareRepository(declaration, first, repo)
        val block = catalogBlock(prepared.lock)
        assertTrue("ktc-sql-sql-runtime = { module = \"app.cash.sqldelight:runtime\", version.ref = \"ktc-sql-sql\" }" in block)
        assertFalse("ktc-sql-sql_runtime" in block)
        for (path in listOf("libs.versions.toml", "gradle/libs.versions.toml", "gradle", "libs.versions.toml/plugin")) {
            assertFailsWith<InstallError> { validateDestination(path) }
        }
    }

    @Test fun exportsShareProducerVersionAliasesButKeepIndependentVersionsSeparate() {
        val repo = repository().toMutableMap()
        val path = "gradle/libs.versions.toml"
        repo[path] = Payload(repo.getValue(path).bytes.decodeToString()
            .replace("sql = \"2.3.2\"", "sql = \"2.3.2\"\nother = \"2.3.2\"")
            .replace("version.ref = \"sql\" }\nunrelated", "version.ref = \"other\" }\nunrelated")
            .encodeToByteArray())
        val shared = prepareRepository(declaration, first, repository()).lock
        val text = editCatalog(null, emptyList(), listOf(shared))
        assertEquals(1, Regex("2\\.3\\.2").findAll(text).count())
        assertEquals(2, Regex("version.ref = \"ktc-sql-sql\"").findAll(text).count())
        assertEquals("app.cash.sqldelight:runtime:2.3.2", ProducerCatalog(text).resolve("ktc-sql-sql-runtime"))
        assertEquals("app.cash.sqldelight:sqlite-driver:2.3.2", ProducerCatalog(text).resolve("ktc-sql-sql-driver"))
        for (otherVersion in listOf("2.3.2", "2.4.0")) {
            val independentRepo = repo + (path to Payload(repo.getValue(path).bytes.decodeToString()
                .replace("other = \"2.3.2\"", "other = \"$otherVersion\"").encodeToByteArray()))
            val independent = prepareRepository(declaration, first, independentRepo).lock
            assertEquals(mapOf("ktc-sql-sql" to "2.3.2", "ktc-sql-other" to otherVersion), independent.catalog!!.versions)
            assertEquals(independent, locks(lockYaml(mapOf("sql" to independent))).getValue("sql"))
            val consumer = ProducerCatalog(editCatalog(null, emptyList(), listOf(independent)))
            assertEquals("app.cash.sqldelight:runtime:2.3.2", consumer.resolve("ktc-sql-sql-runtime"))
            assertEquals("app.cash.sqldelight:sqlite-driver:$otherVersion", consumer.resolve("ktc-sql-sql-driver"))
        }
        // Inline coordinates are not coupled to an unrelated alias with the same value.
        repo[path] = Payload(repo.getValue(path).bytes.decodeToString()
            .replace("{ module = \"app.cash.sqldelight:sqlite-driver\", version.ref = \"other\" }", "\"app.cash.sqldelight:sqlite-driver:2.3.2\"")
            .encodeToByteArray())
        val inline = prepareRepository(declaration, first, repo).lock
        assertFalse("ktc-sql-sql-driver" in inline.catalog!!.versionRefs)
        verifyCatalog(editCatalog(null, emptyList(), listOf(inline)), inline)
    }

    @Test fun versionAliasCollisionsNeverAdoptUserEntries() = project { root, cache ->
        for (alias in listOf("ktc-sql-sql", "ktc_sql_sql", "ktc.sql.sql")) {
            writeText(root / "libs.versions.toml", "[versions]\n'$alias' = '2.3.2'\n[libraries]\n")
            val before = fingerprint(root)
            assertFailsWith<InstallError> { Installer(root, cache, remote(), {}).add(declaration, null, null, false) }
            assertEquals(before, fingerprint(root))
        }
    }

    @Test fun versionBlocksMustRemainInTheVersionsTable() {
        val locked = prepareRepository(declaration, first, repository()).lock
        val text = "[libraries]\n" + catalogBlock(locked, "versions") + catalogBlock(locked)
        assertFailsWith<InstallError> { verifyCatalog(text, locked) }
        val valid = editCatalog(null, emptyList(), listOf(locked))
        assertFailsWith<InstallError> { verifyCatalog(valid.replace(catalogBlock(locked, "versions"), ""), locked) }
    }

    @Test fun oldLiteralLocksVerifySyncAndMigrateOnExplicitUpdate() = project { root, cache ->
        val installer = Installer(root, cache, remote(), {})
        installer.add(declaration, null, null, false)
        val current = locks(readText(root / "ktc-plugins.lock.yaml")).getValue("sql")
        val legacy = current.copy(catalog = current.catalog!!.copy(versionRefs = emptyMap()))
        writeText(root / "ktc-plugins.lock.yaml", lockYaml(mapOf("sql" to legacy)))
        writeText(root / "libs.versions.toml", editCatalog(null, emptyList(), listOf(legacy)))
        installer.status(verify = true)
        deleteTree(root / "plugins/sql")
        installer.sync()
        installer.status(verify = true)
        assertEquals(legacy, locks(readText(root / "ktc-plugins.lock.yaml")).getValue("sql"))
        assertFalse("version.ref" in readText(root / "libs.versions.toml"))
        installer.update("sql", false, null, false)
        installer.status(verify = true)
        assertEquals(current, locks(readText(root / "ktc-plugins.lock.yaml")).getValue("sql"))
        assertTrue("version.ref" in readText(root / "libs.versions.toml"))
        installer.remove("sql")
        assertFalse("ktc-sql" in readText(root / "libs.versions.toml"))
    }

}
