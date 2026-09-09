package com.weto.booxcal.ink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.File

/** A PDF in the templates folder and how many pages it has. */
data class TemplateFile(val file: File, val pageCount: Int) {
    val name: String get() = file.nameWithoutExtension
}

/** One page of one template PDF: what a new note is created from. */
data class TemplateRef(val path: String, val page: Int) {
    val file: File get() = File(path)
    val name: String get() = file.nameWithoutExtension
}

/**
 * Page templates for new notes.
 *
 * Any PDF dropped in the `noteTemplate` folder of the internal storage is a
 * template; each of its pages is one. A note created from a page carries
 * that page as its background image, and every page added to the note gets
 * the same background.
 *
 * The folder is outside the app, so reading it takes the "all files" access
 * on Android 11+ (a plain read permission before that). Without it the
 * picker offers only the blank sheet and a button to grant it.
 */
object NoteTemplates {

    const val FOLDER_NAME = "noteTemplate"

    val dir: File get() = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)

    /** Whether the app may read the templates folder. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** The PDFs in the folder, by name, with their page counts. Empty if the folder is missing or unreadable. */
    fun list(): List<TemplateFile> {
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.mapNotNull { file ->
            val pages = runCatching { open(file) { it.pageCount } }.getOrNull() ?: return@mapNotNull null
            if (pages > 0) TemplateFile(file, pages) else null
        }
    }

    /** A small rendering of a page for the picker; null if the PDF cannot be read. */
    fun thumbnail(ref: TemplateRef, widthPx: Int): Bitmap? =
        runCatching { render(ref, widthPx) }.getOrNull()

    /**
     * The page as a note background: rendered at the width the app uses for
     * imported pages, saved in the notes storage, and wrapped in an empty
     * page of the same proportions. Null if it cannot be rendered or stored.
     */
    fun pageDocument(ref: TemplateRef): InkDocument? {
        val bitmap = runCatching { render(ref, BACKGROUND_WIDTH_PX) }.getOrNull() ?: return null
        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
        val name = try {
            NoteStorage.saveImage(bitmap)
        } finally {
            bitmap.recycle()
        } ?: return null
        return InkDocument(
            canvasWidth = NotePdf.IMPORT_CANVAS_WIDTH,
            canvasHeight = NotePdf.IMPORT_CANVAS_WIDTH * aspect,
            background = name,
        )
    }

    private fun render(ref: TemplateRef, widthPx: Int): Bitmap = open(ref.file) { renderer ->
        renderer.openPage(ref.page).use { page ->
            val width = widthPx.coerceAtLeast(1)
            val height = (width * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt()
                .coerceIn(1, MAX_HEIGHT_PX)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    private inline fun <T> open(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val renderer = PdfRenderer(descriptor)
            try {
                block(renderer)
            } finally {
                renderer.close()
            }
        }

    /** Same width as an imported page: room to zoom in while writing on it. */
    private const val BACKGROUND_WIDTH_PX = 1860
    private const val MAX_HEIGHT_PX = 9000
}
