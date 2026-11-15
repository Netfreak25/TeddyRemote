package de.teddycloud.teddyremote.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AppRelease(
    val version: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String? = null,
)

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val debuggable: Boolean,
    val signers: Set<String>,
    val minSdk: Int = 29,
)

/** Only the fixed public repository and a single stable release APK are eligible. */
internal object UpdatePolicy {
    const val PACKAGE_NAME = "de.teddycloud.teddyremote"
    const val REPOSITORY = "Netfreak25/TeddyRemote"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val MAX_RELEASE_BYTES = 1024L * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private val versionPattern = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

    private fun versionParts(raw: String): List<Int> = requireNotNull(versionPattern.matchEntire(raw)) {
        "Die Release-Version wird nicht unterstützt."
    }.groupValues.drop(1).map { requireNotNull(it.toIntOrNull()) { "Ungültige Release-Version." } }

    fun selectRelease(payload: String, installedVersion: String): AppRelease? {
        val release = json.decodeFromString<GitHubRelease>(payload)
        require(!release.draft && !release.prerelease) { "Nur reguläre Releases werden unterstützt." }
        val available = versionParts(release.tag_name)
        val installed = versionParts(installedVersion)
        val comparison = available.zip(installed).firstOrNull { (a, b) -> a != b }
        if (comparison == null || comparison.first < comparison.second) return null
        val version = available.joinToString(".")
        val name = "TeddyRemote-$version-release.apk"
        val asset = requireNotNull(release.assets.filter { it.name == name }.singleOrNull()) {
            "Keine eindeutige Release-APK in dieser GitHub-Release gefunden."
        }
        require(asset.state == "uploaded" && asset.size in 1..MAX_APK_BYTES) { "Die Release-APK hat eine ungültige Größe oder ist noch nicht verfügbar." }
        val url = asset.browser_download_url.toHttpUrlOrNull()
        require(url != null && url.scheme == "https" && url.host == "github.com" &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.pathSegments == REPOSITORY.split('/') + listOf("releases", "download", release.tag_name, name)
        ) { "Die APK-Downloadadresse gehört nicht zur erwarteten GitHub-Release." }
        val digest = asset.digest?.let {
            require(it.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "Ungültige SHA-256-Prüfsumme in der Release." }
            it.substringAfter(':').lowercase()
        }
        return AppRelease(version, release.body.orEmpty(), name, asset.browser_download_url, asset.size, digest)
    }

    fun validateApk(installed: ApkIdentity, candidate: ApkIdentity, release: AppRelease, deviceSdk: Int) {
        require(installed.packageName == PACKAGE_NAME && !installed.debuggable) { "Updates sind nur in der Release-App verfügbar." }
        require(candidate.packageName == PACKAGE_NAME && !candidate.debuggable) { "Die Datei ist keine TeddyRemote-Release-APK." }
        require(candidate.versionCode > installed.versionCode) { "Die APK ist nicht neuer als die installierte App." }
        require(candidate.versionName == release.version) { "APK-Version und GitHub-Release stimmen nicht überein." }
        require(candidate.minSdk <= deviceSdk) { "Diese APK benötigt eine neuere Android-Version." }
        require(installed.signers.isNotEmpty() && candidate.signers == installed.signers) { "Die APK-Signatur passt nicht zur installierten App." }
    }

    @Serializable private data class GitHubRelease(
        val tag_name: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val body: String? = null,
        val assets: List<GitHubAsset>,
    )

    @Serializable private data class GitHubAsset(
        val name: String,
        val state: String,
        val size: Long,
        val browser_download_url: String,
        val digest: String? = null,
    )
}
