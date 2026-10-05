package io.heapy.ktcplugins

import okio.ByteString.Companion.toByteString

data class LauncherRelease(val files: Map<String, Payload>, val digests: Map<String, String>)
fun interface ReleaseSource { fun release(version: String): LauncherRelease }
private val targets = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")
fun releaseVersion(version: String): String {
    checkInstall(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(version)) { "Choose an exact stable release version, such as 0.2.0" }
    return version
}
fun launcherVersion(name: String, bytes: ByteArray): String {
    val text = bytes.decodeToString(throwOnInvalidSequence = true).replace("\r\n", "\n")
    val regex = if (name == "ktc-plugins") Regex("(?m)^version='([^']+)' # VERSION$") else Regex("(?m)^set \"ktc_version=([^\"]+)\"$")
    return releaseVersion(regex.find(text)?.groupValues?.get(1) ?: fail("Unrecognized launcher: $name"))
}
fun validateLauncherRelease(version: String, release: LauncherRelease) {
    releaseVersion(version)
    checkInstall(release.files.keys == setOf("ktc-plugins", "ktc-plugins.bat")) { "Release must contain both launchers" }
    for ((name, file) in release.files) {
        checkInstall(!file.symbolicLink && file.bytes.size <= 1024 * 1024) { "Unsafe launcher asset: $name" }
        checkInstall(launcherVersion(name, file.bytes) == version) { "Wrong version in release launcher $name" }
        checkInstall(file.bytes.toByteString().sha256().hex() == release.digests[name]) { "Release launcher checksum mismatch: $name" }
        checkInstall(file.executable == (name == "ktc-plugins")) { "Wrong launcher executable mode" }
    }
    for (target in targets) {
        val name = "ktc-plugins-$version-$target" + if (target == "windows-x64") ".exe" else ""
        val digest = release.digests[name] ?: fail("Missing binary digest for $target")
        checkInstall(Regex("[a-f0-9]{64}").matches(digest)) { "Invalid release binary digest" }
        val marker = "SHA_" + target.uppercase().replace('-', '_')
        val windows = target == "windows-x64"
        val pattern = if (windows) "(?m)^\\${'$'}sha = '([a-f0-9]{64})' # $marker$" else "(?m)sha='([a-f0-9]{64})' ;; # $marker$"
        val text = release.files.getValue(if (windows) "ktc-plugins.bat" else "ktc-plugins").bytes.decodeToString().replace("\r\n", "\n")
        val pins = Regex(pattern).findAll(text).toList()
        checkInstall(pins.size == 1 && pins.single().groupValues[1] == digest) { "Release launcher binary pin mismatch: $target" }
    }
}
class GitHubReleaseSource(private val github: GitHub) : ReleaseSource {
    override fun release(version: String): LauncherRelease {
        releaseVersion(version)
        val metadata = parseYaml(github.get("https://api.github.com/repos/Heapy/ktc-plugins/releases/tags/v$version").decodeToString()).map()
        checkInstall(metadata.text("tag_name") == "v$version" && metadata.text("draft") == "false" && metadata.text("prerelease") == "false") { "Requested version is not a published stable release" }
        val assets = metadata.required("assets").list().map { it.map() }
        fun asset(name: String): Map<String, com.charleskorn.kaml.YamlNode> {
            return assets.singleOrNull { it.text("name") == name && it.text("state") == "uploaded" } ?: fail("Missing/duplicate release asset: $name")
        }
        fun digest(name: String) = asset(name).text("digest").also {
            checkInstall(Regex("sha256:[a-f0-9]{64}").matches(it)) { "GitHub release asset lacks SHA-256: $name" }
        }.removePrefix("sha256:")
        fun download(name: String): ByteArray {
            val a = asset(name)
            val url = a.text("url")
            checkInstall(Regex("https://api\\.github\\.com/repos/Heapy/ktc-plugins/releases/assets/[0-9]+").matches(url)) { "Unexpected release asset URL" }
            checkInstall((a.text("size").toLongOrNull() ?: Long.MAX_VALUE) in 1..1024L * 1024) { "Release text asset exceeds size limit" }
            val bytes = github.get(url, 1024 * 1024, releaseAsset = true)
            checkInstall(bytes.toByteString().sha256().hex() == digest(name)) { "GitHub release checksum mismatch: $name" }
            return bytes
        }
        val sums = linkedMapOf<String, String>()
        for (line in download("SHA256SUMS").decodeToString(throwOnInvalidSequence = true).lineSequence().filter(String::isNotBlank)) {
            val match = Regex("([a-f0-9]{64})  ([A-Za-z0-9_.-]+)").matchEntire(line) ?: fail("Invalid SHA256SUMS entry")
            checkInstall(sums.put(match.groupValues[2], match.groupValues[1]) == null) { "Duplicate SHA256SUMS entry" }
        }
        val names = setOf("ktc-plugins", "ktc-plugins.bat") + targets.map { "ktc-plugins-$version-$it" + if (it == "windows-x64") ".exe" else "" }
        checkInstall(sums.keys == names) { "Unexpected/missing SHA256SUMS inventory" }
        names.forEach { checkInstall(sums[it] == digest(it)) { "Release asset digest differs from SHA256SUMS: $it" } }
        val files = listOf("ktc-plugins", "ktc-plugins.bat").associateWith { Payload(download(it), executable = it == "ktc-plugins") }
        return LauncherRelease(files, sums).also { validateLauncherRelease(version, it) }
    }
}
