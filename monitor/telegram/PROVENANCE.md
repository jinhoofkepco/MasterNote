# Telegram foundation provenance

This module adapts internal implementation patterns from the repository owner's
`jinhoofkepco/FocusMonitor2` repository, branch `agent/telegram-bot-transition`, commit
`e5809ebc7167bb9c13f923f90b2cb06a92423a78` (2026-08-23).

The source repository did not contain a `LICENSE` or `NOTICE` file at that commit. The adapted
code is retained here with explicit same-owner provenance rather than represented as a clean-room
implementation.

Adapted sources and patterns:

- `app-student/.../TelegramCredentialStore.kt`: Android Keystore AES-GCM credential storage.
- `telegram-report/.../TelegramBotApi.kt`: small synchronous `HttpURLConnection` Bot API client.
- `telegram-report/.../DiskUploadQueue.kt`: fsynced append journal and atomic compaction.
- `telegram-report/.../TelegramConnectionState.kt`: pure connection-state reducer.
- `telegram-report/.../TelegramReporter.kt`: one long-poll owner and durable update offset.
- `voice-message/.../VoiceMessageRecorder*.kt`: M4A/AAC recording, `.part` commit and 60 s cap.

MasterNote-specific changes include a paired-private-chat allowlist, stable idempotency keys,
dead letters, durable `retry_after`, bounded journals, `sendVoice`, `/화면`, no-backup storage,
and process-local integration buses. No bot token is embedded in source or build configuration.
