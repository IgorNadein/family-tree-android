package app.familygem.history

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import app.familygem.visitor.MediaList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.folg.gedcom.parser.JsonParser as GedcomJsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Offline, versioned Family Tree exchange.
 *
 * This is NOT a Git repository and does not mutate existing GEDCOM identifiers.
 * Full snapshots are used for the first format version to make three-way merges
 * understandable, deterministic and independent of a server.
 */
data class HistoryManifest(
    val format: String = "family-tree-history",
    val formatVersion: Int = 1,
    val treeUuid: String = "",
    val head: String = ""
)

data class SnapshotCommit(
    val parents: List<String>,
    val createdAtMillis: Long,
    val author: String,
    val snapshotJson: String,
    val id: String,
    /** null means a legacy v1 commit whose hash did not cover attachments. */
    val media: Map<String, String>? = null
)

data class VersionedTreeArchive(
    val manifest: HistoryManifest,
    val title: String,
    val commits: List<SnapshotCommit>,
    /** Staged, verified blob files. Never keep binary contents in memory. */
    val blobFolder: File? = null
) {
    fun headCommit(): SnapshotCommit = commits.first { it.id == manifest.head }
    fun release() { blobFolder?.deleteRecursively() }
}

enum class MergeChoice { LOCAL, INCOMING }

data class MergeConflict(
    val collection: String,
    val recordId: String,
    val base: JsonElement?,
    val local: JsonElement?,
    val incoming: JsonElement?
) {
    val key: String get() = "$collection/$recordId"
}

data class RecoverableTree(val id: Int, val peopleCount: Int, val treeUuid: String)

data class MergePreview(
    val treeId: Int,
    val localHead: String,
    val incoming: VersionedTreeArchive,
    val commonAncestorId: String,
    val changes: Int,
    val conflicts: List<MergeConflict>,
    val reassignedPeople: Int = 0,
    val reassignedFamilies: Int = 0
)

/**
 * JSON three-way merge at the GEDCOM record boundary.
 *
 * Values with an "id" in a top-level array are merged independently.
 * Simultaneous edits to the same record are conflicts rather than destructive
 * "last writer wins" guesses. Other JSON fields, e.g. the GEDCOM header, are
 * treated as single logical values.
 */
object GedcomThreeWayMerge {
    data class Result(
        val merged: JsonObject,
        val conflicts: List<MergeConflict>,
        val changes: Int,
        val reassignedPeople: Int = 0,
        val reassignedFamilies: Int = 0
    )

    fun merge(baseJson: String, localJson: String, incomingJson: String, choices: Map<String, MergeChoice> = emptyMap()): Result {
        val base = jsonObject(baseJson)
        val local = jsonObject(localJson)
        val collisionFix = rewriteNewRecordIdCollisions(base, local, jsonObject(incomingJson))
        val incoming = collisionFix.snapshot
        val merged = JsonObject()
        val conflicts = mutableListOf<MergeConflict>()
        var changes = 0
        val fields = linkedSetOf<String>().also {
            it += base.keySet()
            it += local.keySet()
            it += incoming.keySet()
        }
        for (field in fields) {
            val b = base.get(field)
            val l = local.get(field)
            val r = incoming.get(field)
            if (isIndexedArray(field, b, l, r)) {
                val ancestor = indexRecords(b)
                val ours = indexRecords(l)
                val theirs = indexRecords(r)
                val ids = linkedSetOf<String>().also {
                    it += ancestor.keys
                    it += ours.keys
                    it += theirs.keys
                }
                val records = JsonArray()
                for (id in ids) {
                    val value = pick(field, id, ancestor[id], ours[id], theirs[id], choices, conflicts)
                    if (value != null) records.add(value.deepCopy())
                    if (value != ours[id]) changes++
                }
                merged.add(field, records)
            } else {
                val value = pick("root", field, b, l, r, choices, conflicts)
                if (value != null) merged.add(field, value.deepCopy())
                if (value != l) changes++
            }
        }
        return Result(merged, conflicts, changes,
            collisionFix.peopleRenamed, collisionFix.familiesRenamed)
    }

    private data class CollisionFix(
        val snapshot: JsonObject, val peopleRenamed: Int, val familiesRenamed: Int
    )

