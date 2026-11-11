package app.familygem.data

import app.familygem.history.VersionedTreeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.parser.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A boundary around the on-disk family tree format.
 *
 * IDs map to the existing "<id>.json" files: no database migration is needed.
 */
interface TreeRepository {
    suspend fun read(treeId: Int): Gedcom?
    suspend fun write(treeId: Int, gedcom: Gedcom)
}

/**
 * Preserves Family Gem's JSON format while replacing direct, non-atomic file writes.
 * A temporary file in the same directory is renamed into place only after a
 * complete successful write, so a failed save does not truncate the last tree.
 */
class FileTreeRepository(private val filesDir: File) : TreeRepository {
    private val writeMutex = Mutex()
    private val history = VersionedTreeStore(filesDir)

    override suspend fun read(treeId: Int): Gedcom? = withContext(Dispatchers.IO) {
        val json = treeFile(treeId).readText(Charsets.UTF_8)
        JsonParser().fromJson(LegacyTreeJson.normalize(json))
    }

    override suspend fun write(treeId: Int, gedcom: Gedcom): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val destination = treeFile(treeId)
            val temporary = File.createTempFile("tree-" + treeId + "-", ".tmp", filesDir)
            try {
                val json = JsonParser().toJson(gedcom)
                FileOutputStream(temporary).use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to replace tree file: " + destination.name)
                }
                // Old JSON remains 100% compatible; the revision journal lives
                // separately and is reconstructed if a save was interrupted.
                try {
                    history.record(treeId, json)
                } catch (journalError: Exception) {
                    // The GEDCOM file was saved successfully. Do not break
                    // legacy saves if the optional history journal is corrupt
                    // or this old tree contains inconsistent references.
                    android.util.Log.w("FamilyTreeHistory", "Cannot append optional revision", journalError)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    private fun treeFile(treeId: Int): File = File(filesDir, treeId.toString() + ".json")
}

/**
 * Historical keys from early Family Gem releases. Only JSON property names are
 * migrated; person names and other user data are left untouched.
 */
internal object LegacyTreeJson {
    fun normalize(json: String): String = json
        .replace("\"zona\":", "\"zone\":")
        .replace("\"famili\":", "\"kin\":")
        .replace("\"passato\":", "\"passed\":")
}
