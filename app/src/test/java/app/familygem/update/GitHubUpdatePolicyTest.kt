package app.familygem.update

import org.junit.Assert.*
import org.junit.Test

class GitHubUpdatePolicyTest {
    private fun release(tag: String, apk: String = "family-tree-android.apk"): String = """
        {
          "tag_name": "$tag", "draft": false, "prerelease": false,
          "body": "Improved importer",
          "assets": [
            {"name":"$apk","size":15800000,
             "browser_download_url":"https://github.com/IgorNadein/family-tree-android/releases/download/$tag/$apk"},
            {"name":"family-tree-android.apk.sha256","size":90,
             "browser_download_url":"https://github.com/IgorNadein/family-tree-android/releases/download/$tag/family-tree-android.apk.sha256"}
          ]
        }""".trimIndent()

    @Test fun versionCheckUsesNumericOrderNotLexicalOrder() {
        assertTrue(GitHubUpdatePolicy.isNewer("v0.2.10", "0.2.9"))
        assertTrue(GitHubUpdatePolicy.isNewer("v1.0.0", "0.99.99"))
        assertFalse(GitHubUpdatePolicy.isNewer("v0.2.8", "0.2.8"))
        assertFalse(GitHubUpdatePolicy.isNewer("v0.2.7", "0.2.8"))
        assertFalse(GitHubUpdatePolicy.isNewer("main", "0.2.8"))
        assertFalse(GitHubUpdatePolicy.isNewer("v0.2.8-pre", "0.2.7"))
    }

    @Test fun officialReleaseMustContainExpectedAssets() {
        val update = GitHubUpdatePolicy.parseLatest(release("v0.2.9"), "0.2.8")!!
        assertEquals("0.2.9", update.version)
        assertEquals(15800000L, update.apkSize)
        assertTrue(update.apkUrl.endsWith("/v0.2.9/family-tree-android.apk"))
        assertNull(GitHubUpdatePolicy.parseLatest(release("v0.2.9"), "0.2.9"))
        assertNull(GitHubUpdatePolicy.parseLatest(release("v0.2.9", "malware.apk"), "0.2.8"))
    }

    @Test fun tamperedDownloadUrlAndBadTagAreRejected() {
        assertNull(GitHubUpdatePolicy.parseLatest(
            release("v0.2.9").replace("https://github.com/", "https://fake.example/"), "0.2.8"))
        assertNull(GitHubUpdatePolicy.parseLatest(release("v0.2.9/../../other"), "0.2.8"))
        assertNull(GitHubUpdatePolicy.parseLatest(
            release("v0.2.9").replace("\"draft\": false", "\"draft\": true"), "0.2.8"))
    }

    @Test fun releaseChecksumOnlyAcceptsSha256ForExpectedFile() {
        val hash = "a".repeat(64)
        assertEquals(hash, GitHubUpdatePolicy.parseChecksum(hash + "  family-tree-android.apk\n"))
        assertEquals(hash, GitHubUpdatePolicy.parseChecksum(hash))
        assertNull(GitHubUpdatePolicy.parseChecksum(hash + "  another.apk"))
        assertNull(GitHubUpdatePolicy.parseChecksum("abcd"))
        assertNull(GitHubUpdatePolicy.parseChecksum(hash + "\n" + hash))
    }
}
