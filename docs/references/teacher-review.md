# Teacher review references and adoption boundaries

The teacher workflow is MasterNote-owned code. External references supply architecture patterns,
not teacher-domain source code.

- Now in Android contributes the pattern of combining repository flows and saved selection into an
  immutable UI state. Hilt, its topic domain, and Navigation 3 are not adopted.
- Jetcaster demonstrates separation between a stable main pane and a supporting pane. Material3
  Adaptive is intentionally deferred because MasterNote currently has no compatible adaptive
  dependency and this feature does not justify a navigation migration.
- Room 2.8.4 transactions are used for draft creation and publication. Every publication phase has
  local fault-injection coverage.
- AndroidX BiometricPrompt will be isolated behind a MasterNote interface. Authentication state is
  process-memory-only and never persisted to Room.
