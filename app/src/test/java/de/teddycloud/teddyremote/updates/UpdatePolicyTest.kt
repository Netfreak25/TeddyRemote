package de.teddycloud.teddyremote.updates

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun payload(version: String = "0.1.34", asset: String = "TeddyRemote-$version-release.apk") =
        """{"tag_name":"v$version","draft":false,"prerelease":false,"body":"Changes","assets":[{"name":"$asset","state":"uploaded","size":1234,"browser_download_url":"https://github.com/Netfreak25/TeddyRemote/releases/download/v$version/$asset"}]}"""

    @Test fun `only a single stable newer release asset qualifies`() {
        assertEquals("0.1.34", UpdatePolicy.selectRelease(payload(), "0.1.33")?.version)
        assertNull(UpdatePolicy.selectRelease(payload(), "0.1.34"))
        assertNull(UpdatePolicy.selectRelease(payload(), "0.2.0"))
        assertEquals("0.1.10", UpdatePolicy.selectRelease(payload("0.1.10"), "0.1.9")?.version)
        for (invalid in listOf(
            payload().replace("\"draft\":false", "\"draft\":true"),
            payload().replace("\"prerelease\":false", "\"prerelease\":true"),
            payload(asset = "TeddyRemote-0.1.34-debug.apk"),
            payload(asset = "source.zip"),
            payload().replace("github.com/", "evil.example/"),
            payload().replace("https://", "http://"),
            payload().replace("\"size\":1234", "\"size\":0"),
            payload().replace("}]}", "}," + payload().substringAfter("\"assets\":[")),
        )) assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.selectRelease(invalid, "0.1.33") }
    }

    @Test fun `package version debug flag and signer must all match update policy`() {
        val installed = ApkIdentity(UpdatePolicy.PACKAGE_NAME, 34, "0.1.33", false, setOf("signer"))
        val candidate = installed.copy(versionCode = 35, versionName = "0.1.34")
        val release = requireNotNull(UpdatePolicy.selectRelease(payload(), "0.1.33"))
        UpdatePolicy.validateApk(installed, candidate, release, 36)
        for (invalid in listOf(
            candidate.copy(packageName = "other.app"), candidate.copy(debuggable = true),
            candidate.copy(versionCode = 34), candidate.copy(versionCode = 33),
            candidate.copy(versionName = "0.1.35"), candidate.copy(signers = setOf("other")),
            candidate.copy(signers = emptySet()), candidate.copy(minSdk = 37),
        )) assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.validateApk(installed, invalid, release, 36) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePolicy.validateApk(installed.copy(debuggable = true), candidate, release, 36)
        }
    }
}
