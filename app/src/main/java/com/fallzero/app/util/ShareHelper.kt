package com.fallzero.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object ShareHelper {

    fun shareText(context: Context, title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "결과 공유"))
    }

    /** 여러 이미지를 한 번에 공유 (보고서 1·2페이지 등). */
    fun shareBitmaps(context: Context, bitmaps: List<Bitmap>, baseName: String = "result") {
        if (bitmaps.size == 1) { shareBitmap(context, bitmaps[0], "$baseName.png"); return }
        val uris = ArrayList<Uri>()
        bitmaps.forEachIndexed { i, bmp ->
            val file = File(context.cacheDir, "${baseName}_${i + 1}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "결과 이미지 공유"))
    }

    fun shareBitmap(context: Context, bitmap: Bitmap, filename: String = "result.png") {
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // authority는 AndroidManifest의 <provider android:authorities="${applicationId}.provider" />와 일치해야 함
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "결과 이미지 공유"))
    }
}
