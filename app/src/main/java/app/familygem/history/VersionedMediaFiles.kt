package app.familygem.history

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Platform-dependent access to files mentioned by GEDCOM OBJE.FILE. */
data class MediaFingerprint(val locator: String, val size: Long, val modified: Long)

interface TreeMediaAccess {
    fun open(treeId: Int, reference: String): InputStream?
    /** Optional stat cache. Unreliable providers return null and are always hashed. */
    fun fingerprint(treeId: Int, reference: String): MediaFingerprint? = null
    fun hasCheckout(treeId: Int, reference: String): Boolean = false
    /** A writable, tree-local checkout. Never write directly into immutable blobs. */
    fun checkout(treeId: Int, reference: String, blob: File)
}

/** Local checkout names depend on the complete reference, not its basename. */
object VersionedMediaFiles {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type
    private data class Cached(val modified: Long, val length: Long, val entries: Map<String, String>)
    private val cached = mutableMapOf<String, Cached>()
    @Synchronized fun invalidate(filesDir: File, treeId: Int) {
        cached.remove(index(filesDir, treeId).absolutePath)
    }
    fun index(filesDir: File, treeId: Int) = File(filesDir, ".ftree-history/$treeId/working-media.json")
    fun current(filesDir: File, treeId: Int): Map<String, String> {
        val file = index(filesDir, treeId)
        if (!file.isFile) return emptyMap()
        val key = file.absolutePath
        val stamp = file.lastModified()
        val length = file.length()
        synchronized(this) {
            cached[key]?.takeIf { it.modified == stamp && it.length == length }?.let { return it.entries }
            val entries = gson.fromJson<Map<String, String>>(file.readText(), mapType).orEmpty()
            cached[key] = Cached(stamp, length, entries)
            return entries
        }
    }
    fun filename(reference: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(reference.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val ext = reference.substringAfterLast('/').substringAfterLast('\\')
            .substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 1..12 && it.all(Char::isLetterOrDigit) }
        return hash + (if (ext == null) "" else ".$ext")
    }
    fun checkoutFile(root: File, reference: String) =
        File(File(root, ".ftree-files"), filename(reference))
    fun mappedFile(filesDir: File, root: File, treeId: Int, reference: String): File? {
        if (!current(filesDir, treeId).containsKey(reference)) return null
        return checkoutFile(root, reference).takeIf { it.isFile }
    }
}
