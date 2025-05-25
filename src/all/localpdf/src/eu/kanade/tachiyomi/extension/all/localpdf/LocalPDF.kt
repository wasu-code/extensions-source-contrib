package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val STRATEGY = 2

@RequiresApi(Build.VERSION_CODES.O)
class LocalPDF : HttpSource(), ConfigurableSource {

    override val name = "Local PDF"
    override val lang = "all"
    override val supportsLatest = false

    override val baseUrl: String = ""

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val context = Injekt.get<Application>()

    //    private val preferences by lazy {
//        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
//    }
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val INPUT_DIR = preferences.getString("INPUT_DIR", DEFAULT_INPUT_DIR) ?: DEFAULT_INPUT_DIR
    private val OUTPUT_DIR = preferences.getString("OUTPUT_DIR", DEFAULT_OUTPUT_DIR) ?: DEFAULT_OUTPUT_DIR

    @Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")
    suspend fun getPopularManga(page: Int): MangasPage {
        val mangaDirs = File(INPUT_DIR).listFiles { file -> file.isDirectory } ?: emptyArray()

        val mangaList = mangaDirs.map { dir ->
            SManga.create().apply {
                title = dir.name
                url = dir.name // Use folder name as unique URL identifier
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
        val mangaDir = File(INPUT_DIR, manga.url)

//        val pdfFiles = mangaDir.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) } ?: emptyArray()

        // use fake chapters: directories with PDF's name
//        if (pdfFiles.isEmpty()) mangaDir.listFiles { file -> file.name.endsWith(".dummy", ignoreCase = true) } ?: emptyArray()
        // change parsing method generally to STRATEGY 2

        val pdfFiles = mangaDir.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }?.takeIf { it.isNotEmpty() }
            ?: mangaDir.listFiles { file -> file.name.endsWith(".dummy", ignoreCase = true) } ?: emptyArray()

        val chapterNames = pdfFiles.map { file ->
            file.nameWithoutExtension.replace(".dummy", "", ignoreCase = true)
        }

        return chapterNames.map { pdf ->
            SChapter.create().apply {
                name = pdf
                url = "${manga.url}/$pdf.pdf"
            }
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        handler.post {
            Toast.makeText(context, "Converting pages...", Toast.LENGTH_SHORT).show()
        }

        if (STRATEGY == 1) {
            val pdfPath = "$INPUT_DIR/${chapter.url}"
            val pdfFile = File(pdfPath)
            val chapterName = pdfFile.nameWithoutExtension
            val mangaName = pdfFile.parentFile?.name ?: "unknown"

            val outputDir = File("$OUTPUT_DIR/$mangaName")
            outputDir.mkdirs()

            val zipFile = File(outputDir, "$chapterName.cbz")
            convertPdfToZip(File(pdfPath), zipFile)
            Toast.makeText(context, "Ready! You can start reading now", Toast.LENGTH_SHORT)
                .show()
        } else {
            val intent = Intent().apply {
                setClassName("eu.kanade.tachiyomi.extension.all.localpdf", "eu.kanade.tachiyomi.extension.all.localpdf.FileHandlerActivity")
                putExtra("action", "convert")
                putExtra("chapterPath", "$INPUT_DIR/${chapter.url}")
                putExtra("outputDir", OUTPUT_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
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
                val scale = 2 // scale factor: increase for higher DPI
                val width = page.width * scale
                val height = page.height * scale

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // Clear the bitmap to white to avoid black background
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)

                // Render the page
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
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
            key = "INPUT_DIR"
            title = "Folder storing series in PDF format"
            dialogTitle = "Suggested: $DEFAULT_INPUT_DIR"
            summary = preferences.getString(key, DEFAULT_INPUT_DIR) ?: DEFAULT_INPUT_DIR
            setDefaultValue(DEFAULT_INPUT_DIR)
            setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue as String
                preference.summary = value
                Toast.makeText(context, "Restart app to apply changes", Toast.LENGTH_LONG).show()
                true
            }
//            setOnPreferenceClickListener {
//                val intent = Intent().apply {
//                    setClassName(context, FileHandlerActivity::class.java.name)
//                    putExtra("action", "safRequest")
//                    putExtra("request_code", 1001)
//                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                }
//                context.startActivity(intent)
//                true
//            }
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
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "OUTPUT_DIR"
            title = "Mihon download folder (as in Settings » Data & Storage)"
            dialogTitle = "Should end with `.../downloads/Local PDF (ALL)` and be in Mihon directory"
            summary = preferences.getString(key, DEFAULT_OUTPUT_DIR) ?: DEFAULT_OUTPUT_DIR
            setDefaultValue(DEFAULT_OUTPUT_DIR)
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
        }.also(screen::addPreference)

        /*SwitchPreferenceCompat(screen.context).apply {
            key = "SAF_TRIGGER_INPUT_DIR"
            title = "SAF input dir picker"
            summary = "does nothing"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val intent = Intent().apply {
                        setClassName("eu.kanade.tachiyomi.extension.all.localpdf", "eu.kanade.tachiyomi.extension.all.localpdf.SAFPickerActivity")
                        putExtra("request_code", 1001) // or 1002 for output
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(context, "Err: ${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.also(screen::addPreference)*/
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
        const val DEFAULT_OUTPUT_DIR = "/storage/emulated/0/Mihon/downloads/Local PDF (ALL)"
    }
}
