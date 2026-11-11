package com.kowsiq.creativescan.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

data class DocItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val modified: Long, // seconds since epoch
    val size: Long,
    val path: String?   // only used on Android 8/9
) {
    val isPdf: Boolean get() = mime == "application/pdf"
}

/**
 * Saves/lists/renames/deletes exported files in Documents/Creative Scan/.
 * Android 10+: MediaStore with RELATIVE_PATH (no storage permission needed).
 * Android 8-9: direct file access (WRITE_EXTERNAL_STORAGE) plus a MediaStore record.
 */
@Suppress("DEPRECATION")
object DocumentRepository {
    const val FOLDER = "Creative Scan"
    const val RELATIVE = "Documents/Creative Scan/"

    private val modern get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun collection(): Uri =
        if (modern) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Files.getContentUri("external")

    private fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        return cleaned.ifBlank { "Scan" }
    }

    /** Writes a new file and returns its content Uri. */
    fun save(ctx: Context, displayName: String, mime: String, writer: (OutputStream) -> Unit): Uri {
        val cr = ctx.contentResolver
        if (modern) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(collection(), values) ?: error("Could not create the file")
            try {
                cr.openOutputStream(uri)?.use { writer(it) } ?: error("Could not open the file")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            return uri
        }

        val dir = legacyDir().apply { mkdirs() }
        val file = uniqueFile(dir, displayName)
        try {
            file.outputStream().use { writer(it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.SIZE, file.length())
        }
        return cr.insert(collection(), values) ?: Uri.fromFile(file)
    }

    fun list(ctx: Context): List<DocItem> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATA
        )
        val selection: String
        val args: Array<String>
        if (modern) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$RELATIVE%")
        } else {
            selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            args = arrayOf(legacyDir().absolutePath + "/%")
        }
        val result = ArrayList<DocItem>()
        try {
            ctx.contentResolver.query(collection(), projection, selection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext()) {
                    val mime = c.getString(iMime) ?: ""
                    if (mime != "application/pdf" && mime != "image/jpeg") continue
                    result.add(
                        DocItem(
                            uri = ContentUris.withAppendedId(collection(), c.getLong(iId)),
                            name = c.getString(iName) ?: "Scan",
                            mime = mime,
                            modified = c.getLong(iDate),
                            size = c.getLong(iSize),
                            path = c.getString(iData)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Storage permission not granted yet (Android 8/9): show nothing.
        }
        return result
    }

    fun rename(ctx: Context, item: DocItem, newBaseName: String): Boolean {
        val ext = item.name.substringAfterLast('.', "")
        val newName = sanitize(newBaseName) + if (ext.isNotEmpty()) ".$ext" else ""
        return try {
            val values = ContentValues()
            if (!modern && item.path != null) {
                val src = File(item.path)
                val target = File(src.parentFile, newName)
                if (target.exists() || !src.renameTo(target)) return false
                values.put(MediaStore.MediaColumns.DATA, target.absolutePath)
            }
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            ctx.contentResolver.update(item.uri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun delete(ctx: Context, item: DocItem): Boolean {
        return try {
            if (!modern && item.path != null) File(item.path).delete()
            ctx.contentResolver.delete(item.uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)" + if (ext.isNotEmpty()) ".$ext" else "")
            i++
        }
        return f
    }
}
