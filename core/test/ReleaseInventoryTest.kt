package io.heapy.ktcplugins

import kotlin.test.*

class ReleaseInventoryTest {
    private val version = "0.2.1"
    private val targets = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")
    private val extra = "ktc-plugins-$version-windows-arm64.exe"
    private val binaryDigests = (targets + "windows-arm64").associate { target ->
        "ktc-plugins-$version-$target" + (if (target.startsWith("windows-")) ".exe" else "") to sha256("binary $target")
    }
    private val unix = "#!/bin/sh\nversion='$version' # VERSION\n" + targets.filter { it != "windows-x64" }.joinToString("\n", postfix = "\n") {
        "sha='${binaryDigests.getValue("ktc-plugins-$version-$it")}' ;; # SHA_${it.uppercase().replace('-', '_')}"
    }
    private val windows = "@echo off\r\nset \"ktc_version=$version\"\r\n\$sha = '${binaryDigests.getValue("ktc-plugins-$version-windows-x64.exe")}' # SHA_WINDOWS_X64\r\n"
    private val files = mapOf("ktc-plugins" to unix.encodeToByteArray(), "ktc-plugins.bat" to windows.encodeToByteArray())
    private val digests = binaryDigests + files.mapValues { Payload(it.value).record.sha256 }

    private fun fetch(
        sums: Map<String, String> = digests,
        assetDigests: List<Pair<String, String>> = digests.toList(),
        extraState: String = "uploaded",
        rawSums: String = sums.entries.joinToString("\n", postfix = "\n") { "${it.value}  ${it.key}" },
        requested: MutableList<String> = mutableListOf(),
    ): LauncherRelease {
        val checksumBytes = rawSums.encodeToByteArray()
        val assets = assetDigests + ("SHA256SUMS" to Payload(checksumBytes).record.sha256)
        val metadata = "tag_name: v$version\ndraft: false\nprerelease: false\nassets:\n" + assets.mapIndexed { index, (name, hash) ->
            "  - name: $name\n    state: ${if (name == extra) extraState else "uploaded"}\n    url: https://api.github.com/repos/Heapy/ktc-plugins/releases/assets/$index\n    digest: sha256:$hash\n    size: ${files[name]?.size ?: if (name == "SHA256SUMS") checksumBytes.size else 100}\n"
        }.joinToString("")
        val cache = tempDirectory()
        try {
            val github = GitHub(cache, request = { url, _ ->
                if (url.endsWith("/tags/v$version")) metadata.encodeToByteArray()
                else {
                    val name = assets[url.substringAfterLast('/').toInt()].first
                    requested += name
                    if (name == "SHA256SUMS") checksumBytes else files[name] ?: error("Unexpected native download: $name")
                }
            })
            return GitHubReleaseSource(github).release(version)
        } finally { deleteTree(cache) }
    }
    @Test fun acceptsVerifiedExtraPlatformWithoutDownloadingNativeBinaries() {
        val requested = mutableListOf<String>()
        val release = fetch(requested = requested)
        assertEquals(digests, release.digests)
        assertEquals(setOf("ktc-plugins", "ktc-plugins.bat"), release.files.keys)
        assertEquals(listOf("SHA256SUMS", "ktc-plugins", "ktc-plugins.bat"), requested)
    }
    @Test fun stillRequiresEveryKnownBinaryAndBothWrappers() {
        for (name in digests.keys - extra) {
            assertFailsWith<InstallError>(name) { fetch(sums = digests - name) }
        }
    }
    @Test fun rejectsUnverifiedExtraReleaseEntries() {
        assertFailsWith<InstallError> { fetch(assetDigests = (digests + (extra to "0".repeat(64))).toList()) }
        assertFailsWith<InstallError> { fetch(assetDigests = (digests - extra).toList()) }
        assertFailsWith<InstallError> { fetch(assetDigests = digests.toList() + (extra to digests.getValue(extra))) }
        assertFailsWith<InstallError> { fetch(extraState = "new") }
        assertFailsWith<InstallError> { fetch(assetDigests = (digests + (extra to "invalid")).toList()) }
    }
    @Test fun rejectsDuplicateAndUnsafeAdditionalChecksumEntries() {
        val valid = digests.entries.joinToString("\n", postfix = "\n") { "${it.value}  ${it.key}" }
        for (line in listOf("${digests.getValue(extra)}  $extra", "${digests.getValue(extra)}  ../outside", "invalid  unexpected")) {
            assertFailsWith<InstallError> { fetch(rawSums = valid + line + "\n") }
        }
    }
}
