# Reader/Ink baseline reference notes

MasterNote code must not copy unattributed snippets or expose third-party types throughout the
domain. PDF and Ink dependencies are contained in adapter/input modules; the annotation engine
uses MasterNote-owned canonical page models.

Before adopting outside code:

1. Record repository, exact commit, path, and license in `REFERENCE_LEDGER.md`.
2. Inspect upstream implementation and its tests.
3. Prove compatibility with the pinned MasterNote dependency version in a test or adapter.
4. Run local unit/instrumentation tests and a physical-tablet performance check.
5. Document deviations and retain only the minimal adapted portion.
