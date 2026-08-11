# Reader/Ink test matrix

| Capability | Unit | Instrumentation | Physical tablet |
| --- | --- | --- | --- |
| Partial/whole erase geometry | `EraseEngineTest` | existing reader interaction tests | required before PR 1 merge |
| One callback per completed gesture | — | `InkCallbackContractTest` | S Pen side/eraser button smoke test |
| PDF isolation and restore | — | `ReaderInteractionTest` | five-page close/reopen test |
| One-page zoom/pan lock | `PdfTileGridTest` | `ReaderInteractionTest` | repeat pinch/pan on scan fixture |
| Text/scan/120-page inputs | — | `ReaderPdfFixtureTest` | open and page through all three |
| StrictMode | — | debug log inspection | 30-minute soak, zero app disk-I/O violations |
| Memory and frame pacing | — | API 36 smoke | Perfetto + PSS before/after 30-minute soak |

Every regression entry records APK commit, fixture version, device model, Android build, and
whether the fixed signing certificate was used.
