# Maternote content package format v1

`.mnote` is a ZIP containing exactly one `manifest.json` and content-addressed files under `assets/`. One package describes one immutable book revision. Learner profiles, attempts, submissions, ink, reviews, remote sessions, and progress are prohibited.

## Compatibility

- Reader supports major version 1. A different major is rejected.
- Unknown minor fields are ignored only in Reader mode.
- Every unknown `requiredCapabilities` value is rejected.
- Unknown optional capabilities produce warnings and may be omitted.
- Studio uses strict JSON and semantic validation before export.

## Limits

| Limit | v1 value |
|---|---:|
| Entries | 10,000 |
| Manifest | 5 MiB |
| Single asset | 1 GiB |
| Expanded package | 2 GiB |
| Compression ratio | 200:1 |

Absolute paths, `..`, duplicate entries, nested ZIP/`.mnote`, multiple manifests, missing assets, bad hashes, and dangling IDs are errors. Assets use `assets/<full-sha256>.<extension>`.

## Commands

From `package-format/`: `./gradlew :cli:run --args='validate book.mnote'`, `inspect`, or `diff old.mnote new.mnote`.