    /**
     * Two devices may assign I140 or F47 to unrelated new records after a split.
     * Never interpret a matching numeric ID as proof of the same individual.
     * Keep the original commits untouched; only re-key the incoming checkout
     * used to construct the new merge commit. All references must follow.
     */
    private fun rewriteNewRecordIdCollisions(
        base: JsonObject, local: JsonObject, incoming: JsonObject
    ): CollisionFix {
        val snapshot = incoming.deepCopy()
        val replacements = linkedMapOf<String, String>()
        var peopleRenamed = 0
        var familiesRenamed = 0
        for ((collection, prefix) in listOf("people" to "I", "families" to "F")) {
            val ancestorIds = indexRecords(base.get(collection)).keys
            val ours = indexRecords(local.get(collection))
            val theirs = indexRecords(incoming.get(collection))
            val reserved = (ancestorIds + ours.keys + theirs.keys).toMutableSet()
            // A synthetic ID is stable across preview/apply and independent of
            // whichever numeric IDs happen to exist on this device.
            val originalSnapshotDigest = MessageDigest.getInstance("SHA-256")
                .digest(incoming.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            fun hasRemappedPersonReference(element: JsonElement): Boolean = when {
                element.isJsonArray -> element.asJsonArray.any(::hasRemappedPersonReference)
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    obj.get("ref")?.let { it.isJsonPrimitive && it.asString in replacements } == true ||
                        obj.entrySet().any { hasRemappedPersonReference(it.value) }
                }
                else -> false
            }
            val colliding = theirs.keys.filter { id ->
                id !in ancestorIds && id in ours && (ours[id] != theirs[id] ||
                    (collection == "families" && hasRemappedPersonReference(theirs.getValue(id))))
            }
            val newIds = linkedMapOf<String, String>()
            for (id in colliding) {
                var attempt = 0
                var replacement: String
                do {
                    val data = "$collection/$originalSnapshotDigest/$id/$attempt"
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(data.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    replacement = prefix + "_" + hash.take(32)
                    attempt++
                } while (replacement in reserved)
                newIds[id] = replacement
                reserved += replacement
            }
            if (newIds.isNotEmpty()) {
                snapshot.getAsJsonArray(collection)?.forEach { item ->
                    val record = item.asJsonObject
                    newIds[record.get("id").asString]?.let { record.addProperty("id", it) }
                }
                replacements.putAll(newIds)
            }
            if (collection == "people") peopleRenamed = newIds.size
            else familiesRenamed = newIds.size
        }

        if (replacements.isNotEmpty()) {
            // Only GEDCOM reference fields are identifiers. A free-text note
            // that mentions I163 is historical content and must not be rewritten:
            // doing so changes unrelated ancestral records and creates conflicts.
            fun updateReferences(element: JsonElement) {
                when {
                    element.isJsonArray -> element.asJsonArray.forEach(::updateReferences)
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        val ref = obj.get("ref")
                        if (ref != null && ref.isJsonPrimitive && ref.asJsonPrimitive.isString) {
                            replacements[ref.asString]?.let { obj.addProperty("ref", it) }
                        }
                        obj.entrySet().forEach { updateReferences(it.value) }
                    }
                }
            }
            updateReferences(snapshot)
        }
        return CollisionFix(snapshot, peopleRenamed, familiesRenamed)
    }

    private val records = setOf("people", "families", "notes", "sources", "media", "repositories", "submitters")
    private fun isIndexedArray(field: String, vararg values: JsonElement?): Boolean =
        field in records && values.all { it == null || it.isJsonNull || it is JsonArray }

    private fun indexRecords(value: JsonElement?): LinkedHashMap<String, JsonElement> {
        val result = linkedMapOf<String, JsonElement>()
        if (value == null || value.isJsonNull) return result
        for (item in value.asJsonArray) {
            require(item.isJsonObject && item.asJsonObject.has("id") && item.asJsonObject.get("id").isJsonPrimitive) {
                "GEDCOM record without a stable id"
            }
            val id = item.asJsonObject.get("id").asString
            require(id.isNotBlank() && result.putIfAbsent(id, item) == null) { "Duplicate GEDCOM id $id" }
        }
        return result
    }

    private fun pick(
        collection: String, id: String,
        base: JsonElement?, local: JsonElement?, incoming: JsonElement?,
        choices: Map<String, MergeChoice>, conflicts: MutableList<MergeConflict>
    ): JsonElement? {
        if (incoming == base || incoming == local) return local
        if (local == base) return incoming
        // The GEDCOM header is rewritten with a new dateTime on every save.
        // Two independent edits to different people must not conflict merely
        // because both applications stamped their own save timestamp.
        if (collection == "root" && (id == "header" || id == "head")) {
            val b = headerWithoutVolatileFields(base)
            val l = headerWithoutVolatileFields(local)
            val r = headerWithoutVolatileFields(incoming)
            if (r == b || r == l) return local
            if (l == b) return incoming
        }
        // Preserve independent changes to different properties of a GEDCOM record.
        // Lists are atomic: combining parent/child references automatically is unsafe.
        if (collection != "root" && base is JsonObject && local is JsonObject && incoming is JsonObject) {
            val merged = JsonObject()
            val keys = linkedSetOf<String>().also {
                it += base.keySet()
                it += local.keySet()
                it += incoming.keySet()
            }
            for (field in keys) {
                val value = pick("$collection/$id", field, base.get(field),
                    local.get(field), incoming.get(field), choices, conflicts)
                if (value != null) merged.add(field, value.deepCopy())
            }
            return merged
        }
        // A person can belong to multiple spouse families; children added to
        // the same unchanged family can likewise be accumulated. Only union
        // truly append-only ref lists; changes/removals still need review.
        if ((collection.startsWith("people/") && id == "fams") ||
            (collection.startsWith("families/") && id == "childRefs")) {
            appendOnlyReferenceUnion(base, local, incoming)?.let { return it }
        }
        val conflict = MergeConflict(collection, id, base, local, incoming)
        conflicts += conflict
        return if (choices[conflict.key] == MergeChoice.INCOMING) incoming else local
    }

