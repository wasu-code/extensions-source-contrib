@file:Suppress("unused", "RedundantSuspendModifier", "UNUSED_PARAMETER")

package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalPDF : HttpSource(), ConfigurableSource {

    companion object {
        /** Extension package name */
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.localpdf"
    }

    override val name = "Local PDF"
    override val lang = "all"
    override val supportsLatest = false
    override val baseUrl: String = ""

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val context = Injekt.get<Application>()
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val mihonUri = preferences.getString("MIHON_URI", null)?.let { Uri.parse(it) }

    private fun getInputDir(): UniFile? {
        return mihonUri?.let {
            UniFile.fromUri(context, it)
                ?.findFile("localpdf")
        }
    }

    private fun getOutputDir(): UniFile? {
        return mihonUri?.let {
            UniFile.fromUri(context, it)
                ?.findFile("downloads")
                ?.findFile("Local PDF (ALL)")
        }
    }

    suspend fun getPopularManga(page: Int): MangasPage {
        val inputDir = getInputDir()
        val mangaDirs = inputDir?.listFiles()?.filter { it.isDirectory } ?: emptyList()

        val mangaList = mangaDirs.map { dir ->
            SManga.create().apply {
                title = dir.name ?: "Unknown"
                url = dir.name ?: "unknown"
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
        val inputDir = getInputDir()
        val mangaDir = inputDir?.findFile(manga.url)?.takeIf { it.isDirectory }

        val pdfFiles = mangaDir?.listFiles()?.filter {
            it.name?.endsWith(".pdf", ignoreCase = true) == true
        } ?: emptyList()

        return pdfFiles.map { pdf ->
            SChapter.create().apply {
                name = pdf.name?.removeSuffix(".pdf") ?: "chapter"
                url = "${manga.url}/${pdf.name}"
            }
        }
    }

    private fun copyCbzToOutput(cbzFile: File, outputDir: UniFile?, mangaName: String) {
        val mangaFolder = outputDir?.findFile(mangaName) ?: outputDir?.createDirectory(mangaName)
        val cbzUniFile = mangaFolder?.createFile(cbzFile.name)

        cbzUniFile?.let {
            context.contentResolver.openOutputStream(it.uri)?.use { output ->
                cbzFile.inputStream().copyTo(output)
            }
        }
    }

    suspend fun getPageList(chapter: SChapter): List<Page> {
        handler.post {
            Toast.makeText(context, "Converting pages... \n(ignore \"No Pages\" toast)", Toast.LENGTH_SHORT).show()
        }

        val mangaName = chapter.url.substringBefore("/")
        val chapterFileName = chapter.url.substringAfter("/")

        val inputDir = getInputDir()
        val outputDir = getOutputDir()

        val pdfFile = inputDir
            ?.findFile(mangaName)
            ?.findFile(chapterFileName)

        pdfFile?.let {
            val cacheFile = File(context.cacheDir, it.name ?: "temp.pdf")
            context.contentResolver.openInputStream(it.uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val cbzFile = File(context.cacheDir, "${chapterFileName.removeSuffix(".pdf")}.cbz")
            convertPdfToZip(cacheFile, cbzFile)
            copyCbzToOutput(cbzFile, outputDir, mangaName)

            handler.post {
                Toast.makeText(context, "Ready!\nYou can start reading now", Toast.LENGTH_SHORT).show()
            }
        }

        return emptyList()
    }

    private fun convertPdfToZip(pdfFile: File, zipFile: File) {
        val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)

        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)

                // Use higher resolution for better readability
                val scale = 1 // scale factor: increase for higher DPI
                val width = page.width * scale
                val height = page.height * scale

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // Clear the bitmap to white to avoid black background
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)

                // Render the page
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Name like page000.jpg
                val paddedIndex = String.format("%03d", i)
                val imageEntryName = "page$paddedIndex.jpg"

                // Write to ZIP
                val tempImgFile = File.createTempFile("page$paddedIndex", ".jpg")
                FileOutputStream(tempImgFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }

                zipOut.putNextEntry(ZipEntry(imageEntryName))
                zipOut.write(tempImgFile.readBytes())
                zipOut.closeEntry()
                tempImgFile.delete()
            }
        }

        renderer.close()
        descriptor.close()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "MIHON_URI"
            title = """URI to Mihon root directory
                Same as "Settings » Data and storage » Storage loaction"
            """.trimIndent()
            dialogTitle = "[...]/Mihon"
            summary = preferences.getString(key, "Not set")
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue as String
                preference.summary = value
                Toast.makeText(context, "Restart app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
            setOnPreferenceClickListener {
                val intent = Intent().apply {
                    setClassName(PACKAGE_NAME, UriPickerActivity::class.java.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "INFO_INPUT_DIR"
            title = ""
            summary = """Example folder structure:
                /storage/emulated/0/Mihon/localpdf/
                ├── seriesName1/
                │   ├── ch1.pdf
                │   └── ch2.pdf
                ├── seriesName2/
                └── seriesName3/
            """.trimIndent()
            setEnabled(false)
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
}
