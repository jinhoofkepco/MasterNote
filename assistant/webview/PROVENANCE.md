# WebView gateway provenance

The selector set, prompt-and-PNG injection behavior, response snapshot/completion
rules, and renderer recovery behavior in this module were adapted from the user's
`jinhoofkepco/GPTOverlay` repository at commit
`df55533cd453bc8ff0b47a641bc9dda5a976fa15`.

MasterNote-specific changes are deliberately narrow:

- the gateway is an ordinary in-app `View`; it does not request overlay or
  MediaProjection privileges;
- top-level navigation is restricted to exact HTTPS ChatGPT and interactive sign-in hosts;
- file/content URL access, mixed content, popups, geolocation, and WebView
  permission grants are disabled;
- there is no `JavascriptInterface`; native code only invokes isolated scripts
  with `evaluateJavascript` and reads their return values;
- failed automation reports a manual-send/manual-response callback while keeping
  the visible ChatGPT page usable;
- third-party cookies are enabled only in this isolated ChatGPT WebView because the
  interactive identity-provider round trip otherwise cannot retain its session;
- a renderer loss destroys only this WebView, creates a fresh one, and reports a
  retryable error to the caller.

This module owns no page, annotation, score, Telegram, or library data. Removing
the module therefore leaves those stores and transports unchanged.
