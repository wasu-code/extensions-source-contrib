package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileHandlerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                return Toast.makeText(this, "Missing permission: 'Manage all files'.", Toast.LENGTH_LONG).show()
            }
        }

        val action = intent.getStringExtra("action")
        val inputPath = intent.getStringExtra("from")
        val outputPath = intent.getStringExtra("to")

        Toast.makeText(this, "$action: $inputPath >> $outputPath", Toast.LENGTH_SHORT).show()

        if (action == "generateChapterDummies") {
            if (inputPath == null) {
                Toast.makeText(this, "Missing input path", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val inputDir = File(inputPath)
            if (!inputDir.exists() || !inputDir.isDirectory) {
                Toast.makeText(this, "Invalid input directory", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val pdfFiles = inputDir.listFiles { _, name -> name.endsWith(".pdf", ignoreCase = true) } ?: emptyArray()

            for (pdf in pdfFiles) {
                val dummyFolder = File(inputDir, "${pdf.nameWithoutExtension}.dummy")
                dummyFolder.mkdirs()
            }

            Toast.makeText(this, "Dummy folders created: ${pdfFiles.size}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (action == "convert") {
//            if (inputPath == null) {
//                Toast.makeText(this, "Missing input path", Toast.LENGTH_LONG).show()
//                finish()
//                return
//            }

            val pdfFile = File(inputPath)
            val chapterName = pdfFile.nameWithoutExtension
            val mangaName = pdfFile.parentFile!!.name

            val outputDir = File(outputPath, mangaName)
            outputDir.mkdirs()

            val zipFile = File(outputDir, "$chapterName.cbz")

            try {
                convertPdfToZip(pdfFile, zipFile)
                Toast.makeText(this, "Conversion complete: $chapterName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }

    private fun convertPdfToZip(pdfFile: File, zipFile: File) {
        val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)

        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)

                val scale = 2
                val width = page.width * scale
                val height = page.height * scale

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val paddedIndex = String.format("%03d", i)
                val imageEntryName = "page$paddedIndex.jpg"

                val tempImgFile = File.createTempFile("page$paddedIndex", ".jpg", cacheDir)
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
}