    private fun appendOnlyReferenceUnion(
        base: JsonElement?, local: JsonElement?, incoming: JsonElement?
    ): JsonArray? {
        fun entries(value: JsonElement?): LinkedHashMap<String, JsonObject>? {
            if (value != null && !value.isJsonNull && !value.isJsonArray) return null
            val refs = linkedMapOf<String, JsonObject>()
            for (element in (value?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())) {
                if (!element.isJsonObject) return null
                val ref = element.asJsonObject.get("ref") ?: return null
                if (!ref.isJsonPrimitive || !ref.asJsonPrimitive.isString ||
                    refs.putIfAbsent(ref.asString, element.asJsonObject) != null) return null
            }
            return refs
        }
        val b = entries(base) ?: return null
        val l = entries(local) ?: return null
        val r = entries(incoming) ?: return null
        // If an old relation was removed or edited, it is a genuine conflict.
        if (b.any { (id, value) -> l[id] != value || r[id] != value }) return null
        // A shared newly-added relationship with conflicting details must be reviewed.
        if (l.any { (id, value) -> r[id] != null && r[id] != value }) return null
        return JsonArray().also { array ->
            l.values.forEach { array.add(it.deepCopy()) }
            r.filterKeys { it !in l }.values.forEach { array.add(it.deepCopy()) }
        }
    }

    private fun headerWithoutVolatileFields(value: JsonElement?): JsonElement? {
        if (value == null || !value.isJsonObject) return value
        val result = value.asJsonObject.deepCopy()
        result.remove("dateTime")
        result.remove("date") // GEDCOM JSON encodes the header as "head.date"
        val source = result.get("sour")
        if (source != null && source.isJsonObject) {
            source.asJsonObject.remove("vers")
        }
        val generator = result.get("generator")
        if (generator != null && generator.isJsonObject) {
            generator.asJsonObject.remove("version")
        }
        return result
    }

    private fun jsonObject(json: String): JsonObject {
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject) { "Expected GEDCOM JSON object" }
        return parsed.asJsonObject
    }
}

/**
 * Content-addressed repository. JSON snapshots remain GEDCOM-compatible;
 * v2 commits additionally hash a map of media references to immutable blobs.
 */
class VersionedTreeStore(private val filesDir: File, private val mediaAccess: TreeMediaAccess? = null) {
    companion object {
        // Independent repository instances share this lock inside one process.
        private val lock = Mutex()
        private val gson = Gson()
        private val hashPattern = Regex("[0-9a-f]{64}")
        private const val LEGACY_MAX_ENTRY_BYTES = 16L * 1024 * 1024
        private const val LEGACY_MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val LEGACY_MAX_COMMITS = 1024
    }

    private fun directory(treeId: Int): File = File(filesDir, ".ftree-history/$treeId")
    private fun manifestFile(treeId: Int): File = File(directory(treeId), "manifest.json")
    private fun commitsFolder(treeId: Int): File = File(directory(treeId), "commits")
    private fun commitFile(treeId: Int, id: String): File {
        require(hashPattern.matches(id))
        return File(commitsFolder(treeId), "$id.json")
    }
    private fun treeFile(treeId: Int): File = File(filesDir, "$treeId.json")
    private fun blobsFolder(treeId: Int): File = File(directory(treeId), "blobs")
    private fun sourceCacheFile(treeId: Int) = File(directory(treeId), "source-cache.json")
    private data class SourceCacheEntry(val fingerprint: MediaFingerprint, val hash: String)
    private val sourceCacheType = object : TypeToken<Map<String, SourceCacheEntry>>() {}.type
    private fun blobFile(treeId: Int, hash: String): File {
        require(hashPattern.matches(hash))
        return File(blobsFolder(treeId), hash)
    }

    private fun referencedFiles(json: String): Set<String> {
        val gedcom = GedcomJsonParser().fromJson(json)
        val visitor = MediaList(gedcom)
        gedcom.accept(visitor)
        return visitor.list.mapNotNull { it.file?.replace('\\', '/')?.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("https://") && !it.startsWith("http://") }
            .toSet()
    }

    private fun captureMedia(treeId: Int, snapshotJson: String, strict: Boolean): Map<String, String> {
        if (mediaAccess == null) return emptyMap()
        val previous = sourceCacheFile(treeId).takeIf { it.isFile }?.let {
            gson.fromJson<Map<String, SourceCacheEntry>>(it.readText(), sourceCacheType)
        }.orEmpty()
        val updated = linkedMapOf<String, SourceCacheEntry>()
        val result = linkedMapOf<String, String>()
        for (reference in referencedFiles(snapshotJson)) {
            val fingerprint = mediaAccess.fingerprint(treeId, reference)
            val cached = previous[reference]
            // As with Git's index, avoid re-reading multi-gigabyte files on every text edit.
            // Always rehash during explicit export, and avoid caching files just modified.
            val stable = fingerprint != null && fingerprint.modified < System.currentTimeMillis() - 2000L
            val hash = if (!strict && stable && cached?.fingerprint == fingerprint &&
                blobFile(treeId, cached.hash).isFile) cached.hash
            else {
                val input = mediaAccess.open(treeId, reference)
                if (input == null) {
                    if (strict) throw IOException("Referenced attachment cannot be read: $reference")
                    continue
                }
                input.use { storeBlob(blobsFolder(treeId), it) }
            }
            result[reference] = hash
            if (fingerprint != null) updated[reference] = SourceCacheEntry(fingerprint, hash)
        }
        writeAtomic(sourceCacheFile(treeId), gson.toJson(updated))
        return result
    }

