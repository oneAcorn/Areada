package app.areada.data.private

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import app.areada.data.DocumentResolver
import app.areada.data.library.LibraryBookEntry
import app.areada.data.library.LibraryFolderEntry
import app.areada.data.library.NaturalSort
import java.io.File

data class PrivateImportResult(
    val movedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

data class PrivateFolderListing(
    val folders: List<LibraryFolderEntry>,
    val books: List<LibraryBookEntry>,
)

data class PrivateFolderDeleteResult(
    val deleted: Boolean,
    val deletedBookUris: List<String>,
)

object PrivateLibraryStore {
    const val DirectoryName = "books"
    private const val ProviderSuffix = ".private_library"
    private const val ContentUriScheme = "content://"
    private const val FolderIdPrefix = "private::"
    private const val DefaultImportFolderName = "imported"
    private const val MaxImportDepth = 18
    private const val MaxUniqueNameAttempts = 1000

    fun libraryDir(context: Context): File = File(context.filesDir, DirectoryName)

    fun authority(context: Context): String = context.packageName + ProviderSuffix

    fun folderId(relativePath: String): String = "$FolderIdPrefix${normalizeRelativePath(relativePath)}"

    fun isPrivateUriString(uriString: String): Boolean {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith(ContentUriScheme)) {
            return false
        }
        val uriAuthority = trimmed.removePrefix(ContentUriScheme)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return uriAuthority.endsWith(ProviderSuffix)
    }

    fun normalizeRelativePath(relativePath: String): String =
        relativePath
            .trim()
            .replace('\\', '/')
            .split('/')
            .map { segment -> segment.trim() }
            .filter { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
            .joinToString(separator = "/")

    fun listFolder(
        context: Context,
        relativePath: String,
    ): PrivateFolderListing {
        val providerAuthority = authority(context)
        val normalizedPath = normalizeRelativePath(relativePath)
        val directory = directoryFor(context, normalizedPath) ?: return PrivateFolderListing(emptyList(), emptyList())
        val entries = directory.listFiles().orEmpty()
        val folders = entries
            .filter { entry -> entry.isDirectory }
            .mapNotNull { entry ->
                val name = entry.name.trim()
                if (name.isBlank()) {
                    return@mapNotNull null
                }
                val folderPath = if (normalizedPath.isBlank()) name else "$normalizedPath/$name"
                LibraryFolderEntry(
                    id = folderId(folderPath),
                    relativePath = folderPath,
                    name = name,
                )
            }
            .sortedWith(NaturalSort.comparator { it.name })
        val books = entries
            .filter { entry -> entry.isFile }
            .mapNotNull { entry -> bookEntry(context, providerAuthority, entry) }
            .sortedWith(NaturalSort.comparator { it.title })
        return PrivateFolderListing(folders = folders, books = books)
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
            ?: return PrivateImportResult(movedCount = 0, skippedCount = 0, failedCount = 0)
        val targetFolder = libraryDir(context)
        if (!targetFolder.exists() && !targetFolder.mkdirs()) {
            return PrivateImportResult(movedCount = 0, skippedCount = 0, failedCount = 0)
        }
        val importFolderName = importedFolderName(sourceFolder, treeUri)

        var movedCount = 0
        var skippedCount = 0
        var failedCount = 0
        collectBookFiles(sourceFolder, relativeDirectory = importFolderName, depth = 0).forEach { source ->
            val name = sanitizeSegment(source.file.name.orEmpty())
            if (name.isBlank()) {
                failedCount += 1
                return@forEach
            }
            val parentFolder = directoryFor(context, source.relativeDirectory)
            if (parentFolder == null || (!parentFolder.isDirectory && !parentFolder.mkdirs())) {
                failedCount += 1
                return@forEach
            }
            val targetFile = resolveTarget(parentFolder, name, source.file.length())
            if (targetFile == null) {
                skippedCount += 1
            } else {
                val copied = runCatching {
                    context.contentResolver.openInputStream(source.file.uri)?.use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
                if (!copied) {
                    targetFile.delete()
                    failedCount += 1
                    return@forEach
                }
                movedCount += 1
            }
            if (!source.file.delete()) {
                failedCount += 1
            }
        }
        return PrivateImportResult(
            movedCount = movedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
        )
    }

    fun fileForUri(
        context: Context,
        uriString: String,
    ): File? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.authority != authority(context)) {
            return null
        }
        val segments = uri.pathSegments
            .orEmpty()
            .drop(1)
            .mapNotNull { segment -> sanitizeSegment(segment).takeIf { name -> name.isNotEmpty() } }
        if (segments.isEmpty()) {
            return null
        }
        val baseFolder = libraryDir(context)
        val file = segments.fold(baseFolder) { parent, segment -> File(parent, segment) }
        return file.takeIf { target -> isInside(baseFolder, target) }
    }

    fun deleteBook(
        context: Context,
        uriString: String,
    ): Boolean {
        val file = fileForUri(context, uriString) ?: return false
        return !file.exists() || file.delete()
    }

    fun deleteFolder(
        context: Context,
        relativePath: String,
    ): PrivateFolderDeleteResult {
        val normalizedPath = normalizeRelativePath(relativePath)
        if (normalizedPath.isBlank()) {
            return PrivateFolderDeleteResult(deleted = false, deletedBookUris = emptyList())
        }
        val directory = directoryFor(context, normalizedPath)
            ?: return PrivateFolderDeleteResult(deleted = false, deletedBookUris = emptyList())
        if (!directory.exists()) {
            return PrivateFolderDeleteResult(deleted = true, deletedBookUris = emptyList())
        }
        val deletedBookUris = collectStoredBookUris(context, directory)
        return PrivateFolderDeleteResult(
            deleted = directory.deleteRecursively(),
            deletedBookUris = deletedBookUris,
        )
    }

    private fun directoryFor(
        context: Context,
        relativePath: String,
    ): File? {
        val baseFolder = libraryDir(context)
        val segments = normalizeRelativePath(relativePath)
            .split('/')
            .filter { segment -> segment.isNotBlank() }
        val directory = segments.fold(baseFolder) { parent, segment -> File(parent, segment) }
        return directory.takeIf { target -> isInside(baseFolder, target) }
    }

    private fun isInside(
        baseFolder: File,
        target: File,
    ): Boolean {
        val basePath = canonicalPathOf(baseFolder).trimEnd(File.separatorChar)
        val targetPath = canonicalPathOf(target)
        return targetPath == basePath || targetPath.startsWith(basePath + File.separator)
    }

    private fun canonicalPathOf(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun importedFolderName(
        sourceFolder: DocumentFile,
        treeUri: Uri,
    ): String {
        val displayName = sanitizeSegment(sourceFolder.name.orEmpty())
        if (displayName.isNotBlank()) {
            return displayName
        }
        val uriSegment = runCatching { treeUri.lastPathSegment }.getOrNull().orEmpty()
        val fallbackName = sanitizeSegment(
            uriSegment
                .substringAfterLast('/')
                .substringAfterLast(':')
                .trim()
        )
        return fallbackName.ifBlank { DefaultImportFolderName }
    }

    private fun sanitizeSegment(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            return ""
        }
        return buildString(trimmed.length) {
            trimmed.forEach { character ->
                append(if (character == '/' || character.isISOControl()) '_' else character)
            }
        }
    }

    private fun resolveTarget(
        parentFolder: File,
        fileName: String,
        sourceLength: Long,
    ): File? {
        val direct = File(parentFolder, fileName)
        if (!direct.exists()) {
            return direct
        }
        if (sourceLength >= 0L && direct.length() == sourceLength) {
            return null
        }
        return uniqueTarget(parentFolder, fileName)
    }

    private fun bookEntry(
        context: Context,
        providerAuthority: String,
        file: File,
    ): LibraryBookEntry? {
        val name = file.name.trim()
        if (name.isBlank()) {
            return null
        }
        val type = DocumentResolver.detectSupportedType(null, name) ?: return null
        val uriString = runCatching {
            FileProvider.getUriForFile(context, providerAuthority, file).toString()
        }.getOrNull() ?: return null
        return LibraryBookEntry(
            id = uriString,
            uriString = uriString,
            fileName = name,
            title = name.substringBeforeLast('.', name).ifBlank { name },
            type = type,
        )
    }

    private fun collectStoredBookUris(
        context: Context,
        directory: File,
    ): List<String> {
        val providerAuthority = authority(context)
        return directory
            .walkTopDown()
            .filter { file -> file.isFile }
            .mapNotNull { file ->
                if (DocumentResolver.detectSupportedType(null, file.name) == null) {
                    return@mapNotNull null
                }
                runCatching {
                    FileProvider.getUriForFile(context, providerAuthority, file).toString()
                }.getOrNull()
            }
            .toList()
    }

    private fun collectBookFiles(
        folder: DocumentFile,
        relativeDirectory: String,
        depth: Int,
    ): List<PendingBookFile> {
        if (depth > MaxImportDepth) {
            return emptyList()
        }
        val entries = runCatching { folder.listFiles() }.getOrElse { return emptyList() }
        val books = mutableListOf<PendingBookFile>()
        val folders = mutableListOf<Pair<DocumentFile, String>>()
        entries.forEach { entry ->
            runCatching {
                val name = sanitizeSegment(entry.name.orEmpty())
                when {
                    name.isBlank() -> Unit
                    entry.isDirectory -> folders += entry to appendSegment(relativeDirectory, name)
                    entry.isFile && DocumentResolver.detectSupportedType(null, name) != null ->
                        books += PendingBookFile(file = entry, relativeDirectory = relativeDirectory)
                }
            }
        }
        folders.forEach { (childFolder, childDirectory) ->
            books += collectBookFiles(childFolder, childDirectory, depth + 1)
        }
        return books
    }

    private fun appendSegment(
        relativeDirectory: String,
        segment: String,
    ): String = if (relativeDirectory.isBlank()) segment else "$relativeDirectory/$segment"

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

    private data class PendingBookFile(
        val file: DocumentFile,
        val relativeDirectory: String,
    )
}