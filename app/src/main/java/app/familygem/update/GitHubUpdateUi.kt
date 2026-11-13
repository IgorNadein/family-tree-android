package app.familygem.update

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.familygem.BuildConfig
import app.familygem.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in, signed app updates from official GitHub Releases. Never installs silently. */
object GitHubUpdateUi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    private const val CERT_SHA256 =
        "8c5825974bf2f11b994afd165b74e9a968ef47a84429f11e72085e99d0910fbc"
    private const val MAX_APK_BYTES = 300L * 1024 * 1024

    @JvmStatic
    fun check(activity: AppCompatActivity, button: Button, status: TextView) {
        button.isEnabled = false
        status.visibility = View.VISIBLE
        status.setText(R.string.github_update_checking)
        activity.lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) {
                    GitHubUpdatePolicy.parseLatest(
                        requestText(GitHubUpdatePolicy.API_URL), BuildConfig.VERSION_NAME)
                }
                if (update == null) {
                    status.setText(R.string.github_update_current)
                } else {
                    status.text = activity.getString(R.string.github_update_available, update.version)
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.github_update_dialog_title)
                        .setMessage(activity.getString(R.string.github_update_dialog_body,
                            update.version, BuildConfig.VERSION_NAME) +
                            (if (update.notes.isBlank()) "" else "\n\n" + update.notes))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.github_update_download) { _, _ ->
                            downloadAndInstall(activity, button, status, update)
                        }
                        .show()
                }
            } catch (error: Exception) {
                status.text = activity.getString(R.string.github_update_error,
                    error.localizedMessage ?: error.javaClass.simpleName)
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun downloadAndInstall(
        activity: AppCompatActivity, button: Button, status: TextView, release: GitHubRelease
    ) {
        button.isEnabled = false
        status.setText(R.string.github_update_downloading)
        activity.lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val expectedHash = GitHubUpdatePolicy.parseChecksum(
                        requestText(release.sha256Url)
                    ) ?: throw IOException("Invalid release checksum")
                    downloadVerified(activity, release, expectedHash).also {
                        verifyPackage(activity, it, release.version)
                    }
                }
                button.setText(R.string.github_update_install)
                button.setOnClickListener { requestInstall(activity, apk, status) }
                button.isEnabled = true
                status.setText(R.string.github_update_ready)
                requestInstall(activity, apk, status)
            } catch (error: Exception) {
                button.setText(R.string.github_update_check)
                button.setOnClickListener { check(activity, button, status) }
                button.isEnabled = true
                status.text = activity.getString(R.string.github_update_error,
                    error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

    private fun requestText(url: String): String {
        require(url.startsWith("https://")) { "HTTPS is required" }
        val request = Request.Builder().url(url)
            .header("User-Agent", "FamilyTree-Android-Updater")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 && url == GitHubUpdatePolicy.API_URL)
                throw IOException("No published GitHub releases yet")
            if (!response.isSuccessful) throw IOException("GitHub HTTP " + response.code)
            return response.body?.string() ?: throw IOException("Empty server response")
        }
    }

    private fun downloadVerified(
        activity: AppCompatActivity, release: GitHubRelease, expectedHash: String
    ): File {
        val folder = File(activity.cacheDir, "updates")
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Cannot create update cache")
        val target = File(folder, GitHubUpdatePolicy.APK_NAME)
        val temporary = File(folder, "download.part")
        temporary.delete()
        try {
            val request = Request.Builder().url(release.apkUrl)
                .header("User-Agent", "FamilyTree-Android-Updater").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("APK download HTTP " + response.code)
                val body = response.body ?: throw IOException("Missing APK")
                val hash = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            bytes += count
                            if (bytes > MAX_APK_BYTES) throw IOException("APK exceeds maximum size")
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (release.apkSize > 0 && release.apkSize != bytes)
                    throw IOException("APK size mismatch")
                val actual = hash.digest().joinToString("") { "%02x".format(it) }
                if (actual != expectedHash) throw IOException("APK SHA-256 mismatch")
            }
            target.delete()
            if (!temporary.renameTo(target)) throw IOException("Could not save APK")
            return target
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(manager: PackageManager, file: File?): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val result = if (file == null) {
            manager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        } else {
            manager.getPackageArchiveInfo(file.absolutePath, flags)
        }
        return result ?: throw IOException("Cannot inspect APK package")
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(activity: AppCompatActivity, apk: File, expectedVersion: String) {
        val manager = activity.packageManager
        val installed = packageInfo(manager, null)
        val candidate = packageInfo(manager, apk)
        if (candidate.packageName != BuildConfig.APPLICATION_ID)
            throw IOException("APK is for a different application")
        if (candidate.versionName != expectedVersion)
            throw IOException("Downloaded APK version does not match GitHub release")
        val nextCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode
            else candidate.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode
            else installed.versionCode.toLong()
        if (nextCode <= currentCode) throw IOException("APK is not a newer version")
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28)
                info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
            return signatures.map { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val installedSigners = signers(installed)
        val apkSigners = signers(candidate)
        if (installedSigners != setOf(CERT_SHA256) || apkSigners != installedSigners)
            throw IOException("Update signing certificate does not match installed app")
    }

    private fun requestInstall(activity: AppCompatActivity, apk: File, status: TextView) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                status.setText(R.string.github_update_allow_install)
                AlertDialog.Builder(activity)
                    .setTitle(R.string.github_update_permission_title)
                    .setMessage(R.string.github_update_permission_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.github_update_permission_settings) { _, _ ->
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.packageName)))
                    }.show()
                return
            }
            val uri = FileProvider.getUriForFile(activity,
                BuildConfig.APPLICATION_ID + ".provider", apk)
            val installer = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            installer.clipData = ClipData.newUri(activity.contentResolver, "Update APK", uri)
            activity.startActivity(installer)
        } catch (error: Exception) {
            status.text = activity.getString(R.string.github_update_error,
                error.localizedMessage ?: error.javaClass.simpleName)
        }
    }
}
