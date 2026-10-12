package app.areada.data.private

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import app.areada.data.DocumentResolver
import app.areada.data.library.LibraryBookEntry
import app.areada.data.library.NaturalSort
import java.io.File

data class PrivateImportResult(
    val movedCount: Int,
    val failedCount: Int,
)

object PrivateLibraryStore {
    const val DirectoryName = "books"
    private const val ProviderSuffix = ".private_library"
    private const val MaxImportDepth = 18
    private const val MaxUniqueNameAttempts = 1000

    fun libraryDir(context: Context): File = File(context.filesDir, DirectoryName)

    fun authority(context: Context): String = context.packageName + ProviderSuffix

    fun listBooks(context: Context): List<LibraryBookEntry> {
        val providerAuthority = authority(context)
        return libraryDir(context)
            .listFiles()
            .orEmpty()
            .filter { file -> file.isFile }
            .mapNotNull { file ->
                val name = file.name.trim()
                if (name.isBlank()) {
                    return@mapNotNull null
                }
                val type = DocumentResolver.detectSupportedType(null, name) ?: return@mapNotNull null
                val uriString = runCatching {
                    FileProvider.getUriForFile(context, providerAuthority, file).toString()
                }.getOrNull() ?: return@mapNotNull null
                LibraryBookEntry(
                    id = uriString,
                    uriString = uriString,
                    fileName = name,
                    title = name.substringBeforeLast('.', name).ifBlank { name },
                    type = type,
                )
            }
            .sortedWith(NaturalSort.comparator { it.title })
    }

    fun takeReadWritePermission(
        context: Context,
        treeUri: Uri,
    ) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun moveFolderBooks(
        context: Context,
        treeUri: Uri,
    ): PrivateImportResult {
        takeReadWritePermission(context, treeUri)
        val sourceFolder = DocumentFile.fromTreeUri(context, treeUri)
            ?: return PrivateImportResult(movedCount = 0, failedCount = 0)
        val targetFolder = libraryDir(context)
        if (!targetFolder.exists()) {
            targetFolder.mkdirs()
        }

        var movedCount = 0
        var failedCount = 0
        collectBookFiles(sourceFolder, depth = 0).forEach { sourceFile ->
            val name = sourceFile.name?.trim().orEmpty()
            if (name.isBlank()) {
                failedCount += 1
                return@forEach
            }
            val targetFile = uniqueTarget(targetFolder, name)
            val copied = runCatching {
                context.contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (!copied) {
                targetFile.delete()
                failedCount += 1
                return@forEach
            }
            if (!sourceFile.delete()) {
                failedCount += 1
                return@forEach
            }
            movedCount += 1
        }
        return PrivateImportResult(movedCount = movedCount, failedCount = failedCount)
    }

    fun fileForUri(
        context: Context,
        uriString: String,
    ): File? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.authority != authority(context)) {
            return null
        }
        val name = uri.lastPathSegment
            ?.trim()
            ?.takeIf { segment -> segment.isNotBlank() && segment == segment.trim('/') }
            ?.takeIf { segment -> segment != "." && segment != ".." && '/' !in segment && '\\' !in segment }
            ?: return null
        return File(libraryDir(context), name)
    }

    fun deleteBook(
        context: Context,
        uriString: String,
    ): Boolean {
        val file = fileForUri(context, uriString) ?: return false
        return !file.exists() || file.delete()
    }

    private fun collectBookFiles(
        folder: DocumentFile,
        depth: Int,
    ): List<DocumentFile> {
        if (depth > MaxImportDepth) {
            return emptyList()
        }
        val entries = runCatching { folder.listFiles() }.getOrElse { return emptyList() }
        val books = mutableListOf<DocumentFile>()
        val folders = mutableListOf<DocumentFile>()
        entries.forEach { entry ->
            runCatching {
                when {
                    entry.isDirectory -> folders += entry
                    entry.isFile && DocumentResolver.detectSupportedType(null, entry.name.orEmpty()) != null ->
                        books += entry
                }
            }
        }
        folders.forEach { childFolder ->
            books += collectBookFiles(childFolder, depth + 1)
        }
        return books
    }

    private fun uniqueTarget(
        folder: File,
        fileName: String,
    ): File {
        var candidate = File(folder, fileName)
        if (!candidate.exists()) {
            return candidate
        }
        val baseName = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
        val extension = fileName.substringAfterLast('.', "")
        val extensionSuffix = if (extension.isEmpty()) "" else ".$extension"
        var index = 2
        while (candidate.exists() && index < MaxUniqueNameAttempts) {
            candidate = File(folder, "$baseName ($index)$extensionSuffix")
            index += 1
        }
        return candidate
    }
}