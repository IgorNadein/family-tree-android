package app.familygem.history

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
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
    val id: String
)

data class VersionedTreeArchive(
    val manifest: HistoryManifest,
    val title: String,
    val commits: List<SnapshotCommit>
) {
    fun headCommit(): SnapshotCommit = commits.first { it.id == manifest.head }
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

data class MergePreview(
    val treeId: Int,
    val localHead: String,
    val incoming: VersionedTreeArchive,
    val commonAncestorId: String,
    val changes: Int,
    val conflicts: List<MergeConflict>
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
        val changes: Int
    )

    fun merge(baseJson: String, localJson: String, incomingJson: String, choices: Map<String, MergeChoice> = emptyMap()): Result {
        val base = jsonObject(baseJson)
        val local = jsonObject(localJson)
        val incoming = jsonObject(incomingJson)
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
        return Result(merged, conflicts, changes)
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
        if (collection == "root" && id == "header") {
            val b = headerWithoutVolatileFields(base)
            val l = headerWithoutVolatileFields(local)
            val r = headerWithoutVolatileFields(incoming)
            if (r == b || r == l) return local
            if (l == b) return incoming
        }
        val conflict = MergeConflict(collection, id, base, local, incoming)
        conflicts += conflict
        return if (choices[conflict.key] == MergeChoice.INCOMING) incoming else local
    }

    private fun headerWithoutVolatileFields(value: JsonElement?): JsonElement? {
        if (value == null || !value.isJsonObject) return value
        val result = value.asJsonObject.deepCopy()
        result.remove("dateTime")
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

class VersionedTreeStore(private val filesDir: File) {
    companion object {
        // Independent repository instances share this lock inside one process.
        private val lock = Mutex()
        private val gson = Gson()
        private val hashPattern = Regex("[0-9a-f]{64}")
        private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_COMMITS = 1024
    }

    private fun directory(treeId: Int): File = File(filesDir, ".ftree-history/$treeId")
    private fun manifestFile(treeId: Int): File = File(directory(treeId), "manifest.json")
    private fun commitsFolder(treeId: Int): File = File(directory(treeId), "commits")
    private fun commitFile(treeId: Int, id: String): File {
        require(hashPattern.matches(id))
        return File(commitsFolder(treeId), "$id.json")
    }
    private fun treeFile(treeId: Int): File = File(filesDir, "$treeId.json")

    /** Called after ordinary repository saves. A no-op if the snapshot is unchanged. */
    suspend fun record(treeId: Int, snapshotJson: String): HistoryManifest = withContext(Dispatchers.IO) {
        lock.withLock { recordLocked(treeId, snapshotJson) }
    }

    private fun recordLocked(treeId: Int, snapshotJson: String): HistoryManifest {
        validateGedcom(snapshotJson)
        val oldManifest = readManifest(treeId)
        val oldHead = oldManifest?.let { readCommit(treeId, it.head) }
        if (oldManifest != null && oldHead?.snapshotJson == snapshotJson) return oldManifest
        val commit = commit(
            parents = if (oldHead == null) emptyList() else listOf(oldHead.id),
            snapshot = snapshotJson
        )
        writeCommit(treeId, commit)
        val manifest = HistoryManifest(treeUuid = oldManifest?.treeUuid ?: UUID.randomUUID().toString(), head = commit.id)
        writeAtomic(manifestFile(treeId), gson.toJson(manifest))
        return manifest
    }

    suspend fun export(treeId: Int, title: String, snapshotJson: String, stream: OutputStream) = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = recordLocked(treeId, snapshotJson)
            val commits = collectReachable(treeId, manifest.head).values.toList()
            writeArchive(VersionedTreeArchive(manifest, title, commits), stream)
        }
    }

    suspend fun inspect(input: InputStream): VersionedTreeArchive = withContext(Dispatchers.IO) {
        readArchive(input)
    }

    suspend fun localIdentity(treeId: Int): String? = withContext(Dispatchers.IO) {
        lock.withLock { readManifest(treeId)?.treeUuid }
    }

    suspend fun importNew(treeId: Int, archive: VersionedTreeArchive) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(!treeFile(treeId).exists() && !directory(treeId).exists()) { "Tree already exists" }
            validateArchive(archive)
            // Never overwrite an existing tree, even if something goes wrong.
            try {
                installCommits(treeId, archive.commits)
                writeAtomic(treeFile(treeId), archive.headCommit().snapshotJson)
                writeAtomic(manifestFile(treeId), gson.toJson(archive.manifest))
            } catch (e: Exception) {
                treeFile(treeId).delete()
                directory(treeId).deleteRecursively()
                throw e
            }
        }
    }

    suspend fun planMerge(treeId: Int, currentJson: String, incoming: VersionedTreeArchive): MergePreview =
        withContext(Dispatchers.IO) {
            lock.withLock {
                validateArchive(incoming)
                val local = recordLocked(treeId, currentJson)
                require(local.treeUuid == incoming.manifest.treeUuid) {
                    "Different family trees: import as a separate tree instead"
                }
                val known = collectReachable(treeId, local.head).toMutableMap()
                incoming.commits.forEach { known[it.id] = it }
                val mineAncestors = ancestors(local.head, known)
                val theirsAncestors = ancestors(incoming.manifest.head, known)
                val common = mineAncestors.keys.intersect(theirsAncestors.keys)
                    .minWithOrNull(compareBy<String> { mineAncestors.getValue(it) + theirsAncestors.getValue(it) }
                        .thenBy { mineAncestors.getValue(it) })
                    ?: throw IllegalArgumentException("No common history; cannot merge unrelated revisions")
                val result = GedcomThreeWayMerge.merge(
                    known.getValue(common).snapshotJson,
                    known.getValue(local.head).snapshotJson,
                    known.getValue(incoming.manifest.head).snapshotJson
                )
                MergePreview(treeId, local.head, incoming, common, result.changes, result.conflicts)
            }
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
                require(manifest.treeUuid == preview.incoming.manifest.treeUuid) { "Tree identity mismatch" }
                validateArchive(preview.incoming)
                val all = collectReachable(treeId, manifest.head).toMutableMap()
                preview.incoming.commits.forEach { all[it.id] = it }
                val result = GedcomThreeWayMerge.merge(
                    all.getValue(preview.commonAncestorId).snapshotJson,
                    all.getValue(manifest.head).snapshotJson,
                    all.getValue(preview.incoming.manifest.head).snapshotJson,
                    choices
                )
                require(result.conflicts.all { choices.containsKey(it.key) }) { "All merge conflicts must be resolved" }
                val mergedJson = gson.toJson(result.merged)
                validateGedcom(mergedJson)
                if (mergedJson == all.getValue(manifest.head).snapshotJson &&
                    preview.incoming.manifest.head == manifest.head) return@withLock false

                // New snapshot + a two-parent commit preserve both lines of history.
                val mergeCommit = commit(listOf(manifest.head, preview.incoming.manifest.head).distinct(), mergedJson)
                installCommits(treeId, preview.incoming.commits)
                writeCommit(treeId, mergeCommit)
                writeAtomic(treeFile(treeId), mergedJson)
                writeAtomic(manifestFile(treeId), gson.toJson(manifest.copy(head = mergeCommit.id)))
                true
            }
        }

    suspend fun history(treeId: Int): List<SnapshotCommit> = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = readManifest(treeId) ?: return@withLock emptyList()
            collectReachable(treeId, manifest.head).values.toList()
        }
    }

    suspend fun deleteHistory(treeId: Int) = withContext(Dispatchers.IO) {
        lock.withLock { directory(treeId).deleteRecursively() }
    }

    private fun commit(parents: List<String>, snapshot: String): SnapshotCommit {
        val timestamp = System.currentTimeMillis()
        val content = CommitBody(parents, timestamp, "Local device", snapshot)
        return SnapshotCommit(parents, timestamp, "Local device", snapshot, sha256(gson.toJson(content)))
    }

    private data class CommitBody(
        val parents: List<String>, val createdAtMillis: Long,
        val author: String, val snapshotJson: String
    )
    private fun expectedId(commit: SnapshotCommit) =
        sha256(gson.toJson(CommitBody(commit.parents, commit.createdAtMillis, commit.author, commit.snapshotJson)))

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
        fun visit(id: String) {
            if (result.containsKey(id)) return
            require(result.size < MAX_COMMITS) { "Too many history revisions" }
            val revision = readCommit(treeId, id)
            result[id] = revision
            revision.parents.forEach(::visit)
        }
        visit(head)
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

    private fun writeArchive(archive: VersionedTreeArchive, stream: OutputStream) {
        validateArchive(archive)
        val payloadBytes = archive.commits.sumOf { gson.toJson(it).toByteArray(Charsets.UTF_8).size.toLong() } +
            gson.toJson(archive.manifest).toByteArray(Charsets.UTF_8).size +
            gson.toJson(archive.title).toByteArray(Charsets.UTF_8).size
        require(payloadBytes <= MAX_TOTAL_BYTES) { "History is too large for .ftree v1 export" }
        ZipOutputStream(stream).use { out ->
            fun entry(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            entry("manifest.json", gson.toJson(archive.manifest))
            entry("title.json", gson.toJson(archive.title))
            archive.commits.forEach { entry("commits/${it.id}.json", gson.toJson(it)) }
        }
    }

    private fun readArchive(input: InputStream): VersionedTreeArchive {
        var manifest: HistoryManifest? = null
        var title: String? = null
        val revisions = mutableListOf<SnapshotCommit>()
        val names = hashSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name)) { "Duplicate or invalid archive entry" }
                val permitted = entry.name == "manifest.json" || entry.name == "title.json" ||
                    Regex("commits/[0-9a-f]{64}\\.json").matches(entry.name)
                require(permitted) { "Unsupported archive entry: ${entry.name}" }
                require(names.size <= MAX_COMMITS + 2) { "Too many archive entries" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var entrySize = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    entrySize += n
                    total += n
                    require(entrySize <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Archive is too large" }
                    output.write(buffer, 0, n)
                }
                val json = output.toString("UTF-8")
                when (entry.name) {
                    "manifest.json" -> manifest = gson.fromJson(json, HistoryManifest::class.java)
                    "title.json" -> title = gson.fromJson(json, String::class.java)
                    else -> {
                        val revision = gson.fromJson(json, SnapshotCommit::class.java)
                        require(entry.name == "commits/${revision.id}.json") { "Commit filename mismatch" }
                        revisions += revision
                    }
                }
                zip.closeEntry()
            }
        }
        val archive = VersionedTreeArchive(
            manifest ?: throw IllegalArgumentException("Missing .ftree manifest"),
            title ?: throw IllegalArgumentException("Missing .ftree title"),
            revisions
        )
        validateArchive(archive)
        return archive
    }

    private fun validateArchive(archive: VersionedTreeArchive) {
        validateManifest(archive.manifest)
        require(archive.title.length <= 512) { "Tree title is too long" }
        require(archive.commits.size in 1..MAX_COMMITS) { "Invalid revision count" }
        val commits = archive.commits.associateBy { it.id }
        require(commits.size == archive.commits.size && commits.containsKey(archive.manifest.head)) { "Broken history head" }
        archive.commits.forEach { revision ->
            validateCommit(revision)
            require(revision.parents.all(commits::containsKey)) { "Missing history parent" }
        }
        // Reject cycles, unreachable bogus commits and malformed revisions.
        val state = mutableMapOf<String, Int>()
        fun visit(id: String) {
            require(state[id] != 1) { "Cyclic history" }
            if (state[id] == 2) return
            state[id] = 1
            commits.getValue(id).parents.forEach(::visit)
            state[id] = 2
        }
        visit(archive.manifest.head)
        require(state.size == commits.size) { "History includes unreachable revisions" }
    }

    private fun validateManifest(manifest: HistoryManifest) {
        require(manifest.format == "family-tree-history" && manifest.formatVersion == 1) { "Unsupported .ftree format" }
        require(UUID.fromString(manifest.treeUuid).toString() == manifest.treeUuid) { "Invalid tree UUID" }
        require(hashPattern.matches(manifest.head)) { "Invalid history head" }
    }

    private fun validateCommit(commit: SnapshotCommit) {
        require(hashPattern.matches(commit.id) && expectedId(commit) == commit.id) { "Corrupt .ftree history hash" }
        require(commit.parents.size <= 2 && commit.parents.distinct().size == commit.parents.size &&
                commit.parents.all(hashPattern::matches)) { "Invalid history parents" }
        require(commit.author.length <= 256 && commit.snapshotJson.length <= MAX_ENTRY_BYTES) { "History commit too large" }
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
