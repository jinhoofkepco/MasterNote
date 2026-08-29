package com.studyink.assistant.webview

import java.net.URI
import java.util.Locale

/** Exact, non-wildcard hosts accepted for ChatGPT and its interactive sign-in redirects. */
internal object ChatGptNavigationPolicy {
    private val automationHosts = setOf(
        "chatgpt.com",
        "www.chatgpt.com",
        "chat.openai.com",
    )

    private val allowedHosts = setOf(
        *automationHosts.toTypedArray(),
        "auth.openai.com",
        "auth0.openai.com",
        // Social sign-in is allowed only on the providers' exact identity hosts. Some providers
        // may still refuse embedded browsers; email sign-in remains the reliable fallback.
        "accounts.google.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "login.live.com",
    )

    fun allows(url: String): Boolean = parsedHttpsHost(url)?.let(allowedHosts::contains) == true

    /** Login redirects may be displayed, but private page content is injected only here. */
    fun allowsAutomation(url: String?): Boolean =
        url != null && parsedHttpsHost(url)?.let(automationHosts::contains) == true

    private fun parsedHttpsHost(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            return@runCatching null
        }
        host
    }.getOrNull()
}
