package io.heapy.ktcplugins

import kotlin.test.*
import okio.Path

class TemplateValidationTest {
    private val commit = "a".repeat(40)
    private val declaration = Declaration("owner/repo", Ref("commit", commit))
    private fun repository(templates: Map<String, String>, apply: String = "[./base.module-template.yaml]") = buildMap {
        put("ktc-plugin.yaml", "schemaVersion: 1\nplugins:\n  sample:\n    module: plugin\n    licenseFiles: [LICENSE]\n")
        put("plugin/module.yaml", "product: jvm/amper-plugin\npluginInfo: {id: sample}\napply: $apply\n")
        put("plugin/plugin.yaml", "tasks: {}\n")
        put("LICENSE", "license")
        templates.forEach { (name, contents) -> put("plugin/$name", contents) }
    }.mapValues { it.value.encodeToByteArray() }
    private fun temporary(test: (Path) -> Unit) {
        val root = tempDirectory()
        try { test(root) } finally { deleteTree(root) }
    }
    @Test fun rejectsExternalAndHelperDependenciesInAppliedTemplates() {
        for (key in listOf("dependencies", "test-dependencies", "dependencies@jvm", "test-dependencies@linuxX64")) {
            for (reference in listOf("//helper", "../helper", "./helper")) {
                for (entry in listOf("'$reference'", "{'$reference': compile-only}")) {
                    val files = repository(mapOf(
                        "base.module-template.yaml" to "$key: [$entry]\n",
                        "helper/module.yaml" to "product: jvm/lib\n",
                    ))
                    val error = assertFailsWith<InstallError>("$key: $entry") { prepare(declaration, commit, zip(files)) }
                    assertTrue("base.module-template.yaml" in error.message.orEmpty(), error.message)
                    assertTrue(reference in error.message.orEmpty(), error.message)
                }
            }
        }
    }
    @Test fun rejectsExternalMissingAndUnsafeNestedTemplates() {
        for (reference in listOf("//outside.module-template.yaml", "../../outside.module-template.yaml", "./missing.module-template.yaml", "C:/outside.module-template.yaml", "bad\\path.module-template.yaml")) {
            val files = repository(mapOf("nested/base.module-template.yaml" to "apply: ['$reference']\n"), "[./nested/base.module-template.yaml]")
            val error = assertFailsWith<InstallError>(reference) { prepare(declaration, commit, zip(files)) }
            assertTrue("nested/base.module-template.yaml" in error.message.orEmpty(), error.message)
        }
    }
    @Test fun validatesTemplatesRelativeToTheirOwnDirectoryAndAllowsSharedIncludes() {
        val files = repository(mapOf(
            "nested/base.module-template.yaml" to "apply: [../shared.module-template.yaml, ./leaf.module-template.yaml]\n",
            "nested/leaf.module-template.yaml" to "apply: [../shared.module-template.yaml]\ntest-dependencies@jvm: ['example:test:1.0']\n",
            "shared.module-template.yaml" to "dependencies: [{'example:library:1.0': exported}]\n",
        ), "[./nested/base.module-template.yaml, ./shared.module-template.yaml]")
        val prepared = prepare(declaration, commit, zip(files))
        assertEquals(files.getValue("plugin/nested/base.module-template.yaml").decodeToString(), prepared.payload.getValue("nested/base.module-template.yaml").bytes.decodeToString())
        assertEquals("sample", prepared.lock.pluginId)
    }
    @Test fun rejectsTemplateCyclesAndExcessiveDepth() {
        val cycle = repository(mapOf(
            "base.module-template.yaml" to "apply: [./nested/other.module-template.yaml]\n",
            "nested/other.module-template.yaml" to "apply: [../base.module-template.yaml]\n",
        ))
        assertTrue("Cyclic" in assertFailsWith<InstallError> { prepare(declaration, commit, zip(cycle)) }.message.orEmpty())
        val chain = (0..101).associate { index ->
            "$index.module-template.yaml" to if (index == 101) "settings: {}\n" else "apply: [./${index + 1}.module-template.yaml]\n"
        }
        val deep = repository(chain, "[./0.module-template.yaml]")
        assertTrue("depth" in assertFailsWith<InstallError> { prepare(declaration, commit, zip(deep)) }.message.orEmpty())
    }
    @Test fun producerValidationChecksNestedTemplatesWithoutChangingFiles() = temporary { root ->
        val invalid = repository(mapOf(
            "base.module-template.yaml" to "apply: [./nested/leaf.module-template.yaml]\n",
            "nested/leaf.module-template.yaml" to "dependencies: [//helper]\n",
        ))
        invalid.forEach { (name, bytes) -> writeBytes(root / name, bytes) }
        val error = assertFailsWith<InstallError> { validateProducer(root, report = {}) }
        assertTrue("nested/leaf.module-template.yaml" in error.message.orEmpty(), error.message)
        invalid.forEach { (name, bytes) -> assertContentEquals(bytes, fs.readBytes(root / name)) }
        writeText(root / "plugin/nested/leaf.module-template.yaml", "dependencies: ['example:library:1.0']\n")
        val messages = mutableListOf<String>()
        validateProducer(root, report = messages::add)
        assertTrue(messages.single().startsWith("sample: valid"))
    }
    @Test fun refusedTemplateInstallationDoesNotChangeConsumer() = temporary { root ->
        val consumer = root / "consumer"
        writeText(consumer / "project.yaml", "# user configuration\nmodules: []\n")
        val before = fs.list(consumer)
        val archive = zip(repository(mapOf("base.module-template.yaml" to "dependencies: [//helper]\n")))
        val remote = object : Remote {
            override fun resolve(repository: String, ref: Ref) = commit
            override fun archive(repository: String, commit: String) = archive
        }
        assertFailsWith<InstallError> { Installer(consumer, root / "cache", remote, {}).add(declaration, null, null, false) }
        assertEquals(before, fs.list(consumer))
        assertEquals("# user configuration\nmodules: []\n", readText(consumer / "project.yaml"))
    }
}
