package com.studyink.teacher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

interface AssistantProvider {
    fun launch(context: Context, prompt: String, imageUri: Uri?)
}

class ExternalShareAssistantProvider : AssistantProvider {
    override fun launch(context: Context, prompt: String, imageUri: Uri?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (imageUri == null) "text/plain" else "image/png"
            putExtra(Intent.EXTRA_TEXT, prompt)
            imageUri?.let { putExtra(Intent.EXTRA_STREAM, it); clipData = ClipData.newUri(context.contentResolver, "문제 영역", it); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        context.startActivity(Intent.createChooser(intent, "GPT 앱 또는 브라우저로 보내기"))
    }
}

class CustomTabAssistantProvider : AssistantProvider {
    override fun launch(context: Context, prompt: String, imageUri: Uri?) {
        require(imageUri == null || imageUri.scheme == "content")
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Maternote GPT 요청문", prompt))
        CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_ON).build()
            .launchUrl(context, Uri.parse("https://chatgpt.com/"))
    }
}
