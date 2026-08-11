# Annotation persistence references

## Adopted boundary

MasterNote owns the storage model and exposes only its own `AnnotationSnapshot` and
`AnnotationMutation` types. Room entities and Jetpack Ink serialization remain inside
`annotation/storage`, so future dependency upgrades do not leak across the app.

## Room

- Version: 2.8.4
- Upstream revision: `75ef81cced187631f0dd74666188bd9d4cd3358f`
- License: Apache-2.0
- Used capabilities: KSP-generated DAO, coroutine transactions, schema export, in-memory tests
- Explicit choices: no destructive migration, no main-thread database access, one transaction
  for asset insertion, old-link deactivation, new-link activation, operation insertion, and
  page/layer/document revision changes

The database begins at schema version 1. Every future version must include its exported JSON
and an instrumentation migration test before merge.

## Jetpack Ink storage

- Version: 1.0.0 stable
- Upstream revision: `8bf058367eab7e1352e81f61606d663ed7b3cd1d`
- License: Apache-2.0
- Used capability: `StrokeInputBatchSerialization` stream encode/decode

The codec stores reproducible input samples and brush metadata. Rendered meshes are not
persisted. The byte-array convenience overload introduced in Ink 1.1 alpha is intentionally
not used.

## Architecture samples

- Repository: `android/architecture-samples`
- Revision: `ee66e1526b84c026615df032c705842b7d2a521f`
- License: Apache-2.0
- Adoption: repository/coroutine/fake-test separation pattern only; no source copied

## Validation

- upstream versions and licenses pinned in `REFERENCE_LEDGER.md`
- clean compile with Room KSP generation
- exported schema checked into `annotation/storage/schemas`
- codec round trip, five-page restore, replacement commit, replacement rollback, and corrupt
  payload isolation tested on API 36
- existing PDF/Ink reader regression suite retained
