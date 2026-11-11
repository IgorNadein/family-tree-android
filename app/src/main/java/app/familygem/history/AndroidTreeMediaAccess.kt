package app.familygem.history

import android.content.Context
import app.familygem.FileUri
import org.folg.gedcom.model.Media
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class AndroidTreeMediaAccess(private val context: Context) : TreeMediaAccess {
    override fun open(treeId: Int, reference: String): InputStream? {
        if (reference.startsWith("https://") || reference.startsWith("http://")) return null
        val media = Media().apply { file = reference }
        val source = FileUri(context, media, treeId)
        return source.file?.inputStream() ?: source.uri?.let(context.contentResolver::openInputStream)
    }

    override fun fingerprint(treeId: Int, reference: String): MediaFingerprint? {
        val media = Media().apply { file = reference }
        val source = FileUri(context, media, treeId)
        val file = source.file
        if (file != null && file.isFile && file.lastModified() > 0L) {
            return MediaFingerprint(file.absolutePath, file.length(), file.lastModified())
        }
        return null
    }

    override fun hasCheckout(treeId: Int, reference: String): Boolean =
        context.getExternalFilesDir(treeId.toString())?.let {
            VersionedMediaFiles.mappedFile(context.filesDir, it, treeId, reference)?.isFile
        } == true

    override fun checkout(treeId: Int, reference: String, blob: File) {
        val root = context.getExternalFilesDir(treeId.toString())
            ?: throw IOException("Media directory unavailable")
        val destination = VersionedMediaFiles.checkoutFile(root, reference)
        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("checkout-", ".tmp", destination.parentFile)
        try {
            FileInputStream(blob).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("Could not checkout attachment")
        } finally {
            temporary.delete()
        }
    }
}
