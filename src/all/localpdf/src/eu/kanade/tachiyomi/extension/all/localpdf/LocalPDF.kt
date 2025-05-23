package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val INPUT_DIR = "/storage/B8C8-8C91/TachiyomiSY/localpdf"
const val OUTPUT_DIR = "/storage/B8C8-8C91/TachiyomiSY/downloads/Local PDF (ALL)"

class LocalPDF : HttpSource() {

    override val name = "Local PDF"
    override val lang = "all"
    override val supportsLatest = false

    override val baseUrl: String = ""

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val context = Injekt.get<Application>()

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

    suspend fun getMangaDetails(manga: SManga): SManga {
        manga.description = "This manga is generated from PDF"
        manga.status = SManga.COMPLETED
        return manga
    }

    suspend fun getChapterList(manga: SManga): List<SChapter> {
        val mangaDir = File(INPUT_DIR, manga.url)
        val pdfFiles = mangaDir.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) } ?: emptyArray()

        return pdfFiles.map { pdf ->
            SChapter.create().apply {
                name = pdf.nameWithoutExtension
                url = "${manga.url}/${pdf.name}"
            }
        }
    }

    suspend fun getPageList(chapter: SChapter): List<Page> {
        handler.post {
            Toast.makeText(context, "Converting pages...", Toast.LENGTH_SHORT).show()
        }

        val pdfPath = "$INPUT_DIR/${chapter.url}"
        val pdfFile = File(pdfPath)
        val chapterName = pdfFile.nameWithoutExtension
        val mangaName = pdfFile.parentFile?.name ?: "unknown"

        val outputDir = File("$OUTPUT_DIR/$mangaName")
        outputDir.mkdirs()

        val zipFile = File(outputDir, "$chapterName.cbz")
        convertPdfToZip(pdfFile, zipFile)

        handler.post {
            Toast.makeText(context, "Ready! You can start reading now", Toast.LENGTH_SHORT).show()
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
