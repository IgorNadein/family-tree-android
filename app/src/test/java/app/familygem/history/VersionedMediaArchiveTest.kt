package app.familygem.history

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.parser.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class VersionedMediaArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeAccess(private val root: File) : TreeMediaAccess {
        private val files = mutableMapOf<Pair<Int, String>, File>()
        fun register(tree: Int, ref: String, file: File) { files[tree to ref] = file }
        fun put(tree: Int, ref: String, bytes: ByteArray) {
            val file = File(root, "original_" + files.size)
            file.writeBytes(bytes)
            register(tree, ref, file)
        }
        override fun open(treeId: Int, reference: String): InputStream? =
            files[treeId to reference]?.takeIf { it.isFile }?.inputStream()

        override fun checkout(treeId: Int, reference: String, blob: File) {
            val target = VersionedMediaFiles.checkoutFile(File(root, treeId.toString()), reference)
            target.parentFile!!.mkdirs()
            blob.copyTo(target, overwrite = true)
            register(treeId, reference, target)
        }
        fun get(treeId: Int, reference: String): ByteArray = files.getValue(treeId to reference).readBytes()
        fun getLength(treeId: Int, reference: String): Long = files.getValue(treeId to reference).length()
    }

    private fun tree(vararg references: String): String {
        val gedcom = Gedcom()
        references.forEachIndexed { index, path ->
            gedcom.addMedia(Media().apply {
                id = "M" + (index + 1)
                file = path
                fileTag = "FILE"
            })
        }
        gedcom.createIndexes()
        return JsonParser().toJson(gedcom)
    }

    private fun export(store: VersionedTreeStore, treeId: Int, json: String): ByteArray {
        val output = ByteArrayOutputStream()
        runBlocking { store.export(treeId, "Семья", json, output) }
        return output.toByteArray()
    }

    @Test fun roundTripPreservesDifferentFilesWithSameBasename() = runBlocking {
        val sourceAccess = FakeAccess(temp.newFolder("source-media"))
        val source = VersionedTreeStore(temp.newFolder("source"), sourceAccess)
        val first = "parent1/photo.jpg"
        val second = "parent2/photo.jpg"
        sourceAccess.put(1, first, "mother".toByteArray())
        sourceAccess.put(1, second, "father".toByteArray())
        val snapshot = tree(first, second)
        val archive = source.inspect(ByteArrayInputStream(export(source, 1, snapshot)))
        assertEquals(2, archive.headCommit().media!!.size)
        assertEquals(2, archive.blobFolder!!.listFiles()!!.size)

        val destDir = temp.newFolder("destination")
        val destAccess = FakeAccess(temp.newFolder("dest-media"))
        val dest = VersionedTreeStore(destDir, destAccess)
        dest.importNew(13, archive)
        assertArrayEquals("mother".toByteArray(), destAccess.get(13, first))
        assertArrayEquals("father".toByteArray(), destAccess.get(13, second))
        assertEquals(snapshot, File(destDir, "13.json").readText())
        assertEquals(2, VersionedMediaFiles.current(destDir, 13).size)
        assertNotEquals(VersionedMediaFiles.filename(first), VersionedMediaFiles.filename(second))
        assertEquals(archive.headCommit().media, dest.inspect(ByteArrayInputStream(export(dest, 13, snapshot))).headCommit().media)
        archive.release()
    }

    @Test fun identicalBinaryDataIsStoredOnlyOnce() = runBlocking {
        val media = FakeAccess(temp.newFolder("dedup-media"))
        val store = VersionedTreeStore(temp.newFolder("dedup-tree"), media)
        media.put(1, "first/photo.jpg", "same pixels".toByteArray())
        media.put(1, "second/photo.jpg", "same pixels".toByteArray())
        val archive = store.inspect(ByteArrayInputStream(
            export(store, 1, tree("first/photo.jpg", "second/photo.jpg"))))
        assertEquals(2, archive.headCommit().media!!.size)
        assertEquals(1, archive.blobFolder!!.listFiles()!!.size)
        archive.release()
    }

    @Test fun windowsBackslashReferencesRemainResolvable() = runBlocking {
        val ref = """C:\Old Photos\portrait.jpg"""
        val normalized = "C:/Old Photos/portrait.jpg"
        val media = FakeAccess(temp.newFolder("win-media"))
        val store = VersionedTreeStore(temp.newFolder("win-source"), media)
        media.put(1, normalized, "portrait pixels".toByteArray())
        val archive = store.inspect(ByteArrayInputStream(export(store, 1, tree(ref))))
        assertTrue(archive.headCommit().media!!.containsKey(normalized))
        val targetMedia = FakeAccess(temp.newFolder("win-target-media"))
        VersionedTreeStore(temp.newFolder("win-target"), targetMedia).importNew(2, archive)
        assertArrayEquals("portrait pixels".toByteArray(), targetMedia.get(2, normalized))
        archive.release()
    }

    @Test fun inaccessibleLocalMediaNeverProducesSilentIncompleteArchive() {
        val store = VersionedTreeStore(temp.newFolder("missing-source"),
            FakeAccess(temp.newFolder("missing-media")))
        try {
            export(store, 1, tree("missing/photo.jpg"))
            fail("Missing file must fail export")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("cannot be read"))
        }
    }

    @Test fun replacingBinaryWithoutChangingGedcomCreatesNewCommit() = runBlocking {
        val media = FakeAccess(temp.newFolder("media"))
        val store = VersionedTreeStore(temp.newFolder("data"), media)
        val ref = "photo.jpg"
        media.put(1, ref, "original".toByteArray())
        val json = tree(ref)
        val first = store.record(1, json)
        media.put(1, ref, "updated".toByteArray())
        val second = store.record(1, json)
        assertNotEquals(first.head, second.head)
        assertEquals(2, store.history(1).size)
        val archive = store.inspect(ByteArrayInputStream(export(store, 1, json)))
        assertEquals(2, archive.blobFolder!!.listFiles()!!.size)
        archive.release()
    }

    @Test fun concurrentBinaryEditsNeedExplicitConflictResolution() = runBlocking {
        val ref = "photo.jpg"
        val json = tree(ref)
        val aAccess = FakeAccess(temp.newFolder("a-media"))
        val bAccess = FakeAccess(temp.newFolder("b-media"))
        val aDir = temp.newFolder("a")
        val a = VersionedTreeStore(aDir, aAccess)
        val b = VersionedTreeStore(temp.newFolder("b"), bAccess)
        File(aDir, "1.json").writeText(json)
        aAccess.put(1, ref, "base".toByteArray())
        val original = a.inspect(ByteArrayInputStream(export(a, 1, json)))
        b.importNew(4, original)
        original.release()
        aAccess.put(1, ref, "mine".toByteArray())
        bAccess.put(4, ref, "theirs".toByteArray())
        a.record(1, json)
        b.record(4, json)
        val incoming = a.inspect(ByteArrayInputStream(export(b, 4, json)))
        val preview = a.planMerge(1, json, incoming)
        assertTrue(preview.conflicts.any { it.key == "attachments/" + ref })
        try {
            a.applyMerge(preview, emptyMap())
            fail("Missing decision was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("conflicts"))
        }
        a.applyMerge(preview, mapOf("attachments/" + ref to MergeChoice.INCOMING))
        assertArrayEquals("theirs".toByteArray(), aAccess.get(1, ref))
        assertEquals(2, a.history(1).first().parents.size)
        incoming.release()
    }

    @Test fun rejectsCorruptBinaryPayloadBeforeImport() = runBlocking {
        val ref = "photo.jpg"
        val media = FakeAccess(temp.newFolder("media"))
        val store = VersionedTreeStore(temp.newFolder("original"), media)
        media.put(1, ref, "trusted".toByteArray())
        val bytes = export(store, 1, tree(ref))
        val tampered = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
            ZipOutputStream(tampered).use { destination ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(if (entry.name.startsWith("blobs/")) "hacked".toByteArray()
                        else source.readBytes())
                    destination.closeEntry()
                    source.closeEntry()
                }
            }
        }
        try {
            store.inspect(ByteArrayInputStream(tampered.toByteArray()))
            fail("Corrupt content was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Corrupt attachment"))
        }
    }

    @Test fun largeBinaryHasNoFormer64MibLimit() = runBlocking {
        val ref = "movies/family.mp4"
        val media = FakeAccess(temp.newFolder("media"))
        val store = VersionedTreeStore(temp.newFolder("data"), media)
        val file = temp.newFile("large-movie.mp4")
        file.outputStream().buffered().use { out ->
            val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
            repeat(70) { out.write(chunk) }
        }
        media.register(1, ref, file)
        val json = tree(ref)
        val archiveFile = temp.newFile("large.ftree")
        archiveFile.outputStream().use { store.export(1, "Large", json, it) }
        val incoming = archiveFile.inputStream().use { store.inspect(it) }
        assertEquals(file.length(), incoming.blobFolder!!.listFiles()!!.single().length())
        val targetAccess = FakeAccess(temp.newFolder("other-media"))
        val target = VersionedTreeStore(temp.newFolder("other-data"), targetAccess)
        target.importNew(9, incoming)
        assertEquals(70L * 1024 * 1024, targetAccess.getLength(9, ref))
        incoming.release()
    }

    @Test fun moreThan1024RevisionsHaveNoLegacyLimit() = runBlocking {
        val dir = temp.newFolder("long-history")
        val store = VersionedTreeStore(dir)
        var snapshot = tree()
        repeat(1030) { revision ->
            snapshot = tree("revision-" + revision + ".txt")
            store.record(1, snapshot)
        }
        val archive = store.inspect(ByteArrayInputStream(export(store, 1, snapshot)))
        assertEquals(1030, archive.commits.size)
        assertEquals(2, archive.manifest.formatVersion)
        archive.release()
    }

    @Test fun legacyV1ArchiveIsStillReadable() = runBlocking {
        val store = VersionedTreeStore(temp.newFolder("legacy"))
        val json = tree()
        val parents = emptyList<String>()
        val timestamp = 12345L
        val author = "Local device"
        data class Body(val parents: List<String>, val createdAtMillis: Long,
            val author: String, val snapshotJson: String)
        val body = Gson().toJson(Body(parents, timestamp, author, json))
        val id = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val commit = SnapshotCommit(parents, timestamp, author, json, id)
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write(Gson().toJson(HistoryManifest(treeUuid = java.util.UUID.randomUUID().toString(),
                head = id)).toByteArray())
            it.closeEntry()
            it.putNextEntry(ZipEntry("title.json"))
            it.write(Gson().toJson("Legacy").toByteArray())
            it.closeEntry()
            it.putNextEntry(ZipEntry("commits/" + id + ".json"))
            it.write(Gson().toJson(commit).toByteArray())
            it.closeEntry()
        }
        val archive = store.inspect(ByteArrayInputStream(zip.toByteArray()))
        assertEquals(1, archive.manifest.formatVersion)
        store.importNew(5, archive)
        assertEquals(json, File(temp.root, "legacy/5.json").readText())
    }
}
