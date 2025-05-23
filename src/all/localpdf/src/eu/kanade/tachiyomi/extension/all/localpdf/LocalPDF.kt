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

class LocalPDF : HttpSource() {

    override val name = "Local PDF"
    override val lang = "all"
    override val supportsLatest = false
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not Used")

    override val baseUrl: String = ""

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val context = Injekt.get<Application>()

    public suspend fun getPopularManga(page: Int): MangasPage {
        val manga = SManga.create().apply {
            title = "seriesA"
            url = "/pdfmanga"
        }
        return MangasPage(listOf(manga), false)
    }

    suspend fun getMangaDetails(manga: SManga): SManga {
        manga.description = "This manga is generated from PDF"
        manga.status = SManga.COMPLETED
        return manga
    }

    public suspend fun getChapterList(manga: SManga): List<SChapter> {
        val chapter = SChapter.create().apply {
            name = "PDF Chapter 1"
            url = "/pdfchapter1"
        }
        return listOf(chapter)
    }

    public suspend fun getPageList(chapter: SChapter): List<Page> {
        handler.post {
            Toast.makeText(context, "converting...", Toast.LENGTH_SHORT).show()
        }

        val pdfPath = "/storage/B8C8-8C91/TachiyomiSY/localpdf/seriesA/sample.pdf"

        val outputDir = File("/storage/B8C8-8C91/TachiyomiSY/downloads/Local PDF (ALL)/seriesA")
        outputDir.mkdirs()

        val zipFile = File(outputDir, "PDF Chapter 1.cbz")
        convertPdfToZip(File(pdfPath), zipFile)

        handler.post {
            Toast.makeText(context, "ready!", Toast.LENGTH_SHORT).show()
        }

        return emptyList()
    }

    private fun convertPdfToZip(pdfFile: File, zipFile: File) {
        val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val paddedIndex = String.format("%03d", i) // ensures 3-digit padding: 000, 001, ..., 999
                val imgFile = File.createTempFile(paddedIndex, ".jpg")

                FileOutputStream(imgFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }

                zipOut.putNextEntry(ZipEntry("page$i.jpg"))
                zipOut.write(imgFile.readBytes())
                zipOut.closeEntry()
                imgFile.delete()
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
}
