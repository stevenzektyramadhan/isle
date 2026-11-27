package io.github.stevenzektyramadhan.isle.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AppUpdater(
    client: OkHttpClient = OkHttpClient(),
) {
    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        version: String,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(apkUrl.startsWith(ALLOWED_DOWNLOAD_PREFIX)) { "Untrusted APK download URL" }
        val updatesDir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        cleanupOldFiles(updatesDir)
        val safeVersion = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val targetFile = File(updatesDir, "isle-$safeVersion.apk")
        val tempFile = File(updatesDir, "isle-$safeVersion.apk.tmp")

        val request = Request.Builder()
            .url(apkUrl)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
            .build()

        var succeeded = false
        try {
            downloadToFile(request, tempFile, onProgress)
            validateApkHeader(tempFile)
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
            }
            succeeded = true
            targetFile
        } finally {
            if (!succeeded) {
                tempFile.delete()
                if (targetFile.exists() && targetFile.length() == 0L) targetFile.delete()
            } else {
                tempFile.delete()
            }
        }
    }

    private suspend fun downloadToFile(
        request: Request,
        destination: File,
        onProgress: (Float?) -> Unit,
    ) {
        val response = executeCall(request)
        response.use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body
            val totalBytes = body.contentLength()
            if (totalBytes > MAX_APK_BYTES) throw IOException("APK exceeds maximum size")
            onProgress(if (totalBytes > 0) 0f else null)

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastReportedBucket = -1
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        downloaded += read
                        if (downloaded > MAX_APK_BYTES) throw IOException("APK exceeds maximum size")
                        output.write(buffer, 0, read)
                        if (totalBytes > 0) {
                            val fraction = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                            val bucket = (fraction * 100).toInt()
                            if (bucket != lastReportedBucket) {
                                lastReportedBucket = bucket
                                onProgress(fraction)
                            }
                        }
                    }
                    output.flush()
                }
            }
            if (destination.length() == 0L) throw IOException("Downloaded APK is empty")
        }
    }

    private suspend fun executeCall(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private fun validateApkHeader(file: File) {
        if (file.length() < 4L) throw IOException("Downloaded file is too small")
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) != 4) throw IOException("Failed to read APK header")
        }
        val isZip = header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
        if (!isZip) throw IOException("Downloaded file is not a valid APK")
    }

    private fun cleanupOldFiles(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val UPDATES_DIR = "updates"
        private const val ALLOWED_DOWNLOAD_PREFIX = "https://github.com/stevenzektyramadhan/isle/releases/download/"
        private const val MAX_APK_BYTES = 256L * 1024L * 1024L

        fun canInstallPackages(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

        fun unknownSourcesIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun installIntent(context: Context, apkFile: File): Intent {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
