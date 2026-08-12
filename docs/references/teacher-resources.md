# Teacher resources and managed assets

The teacher-resource feature is layered over the existing Reader contracts. It does not own PDF
rendering, Ink input, attempts, reviews, or remote sessions.

## Managed file lifecycle

`ContentResolver` input → streamed staging file → header/MIME validation → SHA-256 → fd sync →
atomic move → Room metadata/relationship transaction.

Managed files live below `filesDir/managed-assets/v1/<hash-prefix>/<sha256>.<extension>`. Staging
files live below `.staging`. Database rows store only metadata and relative paths; PDF and image
bytes are never Room BLOBs. Files committed before a failed database operation are quarantined as
unreferenced files and deleted only after the garbage-collection grace period.

ZIP validation rejects traversal, absolute paths, directories, nested archives, unsupported media,
empty archives, more than 2,000 entries, more than 2 GiB expanded data, and suspicious compression
ratios. Import uses a 64 KiB buffer and a 2 GiB per-asset ceiling.

## Assistant boundary

External assistant providers return a `ResourceDraft`; they do not update teaching-resource tables.
The shipping provider uses Android Sharesheet and Custom Tabs. The experimental WebView remains in
a lab module and has no main navigation route. No OpenAI API credential is stored on-device.
