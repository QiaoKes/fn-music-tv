package com.fnmusic.tv.update

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateContractsTest {
    private val endpoint = URI("https://download.example.com/update.json")

    @Test fun `valid manifest uses the fixed package and same R2 host`() {
        val result = validateManifest(validDto(), endpoint)

        assertEquals(22L, result.versionCode)
        assertEquals("https://download.example.com/releases/22/app.apk", result.apkUrl)
    }

    @Test fun `rejects APK on a second host`() {
        val dto = validDto().copy(apk = validDto().apk.copy(url = "https://github.com/app.apk"))

        assertThrows(UpdateFailure::class.java) { validateManifest(dto, endpoint) }
    }

    @Test fun `rejects unknown schema package uppercase digest and excessive notes`() {
        listOf(
            validDto().copy(schemaVersion = 2),
            validDto().copy(packageName = "com.example.other"),
            validDto().copy(apk = validDto().apk.copy(sha256 = "A".repeat(64))),
            validDto().copy(notes = "x".repeat(MAX_UPDATE_NOTES_LENGTH + 1)),
        ).forEach { dto ->
            assertThrows(UpdateFailure::class.java) { validateManifest(dto, endpoint) }
        }
    }

    @Test fun `rejects insecure endpoint`() {
        assertThrows(UpdateFailure::class.java) { validateHttpsUri("http://download.example.com/update.json", "更新地址") }
    }

    private fun validDto() = UpdateManifestDto(
        schemaVersion = 1,
        packageName = UPDATE_PACKAGE_NAME,
        versionName = "1.0.6",
        versionCode = 22,
        title = "v1.0.6",
        notes = "更新内容",
        apk = UpdateApkDto(
            url = "https://download.example.com/releases/22/app.apk",
            size = 1_024,
            sha256 = "a".repeat(64),
        ),
        publishedAt = "2026-08-11T00:00:00Z",
        githubReleaseUrl = "https://github.com/QiaoKes/fn-music-tv/releases/tag/v1.0.6",
    )
}
