package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RequiresApi(Build.VERSION_CODES.O)
class LocalPDF : HttpSource(), ConfigurableSource {

    override val name = "Local PDF"
    override val lang = "all"
    override val supportsLatest = false
    override val baseUrl = ""

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val preferences: SharedPreferences by getPreferencesLazy()
    private val contentResolver: ContentResolver = context.contentResolver

    private val inputUri: Uri? get() = preferences.getString("INPUT_URI", null)?.let(Uri::parse)
    private val outputUri: Uri? get() = preferences.getString("OUTPUT_URI", null)?.let(Uri::parse)

    suspend fun getPopularManga(page: Int): MangasPage {
        val root = inputUri ?: return MangasPage(emptyList(), false)
        val seriesDirs = SAFFileUtils.listFiles(context, root).filter {
            SAFFileUtils.isDirectory(context, it)
        }

        val mangaList = seriesDirs.map { dirUri ->
            SManga.create().apply {
                title = SAFFileUtils.getFileName(context, dirUri) ?: "???"
                url = dirUri.toString()
            }
        }

        return MangasPage(mangaList, hasNextPage = false)
    }

    suspend fun getMangaDetails(manga: SManga): SManga {
        manga.description = "This manga is generated from PDF"
        manga.status = SManga.COMPLETED
        return manga
    }

    suspend fun getChapterList(manga: SManga): List<SChapter> {
        val mangaUri = Uri.parse(manga.url)
        val children = SAFFileUtils.listFiles(context, mangaUri)

        return children.filter {
            SAFFileUtils.getFileName(context, it)?.endsWith(".pdf", ignoreCase = true) == true
        }.map { pdfUri ->
            SChapter.create().apply {
                name = SAFFileUtils.getFileName(context, pdfUri)?.removeSuffix(".pdf") ?: "Chapter"
                url = pdfUri.toString()
            }
        }
    }

    suspend fun getPageList(chapter: SChapter): List<Page> {
        handler.post {
            Toast.makeText(context, "Converting pages...", Toast.LENGTH_SHORT).show()
        }

        val chapterUri = Uri.parse(chapter.url)
        val chapterName = SAFFileUtils.getFileName(context, chapterUri)?.removeSuffix(".pdf") ?: "chapter"
        val parentUri = SAFFileUtils.getParentUri(chapterUri) ?: return emptyList()
        val parentName = SAFFileUtils.getFileName(context, parentUri) ?: "UnknownManga"

        val outputRoot = outputUri ?: return emptyList()
        val outputMangaUri = SAFFileUtils.findFile(context, outputRoot, parentName)
            ?: SAFFileUtils.createDirectory(context, outputRoot, parentName)
            ?: return emptyList()

        val zipUri = SAFFileUtils.findFile(context, outputMangaUri, "$chapterName.cbz")
            ?: SAFFileUtils.createFile(context, outputMangaUri, "application/zip", chapterName)
            ?: return emptyList()

        contentResolver.openInputStream(chapterUri)?.use { inputStream ->
            contentResolver.openOutputStream(zipUri)?.use { outputStream ->
                convertPdfToZip(inputStream, outputStream)
            }
        }

        handler.post {
            Toast.makeText(context, "Ready! You can start reading now", Toast.LENGTH_SHORT).show()
        }

        return emptyList()
    }

    private fun convertPdfToZip(inputStream: InputStream, outputStream: OutputStream) {
        val tempPdf = File.createTempFile("temp", ".pdf", context.cacheDir).apply {
            deleteOnExit()
            outputStream().use { it.write(inputStream.readBytes()) }
        }

        val descriptor = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)

        ZipOutputStream(outputStream).use { zipOut ->
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = 2
                val width = page.width * scale
                val height = page.height * scale
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val paddedIndex = String.format("%03d", i)
                val imageEntryName = "page$paddedIndex.jpg"
                val byteStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteStream)

                zipOut.putNextEntry(ZipEntry(imageEntryName))
                zipOut.write(byteStream.toByteArray())
                zipOut.closeEntry()
            }
        }

        renderer.close()
        descriptor.close()
        tempPdf.delete()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "INPUT_URI"
            title = "Folder storing series in PDF format"
            dialogTitle = "Suggested: $DEFAULT_INPUT_DIR"
            summary = preferences.getString(key, null) ?: "⚠️ Not selected"
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue as String
                preference.summary = value
                Toast.makeText(context, "Restart app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
            setOnPreferenceClickListener {
                val intent = Intent().apply {
                    setClassName(EXTENSION_PACKAGE_NAME, PICKER_ACTIVITY)
                    putExtra("action", "safRequest")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INFO_INPUT_DIR"
            title = ""
            summary = """
                This source uses Storage Access Framework.
                Please grant folder access in app settings.

                Folder structure example:
                /Mihon/localpdf/
                  ├── seriesName1/
                  │   ├── ch1.pdf
                  │   └── ch2.pdf
                  ├── seriesName2/
            """.trimIndent()
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "OUTPUT_URI"
            title = "Mihon download folder (as in Settings » Data & Storage)"
            dialogTitle = "Should end with `.../downloads/Local PDF (ALL)` and be in Mihon directory"
            summary = preferences.getString(key, null) ?: "⚠️ Not selected"
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue as String
                return@setOnPreferenceChangeListener if (value.endsWith("downloads/Local PDF (ALL)")) {
                    preference.summary = value
                    Toast.makeText(context, "Restart app to apply changes", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(screen.context, "Path must end with `downloads/Local PDF (ALL)`", Toast.LENGTH_LONG).show()
                    false
                }
            }
            setOnPreferenceClickListener {
                val intent = Intent().apply {
                    setClassName(EXTENSION_PACKAGE_NAME, PICKER_ACTIVITY)
                    putExtra("action", "safRequest")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        }.also(screen::addPreference)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException("Not Used")
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException("Not Used")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Not Used")
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not Used")
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not Used")

    companion object {
        const val DEFAULT_INPUT_DIR = "/storage/emulated/0/Mihon/localpdf"
        const val EXTENSION_PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.localpdf"
        const val PICKER_ACTIVITY = "eu.kanade.tachiyomi.extension.all.localpdf.SAFPickerActivity"
    }
}

object SAFFileUtils {

    fun listFiles(context: Context, uri: Uri): List<Uri> {
        val children = mutableListOf<Uri>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            DocumentsContract.getDocumentId(uri),
        )

        context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                children.add(childUri)
            }
        }

        return children
    }

    fun getFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    fun isDirectory(context: Context, uri: Uri): Boolean {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (cursor.moveToFirst()) DocumentsContract.Document.MIME_TYPE_DIR == cursor.getString(index) else false
        } ?: false
    }

    fun findFile(context: Context, parentUri: Uri, name: String): Uri? {
        return listFiles(context, parentUri).firstOrNull {
            getFileName(context, it) == name
        }
    }

    fun createFile(context: Context, parentUri: Uri, mimeType: String, name: String): Uri? {
        return DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
    }

    fun createDirectory(context: Context, parentUri: Uri, name: String): Uri? {
        return DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    fun getParentUri(uri: Uri): Uri {
        val pathSegments = uri.pathSegments
        val parentSegments = pathSegments.dropLast(1)
        val builder = uri.buildUpon().path("")
        for (segment in parentSegments) {
            builder.appendPath(segment)
        }
        return builder.build()
    }
}
