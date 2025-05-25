package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
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
    override val baseUrl: String = ""

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val preferences: SharedPreferences by getPreferencesLazy()
    private val contentResolver: ContentResolver = context.contentResolver

    private val inputUri: Uri? get() = preferences.getString("INPUT_URI", null)?.let(Uri::parse)
    private val outputUri: Uri? get() = preferences.getString("OUTPUT_URI", null)?.let(Uri::parse)

    @Suppress("RedundantSuspendModifier")
    suspend fun getPopularManga(page: Int): MangasPage {
        val rootFolder = inputUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return MangasPage(emptyList(), false)
        val mangaDirs = rootFolder.listFiles().filter { it.isDirectory }

        val mangaList = mangaDirs.map { dir ->
            SManga.create().apply {
                title = dir.name ?: "???"
                url = dir.uri.toString() // use URI as a unique ID
            }
        }

        return MangasPage(mangaList, hasNextPage = false)
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getMangaDetails(manga: SManga): SManga {
        manga.description = "This manga is generated from PDF"
        manga.status = SManga.COMPLETED
        return manga
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        val mangaDir = DocumentFile.fromTreeUri(context, Uri.parse(manga.url)) ?: return emptyList()

        return mangaDir.listFiles()
            .filter { it.name?.endsWith(".pdf", true) == true }
            .map { pdf ->
                SChapter.create().apply {
                    name = pdf.name?.removeSuffix(".pdf") ?: "Chapter"
                    url = pdf.uri.toString()
                }
            }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        handler.post {
            Toast.makeText(context, "Converting pages...", Toast.LENGTH_SHORT).show()
        }

        val chapterUri = Uri.parse(chapter.url)
        val pdfDoc = DocumentFile.fromSingleUri(context, chapterUri) ?: return emptyList()

        val parentName = pdfDoc.parentFile?.name ?: "UnknownManga"
        val chapterName = pdfDoc.name?.removeSuffix(".pdf") ?: "chapter"

        val outputFolder = outputUri?.let { DocumentFile.fromTreeUri(context, it) }?.findFile(parentName)
            ?: outputUri?.let { DocumentFile.fromTreeUri(context, it)?.createDirectory(parentName) }
            ?: return emptyList()

        val zipFile = outputFolder.findFile("$chapterName.cbz")
            ?: outputFolder.createFile("application/zip", chapterName)

        contentResolver.openInputStream(pdfDoc.uri)?.use { inputStream ->
            contentResolver.openOutputStream(zipFile?.uri ?: return emptyList())?.use { outputStream ->
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
                    true // Save the new value
                } else {
                    Toast.makeText(screen.context, "Path must end with `downloads/Local PDF (ALL)`", Toast.LENGTH_LONG).show()
                    false // Reject the value
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

//        const val DEFAULT_OUTPUT_DIR = "/storage/emulated/0/Mihon/downloads/Local PDF (ALL)"
        const val EXTENSION_PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.localpdf"
        const val PICKER_ACTIVITY = "eu.kanade.tachiyomi.extension.all.localpdf.SAFPickerActivity"
    }
}
