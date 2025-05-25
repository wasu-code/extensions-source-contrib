package eu.kanade.tachiyomi.extension.all.localpdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.annotation.RequiresApi

class SAFPickerActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 42
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra("action")
        if (action == "safRequest") {
            openFolderPicker()
        } else {
            finish()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, getInitialUri())
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE)
    }

    private fun getInitialUri(): Uri? {
        // Optional: guide user to a starting folder (not guaranteed to be respected)
        val volumeName = "primary" // "primary" usually maps to /storage/emulated/0
        val relativePath = "Mihon"
        val documentId = "$volumeName:$relativePath"

        return DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            documentId,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE && resultCode == Activity.RESULT_OK) {
            resultData?.data?.let { uri ->
                // Persist permission
//                contentResolver.takePersistableUriPermission(
//                    uri,
//                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
//                )

                // Copy to clipboard
//                val clipboard = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
//                val clip = ClipData.newPlainText("Selected Folder URI", uri.toString())
//                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, uri.toString(), Toast.LENGTH_LONG).show()

                // Toast
//                Toast.makeText(this, "Uri copied! Now paste it into settings", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }
}
