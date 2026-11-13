package app.familygem.update

import com.google.gson.JsonParser

data class GitHubRelease(
    val version: String,
    val apkUrl: String,
    val sha256Url: String,
    val notes: String,
    val apkSize: Long
)

/**
 * Release data is untrusted: only stable releases from our exact GitHub repo
 * with fixed asset names and expected download URLs may be used.
 */
object GitHubUpdatePolicy {
    const val API_URL = "https://api.github.com/repos/IgorNadein/family-tree-android/releases/latest"
    const val APK_NAME = "family-tree-android.apk"
    const val HASH_NAME = "family-tree-android.apk.sha256"
    private val tagRegex = Regex("""v(\d+)\.(\d+)\.(\d+)""")

    fun isNewer(candidate: String, current: String): Boolean {
        fun numbers(s: String): List<Long>? {
            val v = s.removePrefix("v")
            val parts = v.split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } })
                return null
            return parts.map { it.toLongOrNull() ?: return null }
        }
        val next = numbers(candidate) ?: return false
        val installed = numbers(current) ?: return false
        return (0..2).firstOrNull { next[it] != installed[it] }?.let { next[it] > installed[it] } ?: false
    }

    fun parseLatest(json: String, installedVersion: String): GitHubRelease? {
        val release = JsonParser.parseString(json).asJsonObject
        if (release.get("draft")?.asBoolean != false ||
            release.get("prerelease")?.asBoolean != false) return null
        val tag = release.get("tag_name")?.asString ?: return null
        if (!tagRegex.matches(tag) || !isNewer(tag, installedVersion)) return null
        val prefix = "https://github.com/IgorNadein/family-tree-android/releases/download/$tag/"
        val assets = release.getAsJsonArray("assets") ?: return null
        val apk = assets.map { it.asJsonObject }.singleOrNull {
            it.get("name")?.asString == APK_NAME
        } ?: return null
        val hash = assets.map { it.asJsonObject }.singleOrNull {
            it.get("name")?.asString == HASH_NAME
        } ?: return null
        val apkUrl = apk.get("browser_download_url")?.asString
        val hashUrl = hash.get("browser_download_url")?.asString
        if (apkUrl != prefix + APK_NAME || hashUrl != prefix + HASH_NAME) return null
        return GitHubRelease(tag.removePrefix("v"), apkUrl, hashUrl,
            release.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(1400),
            apk.get("size")?.asLong ?: 0)
    }

    fun parseChecksum(text: String): String? {
        val raw = text.trim().lines().singleOrNull()?.trim() ?: return null
        val value = Regex("""^([a-fA-F0-9]{64})(?:\s+\*?family-tree-android\.apk)?$""")
            .matchEntire(raw) ?: return null
        return value.groupValues[1].lowercase()
    }
}
