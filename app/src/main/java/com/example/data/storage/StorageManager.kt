package com.example.data.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.domain.model.AppSettings
import com.example.domain.model.StorageInfo
import com.example.domain.model.VideoQualityTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class CommittedFileResult(
    val uriString: String,
    val displayPath: String,
    val finalFileName: String,
    val finalBytes: Long,
    val sha256: String,
    val detectedHeight: Int?,
    val detectedDurationMs: Long?
)

@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val stagingDir: File
        get() = File(context.filesDir, "staging").apply { if (!exists()) mkdirs() }

    fun getStagingFile(downloadId: String): File = File(stagingDir, "$downloadId.part")

    fun getYtDlpWorkingDirectory(downloadId: String): File = File(context.filesDir, "yt-dlp-$downloadId")

    fun deleteYtDlpWorkingFiles(downloadId: String) {
        getYtDlpWorkingDirectory(downloadId).deleteRecursively()
    }

    fun getYtDlpDownloadedBytes(downloadId: String): Long {
        val directory = getYtDlpWorkingDirectory(downloadId)
        if (!directory.exists()) return 0L
        val mediaExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flv")
        return directory.walkTopDown().filter { file ->
            file.isFile && (file.name.contains(".part", ignoreCase = true) || file.extension.lowercase() in mediaExtensions)
        }.sumOf { it.length() }
    }

    fun deleteStagingFile(downloadId: String) {
        val file = getStagingFile(downloadId)
        if (file.exists()) file.delete()
    }

    fun getStorageInfo(appDownloadsBytes: Long): StorageInfo {
        val stat = StatFs(context.filesDir.absolutePath)
        return StorageInfo(
            availableBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            appDownloadsBytes = appDownloadsBytes
        )
    }

    fun getTempCacheBytes(): Long {
        return context.cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    fun clearTempCache(): Long {
        val before = getTempCacheBytes()
        context.cacheDir.listFiles()?.forEach { child ->
            // Preserve youtubedl-android binary runtime directories if stored in cache; clean temp files/image cache
            if (child.name != "youtubedl-android") {
                child.deleteRecursively()
            }
        }
        val after = getTempCacheBytes()
        return (before - after).coerceAtLeast(0L)
    }

    fun hasEnoughStorage(requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        val stat = StatFs(context.filesDir.absolutePath)
        val safetyMargin = 10L * 1024L * 1024L
        return stat.availableBytes > requiredBytes + safetyMargin
    }

    suspend fun commitDownloadedFile(
        stagingFile: File,
        fileName: String,
        mimeType: String,
        resolutionLabel: String,
        resolutionHeight: Int?,
        isAudioOnly: Boolean,
        settings: AppSettings
    ): CommittedFileResult = withContext(Dispatchers.IO) {
        val sha256 = computeSha256(stagingFile)
        val probe = if (!isAudioOnly) inspectLocalVideo(stagingFile) else inspectLocalAudio(stagingFile)
        val actualHeight = probe.height ?: resolutionHeight
        val resolvedLabel = when {
            isAudioOnly -> resolutionLabel
            actualHeight != null -> VideoQualityTier.formatLabelFromHeight(actualHeight)
            else -> resolutionLabel
        }

        val subfolderName = if (settings.organizeByQuality) {
            if (isAudioOnly) "Audio" else resolvedLabel.replace(" ", "_")
        } else {
            null
        }

        // 1. Try custom SAF folder if configured
        if (!settings.customTreeUri.isNullOrBlank()) {
            val safResult = runCatching {
                commitToSafTree(
                    stagingFile = stagingFile,
                    treeUriString = settings.customTreeUri,
                    subfolderName = subfolderName,
                    fileName = fileName,
                    mimeType = mimeType
                )
            }.getOrNull()
            if (safResult != null) {
                val safFileSize = stagingFile.length()
                stagingFile.delete()
                return@withContext CommittedFileResult(
                    uriString = safResult.first,
                    displayPath = safResult.second,
                    finalFileName = safResult.third,
                    finalBytes = safFileSize.takeIf { it > 0 } ?: safResultLength(safResult.first),
                    sha256 = sha256,
                    detectedHeight = actualHeight,
                    detectedDurationMs = probe.durationMs
                )
            }
        }

        val fileSize = stagingFile.length()

        // 2. Try MediaStore.Downloads on Android 10+ (zero permissions required)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreResult = runCatching {
                commitToMediaStore(
                    stagingFile = stagingFile,
                    subfolderName = subfolderName,
                    fileName = fileName,
                    mimeType = mimeType
                )
            }.getOrNull()
            if (mediaStoreResult != null) {
                stagingFile.delete()
                return@withContext CommittedFileResult(
                    uriString = mediaStoreResult.first,
                    displayPath = mediaStoreResult.second,
                    finalFileName = mediaStoreResult.third,
                    finalBytes = fileSize,
                    sha256 = sha256,
                    detectedHeight = actualHeight,
                    detectedDurationMs = probe.durationMs
                )
            }
        }

        // 3. Fallback to app-scoped external Downloads directory + FileProvider URI
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val targetDir = if (subfolderName != null) {
            File(baseDir, "StreamVault/$subfolderName")
        } else {
            File(baseDir, "StreamVault")
        }
        if (!targetDir.exists()) targetDir.mkdirs()

        val uniqueFile = createUniqueLocalFile(targetDir, fileName)
        FileInputStream(stagingFile).use { input ->
            uniqueFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        stagingFile.delete()

        val contentUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", uniqueFile)
        }.getOrElse { Uri.fromFile(uniqueFile) }

        CommittedFileResult(
            uriString = contentUri.toString(),
            displayPath = uniqueFile.absolutePath,
            finalFileName = uniqueFile.name,
            finalBytes = fileSize,
            sha256 = sha256,
            detectedHeight = actualHeight,
            detectedDurationMs = probe.durationMs
        )
    }

    private fun commitToSafTree(
        stagingFile: File,
        treeUriString: String,
        subfolderName: String?,
        fileName: String,
        mimeType: String
    ): Triple<String, String, String>? {
        val treeUri = Uri.parse(treeUriString)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!rootDoc.canWrite()) return null

        val targetDir = if (!subfolderName.isNullOrBlank()) {
            rootDoc.findFile(subfolderName)?.takeIf { it.isDirectory }
                ?: rootDoc.createDirectory(subfolderName)
                ?: rootDoc
        } else {
            rootDoc
        }

        val uniqueName = resolveUniqueSafName(targetDir, fileName)
        val created = targetDir.createFile(mimeType, uniqueName) ?: return null
        context.contentResolver.openOutputStream(created.uri)?.use { out ->
            FileInputStream(stagingFile).use { input ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }
        } ?: return null

        val pathLabel = if (subfolderName != null) {
            "${rootDoc.name ?: "Custom Folder"}/$subfolderName/$uniqueName"
        } else {
            "${rootDoc.name ?: "Custom Folder"}/$uniqueName"
        }
        return Triple(created.uri.toString(), pathLabel, uniqueName)
    }

    private fun commitToMediaStore(
        stagingFile: File,
        subfolderName: String?,
        fileName: String,
        mimeType: String
    ): Triple<String, String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val relativePath = if (subfolderName != null) {
            "${Environment.DIRECTORY_DOWNLOADS}/StreamVault/$subfolderName"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/StreamVault"
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(stagingFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalStateException("Unable to open MediaStore output stream")

            val publishValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, publishValues, null, null)
            Triple(uri.toString(), "$relativePath/$fileName", fileName)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun deleteMediaFile(localUri: String?, localPath: String?): Boolean {
        if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.isAbsolute && f.exists() && f.delete() && !f.exists()) return true
        }
        if (!localUri.isNullOrBlank()) {
            val uri = Uri.parse(localUri)
            val deleted = when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    val resolverDeleted = runCatching {
                        context.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                    if (resolverDeleted) true else runCatching {
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        doc?.let { !it.exists() || it.delete() } == true
                    }.getOrDefault(false)
                }
                ContentResolver.SCHEME_FILE -> {
                    uri.path?.let { path ->
                        val file = File(path)
                        !file.exists() || (file.delete() && !file.exists())
                    } ?: false
                }
                else -> false
            }
            if (deleted) return true
        }
        return localUri.isNullOrBlank() && (localPath.isNullOrBlank() || !File(localPath).exists())
    }

    fun fileExists(localUri: String?, localPath: String?): Boolean {
        if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.exists() && f.length() > 0) return true
        }
        if (!localUri.isNullOrBlank()) {
            val uri = Uri.parse(localUri)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                return runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
            }
        }
        return false
    }

    fun createShareIntent(localUri: String?, localPath: String?, mimeType: String, title: String): Intent? {
        val uri = resolvePlayableUri(localUri, localPath) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createOpenLocationIntent(localUri: String?): Intent {
        val downloadsUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        val targetUri = localUri?.let { Uri.parse(it) }
        return Intent(Intent.ACTION_VIEW).apply {
            if (targetUri != null && DocumentsContract.isDocumentUri(context, targetUri)) {
                setDataAndType(targetUri, DocumentsContract.Document.MIME_TYPE_DIR)
            } else {
                setDataAndType(downloadsUri, "vnd.android.document/directory")
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun resolvePlayableUri(localUri: String?, localPath: String?): Uri? {
        if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.exists()) {
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                }.getOrElse { Uri.fromFile(f) }
            }
        }
        return localUri?.let { Uri.parse(it) }
    }

    private fun safResultLength(uriStr: String): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    private fun createUniqueLocalFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_($counter).$ext")
            counter++
        }
        return candidate
    }

    private fun resolveUniqueSafName(dir: DocumentFile, fileName: String): String {
        if (dir.findFile(fileName) == null) return fileName
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "mp4")
        var counter = 1
        while (counter < 100) {
            val candidate = "${base}_($counter).$ext"
            if (dir.findFile(candidate) == null) return candidate
            counter++
        }
        return "${base}_${System.currentTimeMillis()}.$ext"
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun inspectLocalVideo(file: File): LocalMediaMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            LocalMediaMeta(height = h, durationMs = d)
        } catch (_: Exception) {
            LocalMediaMeta(null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun inspectLocalAudio(file: File): LocalMediaMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            LocalMediaMeta(height = null, durationMs = d)
        } catch (_: Exception) {
            LocalMediaMeta(null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class LocalMediaMeta(val height: Int?, val durationMs: Long?)
}