    private fun storeBlob(folder: File, input: InputStream): String {
        folder.mkdirs()
        val temp = File.createTempFile("blob-", ".tmp", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(folder, hash)
            if (!destination.isFile && !temp.renameTo(destination)) {
                throw IOException("Cannot install attachment blob $hash")
            }
            return hash
        } finally {
            temp.delete()
        }
    }

    private fun installBlobs(treeId: Int, archive: VersionedTreeArchive) {
        val allHashes = archive.commits.flatMap { it.media.orEmpty().values }.toSet()
        if (allHashes.isEmpty()) return
        val sourceFolder = archive.blobFolder ?: throw IOException("Archive has no staged attachments")
        for (hash in allHashes) {
            val existing = blobFile(treeId, hash)
            if (existing.isFile) continue
            val source = File(sourceFolder, hash)
            require(source.isFile) { "Missing attachment $hash" }
            source.inputStream().use { input ->
                require(storeBlob(blobsFolder(treeId), input) == hash) { "Invalid attachment hash $hash" }
            }
        }
    }

    private fun checkout(
        treeId: Int, media: Map<String, String>,
        previousHeadMedia: Map<String, String> = emptyMap()
    ) {
        if (media.isNotEmpty()) requireNotNull(mediaAccess) { "Attachment storage is unavailable" }
        val previousCheckout = VersionedMediaFiles.current(filesDir, treeId)
        var changed = false
        for ((ref, hash) in media) {
            val blob = blobFile(treeId, hash)
            require(blob.isFile) { "Missing attachment $hash" }
            if (previousCheckout[ref] == hash && previousHeadMedia[ref] == hash &&
                mediaAccess!!.hasCheckout(treeId, ref)) continue
            mediaAccess!!.checkout(treeId, ref, blob)
            changed = true
        }
        writeAtomic(VersionedMediaFiles.index(filesDir, treeId), gson.toJson(media))
        VersionedMediaFiles.invalidate(filesDir, treeId)
        if (changed) sourceCacheFile(treeId).delete()
    }

    /** Called after ordinary repository saves. A no-op if the snapshot is unchanged. */
    suspend fun record(treeId: Int, snapshotJson: String): HistoryManifest = withContext(Dispatchers.IO) {
        lock.withLock { recordLocked(treeId, snapshotJson) }
    }

    private fun recordLocked(treeId: Int, snapshotJson: String, strictMedia: Boolean = false): HistoryManifest {
        validateGedcom(snapshotJson)
        val oldManifest = readManifest(treeId)
        val oldHead = oldManifest?.let { readCommit(treeId, it.head) }
        val media = captureMedia(treeId, snapshotJson, strictMedia)
        if (oldHead?.snapshotJson == snapshotJson && oldHead.media == media &&
            oldManifest.formatVersion == 2) return oldManifest
        val commit = commit(
            parents = if (oldHead == null) emptyList() else listOf(oldHead.id),
            snapshot = snapshotJson,
            media = media
        )
        writeCommit(treeId, commit)
        val manifest = HistoryManifest(formatVersion = 2,
            treeUuid = oldManifest?.treeUuid ?: UUID.randomUUID().toString(), head = commit.id)
        writeAtomic(manifestFile(treeId), gson.toJson(manifest))
        return manifest
    }

    suspend fun export(treeId: Int, title: String, snapshotJson: String, stream: OutputStream) = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = recordLocked(treeId, snapshotJson, strictMedia = true)
            val commits = collectReachable(treeId, manifest.head).values.toList()
            writeArchive(treeId, VersionedTreeArchive(manifest, title, commits), stream)
        }
    }

    suspend fun inspect(input: InputStream): VersionedTreeArchive = withContext(Dispatchers.IO) {
        readArchive(input)
    }

    suspend fun localIdentity(treeId: Int): String? = withContext(Dispatchers.IO) {
        lock.withLock { readManifest(treeId)?.treeUuid }
    }

    /** Check the disk, not just settings: failed imports may leave orphaned files. */
    fun nextAvailableTreeId(registeredIds: Collection<Int>): Int {
        var id = (registeredIds.maxOrNull() ?: 0) + 1
        require(id > 0) { "No available tree identifier" }
        while (id in registeredIds || treeFile(id).exists() || directory(id).exists()) {
            require(id < Int.MAX_VALUE) { "No available tree identifier" }
            id++
        }
        return id
    }

    /** Allocates and imports under one lock to prevent concurrent collisions. */
    suspend fun importAsNew(registeredIds: Collection<Int>, archive: VersionedTreeArchive): Int =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val id = nextAvailableTreeId(registeredIds)
                importNewLocked(id, archive)
                id
            }
        }

    suspend fun importNew(treeId: Int, archive: VersionedTreeArchive) = withContext(Dispatchers.IO) {
        lock.withLock { importNewLocked(treeId, archive) }
    }

    private fun importNewLocked(treeId: Int, archive: VersionedTreeArchive) {
        require(!treeFile(treeId).exists() && !directory(treeId).exists()) { "Tree already exists" }
        validateArchive(archive)
        try {
            installBlobs(treeId, archive)
            installCommits(treeId, archive.commits)
            checkout(treeId, archive.headCommit().media.orEmpty())
            writeAtomic(treeFile(treeId), archive.headCommit().snapshotJson)
            writeAtomic(manifestFile(treeId), gson.toJson(archive.manifest))
        } catch (e: Exception) {
            treeFile(treeId).delete()
            directory(treeId).deleteRecursively()
            throw e
        }
    }

    /** Only complete, valid trees absent from settings can be recovered. */
    suspend fun findRecoverableTrees(registeredIds: Collection<Int>): List<RecoverableTree> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val listed = registeredIds.toSet()
                filesDir.listFiles().orEmpty().mapNotNull { file ->
                    val id = file.name.removeSuffix(".json").toIntOrNull()
                    if (id == null || !file.name.endsWith(".json") || id <= 0 ||
                        id in listed || !file.isFile) return@mapNotNull null
                    runCatching {
                        val manifest = readManifest(id) ?: return@runCatching null
                        val head = readCommit(id, manifest.head)
                        val json = file.readText(Charsets.UTF_8)
                        if (json != head.snapshotJson) return@runCatching null
                        validateGedcom(json)
                        val count = GedcomJsonParser().fromJson(json).people.size
                        RecoverableTree(id, count, manifest.treeUuid)
                    }.getOrNull()
                }.sortedBy { it.id }
            }
        }

    /** Same UUID or common reachable SHA-256 commit, never a name-based guess. */
    suspend fun isMergeCandidate(treeId: Int, incoming: VersionedTreeArchive): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val manifest = readManifest(treeId) ?: return@withLock false
                if (manifest.treeUuid == incoming.manifest.treeUuid) return@withLock true
                val incomingIds = incoming.commits.map { it.id }.toSet()
                if (incomingIds.none { commitFile(treeId, it).isFile }) return@withLock false
                collectReachable(treeId, manifest.head).keys.any { it in incomingIds }
            }
        }

    suspend fun planMerge(treeId: Int, currentJson: String, incoming: VersionedTreeArchive): MergePreview =
        withContext(Dispatchers.IO) {
            lock.withLock {
                validateArchive(incoming)
                val local = recordLocked(treeId, currentJson)
                val known = collectReachable(treeId, local.head).toMutableMap()
                incoming.commits.forEach { known[it.id] = it }
                val mineAncestors = ancestors(local.head, known)
                val theirsAncestors = ancestors(incoming.manifest.head, known)
                val common = mineAncestors.keys.intersect(theirsAncestors.keys)
                    .minWithOrNull(compareBy<String> { mineAncestors.getValue(it) + theirsAncestors.getValue(it) }
                        .thenBy { mineAncestors.getValue(it) })
                    ?: throw IllegalArgumentException("Different family trees: no common history; cannot merge unrelated revisions")
                val base = known.getValue(common)
                val ours = known.getValue(local.head)
                val theirs = known.getValue(incoming.manifest.head)
                val result = GedcomThreeWayMerge.merge(base.snapshotJson, ours.snapshotJson, theirs.snapshotJson)
                val files = mergeMedia(base.media.orEmpty(), ours.media.orEmpty(), theirs.media.orEmpty())
                MergePreview(treeId, local.head, incoming, common,
                    result.changes + files.changes, result.conflicts + files.conflicts,
                    result.reassignedPeople, result.reassignedFamilies)
            }
        }

    private data class MediaMergeResult(
        val values: Map<String, String>,
        val conflicts: List<MergeConflict>,
        val changes: Int
    )

    private fun mergeMedia(
        base: Map<String, String>, local: Map<String, String>, incoming: Map<String, String>,
        choices: Map<String, MergeChoice> = emptyMap()
    ): MediaMergeResult {
        val merged = linkedMapOf<String, String>()
        val conflicts = mutableListOf<MergeConflict>()
        var changes = 0
        for (ref in (base.keys + local.keys + incoming.keys).toSet()) {
            val b = base[ref]
            val l = local[ref]
            val r = incoming[ref]
            val chosen = when {
                r == b || r == l -> l
                l == b -> r
                else -> {
                    val conflict = MergeConflict("attachments", ref,
                        b?.let(::JsonPrimitive), l?.let(::JsonPrimitive), r?.let(::JsonPrimitive))
                    conflicts += conflict
                    if (choices[conflict.key] == MergeChoice.INCOMING) r else l
                }
            }
            if (chosen != null) merged[ref] = chosen
            if (chosen != l) changes++
        }
        return MediaMergeResult(merged, conflicts, changes)
    }

    /** Applies only a reviewed preview; refuses if local history has moved. */
    suspend fun applyMerge(preview: MergePreview, choices: Map<String, MergeChoice>): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val treeId = preview.treeId
                val manifest = readManifest(treeId) ?: throw IllegalStateException("Local tree has no history")
                require(manifest.head == preview.localHead) { "Tree changed after preview; review merge again" }
                val latestOnDisk = treeFile(treeId).readText(Charsets.UTF_8)
                require(latestOnDisk == readCommit(treeId, manifest.head).snapshotJson) {
                    "Tree data changed after preview; review merge again"
                }
                validateArchive(preview.incoming)
                val all = collectReachable(treeId, manifest.head).toMutableMap()
                preview.incoming.commits.forEach { all[it.id] = it }
                // An archived lineage may use a different UUID; require a real
                // common, reachable and content-verified commit on both sides.
                require(preview.commonAncestorId in ancestors(manifest.head, all) &&
                    preview.commonAncestorId in ancestors(preview.incoming.manifest.head, all)) {
                    "Tree identity mismatch: no shared ancestor"
                }
                val result = GedcomThreeWayMerge.merge(
                    all.getValue(preview.commonAncestorId).snapshotJson,
                    all.getValue(manifest.head).snapshotJson,
                    all.getValue(preview.incoming.manifest.head).snapshotJson,
                    choices
                )
                val mediaResult = mergeMedia(
                    all.getValue(preview.commonAncestorId).media.orEmpty(),
                    all.getValue(manifest.head).media.orEmpty(),
                    all.getValue(preview.incoming.manifest.head).media.orEmpty(), choices)
                require((result.conflicts + mediaResult.conflicts).all { choices.containsKey(it.key) }) {
                    "All merge conflicts must be resolved"
                }
                val mergedJson = gson.toJson(result.merged)
                validateGedcom(mergedJson)
                val refs = referencedFiles(mergedJson)
                val mergedMedia = mediaResult.values.filterKeys { it in refs }
                if (mergedJson == all.getValue(manifest.head).snapshotJson &&
                    mergedMedia == all.getValue(manifest.head).media.orEmpty() &&
                    preview.incoming.manifest.head == manifest.head) return@withLock false

                // Capture both lines of history and all blobs before publishing the merge.
                val mergeCommit = commit(listOf(manifest.head, preview.incoming.manifest.head).distinct(),
                    mergedJson, mergedMedia)
                installBlobs(treeId, preview.incoming)
                installCommits(treeId, preview.incoming.commits)
                writeCommit(treeId, mergeCommit)
                checkout(treeId, mergedMedia, all.getValue(manifest.head).media.orEmpty())
                writeAtomic(treeFile(treeId), mergedJson)
                writeAtomic(manifestFile(treeId), gson.toJson(manifest.copy(formatVersion = 2, head = mergeCommit.id)))
                true
            }
        }

    suspend fun history(treeId: Int): List<SnapshotCommit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = readManifest(treeId) ?: return@withLock emptyList()
            collectReachable(treeId, manifest.head).values.toList()
        }
    }

    /**
     * Restore is itself a new commit, like Git revert. The old commit and every
     * later revision remain reachable, and original attachments are checked out.
     */
    suspend fun restoreRevision(treeId: Int, revisionId: String): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val manifest = readManifest(treeId)
                    ?: throw IllegalArgumentException("No version history for this tree")
                val known = collectReachable(treeId, manifest.head)
                val selected = known[revisionId]
                    ?: throw IllegalArgumentException("Revision not reachable from current history")
                val current = known.getValue(manifest.head)
                if (current.id == selected.id) return@withLock false
                require(treeFile(treeId).readText(Charsets.UTF_8) == current.snapshotJson) {
                    "Save or reload the current tree before restoring a revision"
                }
                validateGedcom(selected.snapshotJson)
                val newHead = commit(listOf(current.id), selected.snapshotJson, selected.media.orEmpty())
                writeCommit(treeId, newHead)
                checkout(treeId, selected.media.orEmpty(), current.media.orEmpty())
                writeAtomic(treeFile(treeId), selected.snapshotJson)
                writeAtomic(manifestFile(treeId),
                    gson.toJson(manifest.copy(formatVersion = 2, head = newHead.id)))
                true
            }
        }

    suspend fun deleteHistory(treeId: Int) = withContext(Dispatchers.IO) {
        lock.withLock { directory(treeId).deleteRecursively() }
    }

    private fun commit(parents: List<String>, snapshot: String, media: Map<String, String>): SnapshotCommit {
        val timestamp = System.currentTimeMillis()
        val draft = SnapshotCommit(parents, timestamp, "Local device", snapshot, "", media)
        return draft.copy(id = expectedId(draft))
    }

    private data class CommitBody(
        val parents: List<String>, val createdAtMillis: Long,
        val author: String, val snapshotJson: String
    )
    private data class CommitBodyV2(
        val parents: List<String>, val createdAtMillis: Long,
        val author: String, val snapshotJson: String, val media: Map<String, String>
    )
    private fun expectedId(commit: SnapshotCommit) = sha256(gson.toJson(
        if (commit.media == null) CommitBody(commit.parents, commit.createdAtMillis,
            commit.author, commit.snapshotJson)
        else CommitBodyV2(commit.parents, commit.createdAtMillis,
            commit.author, commit.snapshotJson, commit.media)
    ))

    private fun readManifest(treeId: Int): HistoryManifest? {
        val file = manifestFile(treeId)
        return if (file.exists()) gson.fromJson(file.readText(), HistoryManifest::class.java).also(::validateManifest) else null
    }

    private fun readCommit(treeId: Int, id: String): SnapshotCommit =
        gson.fromJson(commitFile(treeId, id).readText(), SnapshotCommit::class.java)
            .also { require(it.id == id && expectedId(it) == id) { "Corrupt history commit" } }

    private fun writeCommit(treeId: Int, commit: SnapshotCommit) {
        val file = commitFile(treeId, commit.id)
        if (!file.exists()) writeAtomic(file, gson.toJson(commit))
    }

    private fun installCommits(treeId: Int, commits: List<SnapshotCommit>) {
        commits.forEach(::validateCommit)
        commits.forEach { writeCommit(treeId, it) }
    }

    private fun collectReachable(treeId: Int, head: String): LinkedHashMap<String, SnapshotCommit> {
        val result = linkedMapOf<String, SnapshotCommit>()
        val stack = ArrayDeque<String>()
        stack.addLast(head)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id in result) continue
            val revision = readCommit(treeId, id)
            result[id] = revision
            revision.parents.asReversed().forEach(stack::addLast)
        }
        return result
    }

    private fun ancestors(head: String, commits: Map<String, SnapshotCommit>): Map<String, Int> {
        val distances = mutableMapOf<String, Int>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(head to 0)
        while (queue.isNotEmpty()) {
            val (id, distance) = queue.removeFirst()
            if (distances[id]?.let { it <= distance } == true) continue
            distances[id] = distance
            commits.getValue(id).parents.forEach { queue.add(it to distance + 1) }
        }
        return distances
    }

    /**
     * ZIP64-compatible streaming: only JSON metadata is resident in memory.
     * Binary blobs are copied in fixed-size chunks and addressed by SHA-256.
     */
    private fun writeArchive(treeId: Int, archive: VersionedTreeArchive, stream: OutputStream) {
        validateArchive(archive)
        ZipOutputStream(stream).use { out ->
            fun entry(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            entry("manifest.json", gson.toJson(archive.manifest))
            entry("title.json", gson.toJson(archive.title))
            archive.commits.forEach { entry("commits/" + it.id + ".json", gson.toJson(it)) }
            val hashes = archive.commits.flatMap { it.media.orEmpty().values }.toSet().sorted()
            for (hash in hashes) {
                val blob = blobFile(treeId, hash)
                require(blob.isFile) { "Attachment lost from local history: " + hash }
                out.putNextEntry(ZipEntry("blobs/" + hash))
                val digest = MessageDigest.getInstance("SHA-256")
                blob.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == hash) {
                    "Local attachment corrupted: " + hash
                }
                out.closeEntry()
            }
        }
    }

    private fun readArchive(input: InputStream): VersionedTreeArchive {
        var manifest: HistoryManifest? = null
        var title: String? = null
        val revisions = mutableListOf<SnapshotCommit>()
        val names = hashSetOf<String>()
        val stage = File(filesDir, ".ftree-incoming/" + UUID.randomUUID().toString())
        var metadataBytes = 0L
        var largestMetadata = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name)) { "Duplicate or invalid archive entry" }
                    val isBlob = Regex("blobs/[0-9a-f]{64}").matches(entry.name)
                    val permitted = entry.name == "manifest.json" || entry.name == "title.json" ||
                        Regex("commits/[0-9a-f]{64}\\.json").matches(entry.name) || isBlob
                    require(permitted) { "Unsupported archive entry: " + entry.name }
                    if (isBlob) {
                        val hash = entry.name.removePrefix("blobs/")
                        val actual = storeBlob(stage, zip)
                        require(hash == actual) { "Corrupt attachment: " + hash }
                    } else {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            count += n
                            output.write(buffer, 0, n)
                        }
                        metadataBytes += count
                        largestMetadata = maxOf(largestMetadata, count)
                        val json = output.toString("UTF-8")
                        when (entry.name) {
                            "manifest.json" -> manifest = gson.fromJson(json, HistoryManifest::class.java)
                            "title.json" -> title = gson.fromJson(json, String::class.java)
                            else -> {
                                val revision = gson.fromJson(json, SnapshotCommit::class.java)
                                require(entry.name == "commits/" + revision.id + ".json") { "Commit filename mismatch" }
                                revisions += revision
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val archive = VersionedTreeArchive(
                manifest ?: throw IllegalArgumentException("Missing .ftree manifest"),
                title ?: throw IllegalArgumentException("Missing .ftree title"),
                revisions,
                if (stage.isDirectory) stage else null
            )
            validateArchive(archive)
            if (archive.manifest.formatVersion == 1) {
                require(!stage.isDirectory) { "v1 archive cannot contain attachments" }
                require(revisions.size <= LEGACY_MAX_COMMITS &&
                    metadataBytes <= LEGACY_MAX_TOTAL_BYTES &&
                    largestMetadata <= LEGACY_MAX_ENTRY_BYTES) { "Legacy archive too large" }
            } else {
                val expected = revisions.flatMap { it.media.orEmpty().values }.toSet()
                val actual = stage.listFiles()?.map { it.name }?.toSet().orEmpty()
                require(actual == expected) { "Missing or unreferenced archive attachments" }
            }
            return archive
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }

    private fun validateArchive(archive: VersionedTreeArchive) {
        validateManifest(archive.manifest)
        require(archive.title.length <= 512) { "Tree title is too long" }
        require(archive.commits.isNotEmpty()) { "Invalid revision count" }
        val commits = archive.commits.associateBy { it.id }
        require(commits.size == archive.commits.size && commits.containsKey(archive.manifest.head)) { "Broken history head" }
        archive.commits.forEach { revision ->
            validateCommit(revision)
            require(revision.parents.all(commits::containsKey)) { "Missing history parent" }
        }
        // Iterative DFS handles long commit chains without a call-stack limit.
        val state = mutableMapOf<String, Int>()
        val stack = ArrayDeque<Pair<String, Boolean>>()
        stack.addLast(archive.manifest.head to false)
        while (stack.isNotEmpty()) {
            val (id, finished) = stack.removeLast()
            if (finished) {
                state[id] = 2
                continue
            }
            require(state[id] != 1) { "Cyclic history" }
            if (state[id] == 2) continue
            state[id] = 1
            stack.addLast(id to true)
            commits.getValue(id).parents.asReversed().forEach { stack.addLast(it to false) }
        }
        require(state.size == commits.size) { "History includes unreachable revisions" }
    }

    private fun validateManifest(manifest: HistoryManifest) {
        require(manifest.format == "family-tree-history" && manifest.formatVersion in 1..2) { "Unsupported .ftree format" }
        require(UUID.fromString(manifest.treeUuid).toString() == manifest.treeUuid) { "Invalid tree UUID" }
        require(hashPattern.matches(manifest.head)) { "Invalid history head" }
    }

    private fun validateCommit(commit: SnapshotCommit) {
        require(hashPattern.matches(commit.id) && expectedId(commit) == commit.id) {
            "Corrupt .ftree history hash at " + commit.id.take(12)
        }
        require(commit.parents.size <= 2 && commit.parents.distinct().size == commit.parents.size &&
                commit.parents.all(hashPattern::matches)) { "Invalid history parents" }
        require(commit.author.length <= 256) { "History author too long" }
        commit.media?.forEach { (ref, hash) ->
            require(ref.isNotBlank() && ref.length <= 8192 && !ref.any(Char::isISOControl) &&
                !ref.startsWith("https://") && !ref.startsWith("http://") && hashPattern.matches(hash)) {
                "Invalid attachment mapping"
            }
        }
        validateGedcom(commit.snapshotJson)
    }

    private fun validateGedcom(json: String) {
        val document = JsonParser.parseString(json)
        require(document.isJsonObject) { "Invalid tree JSON" }
        val gedcom = GedcomJsonParser().fromJson(json)
            ?: throw IllegalArgumentException("Invalid GEDCOM data")
        // Do not accept dangling people/family links after merging a deletion
        // and another branch's addition. We do not infer missing parents.
        val people = gedcom.people.map { it.id }.toSet()
        val families = gedcom.families.map { it.id }.toSet()
        for (person in gedcom.people) {
            require(person.parentFamilyRefs.orEmpty().all { it.ref in families } &&
                    person.spouseFamilyRefs.orEmpty().all { it.ref in families }) {
                "Dangling family reference on person ${person.id}"
            }
        }
        for (family in gedcom.families) {
            require(family.childRefs.orEmpty().all { it.ref in people } &&
                    family.husbandRefs.orEmpty().all { it.ref in people } &&
                    family.wifeRefs.orEmpty().all { it.ref in people }) {
                "Dangling person reference in family ${family.id}"
            }
        }
        // Do not accept ambiguous duplicates in indexed collections.
        for (field in listOf("people", "families", "notes", "sources", "media", "repositories", "submitters")) {
            val data = document.asJsonObject.get(field)
            if (data != null && !data.isJsonNull) {
                require(data.isJsonArray) { "Invalid $field records" }
                val ids = hashSetOf<String>()
                for (record in data.asJsonArray) {
                    require(record.isJsonObject && record.asJsonObject.has("id")) { "Record without id" }
                    require(ids.add(record.asJsonObject.get("id").asString)) { "Duplicate $field record id" }
                }
            }
        }
    }

    private fun writeAtomic(destination: File, json: String) {
        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("ftree-", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(destination)) throw IOException("Cannot replace ${destination.name}")
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
