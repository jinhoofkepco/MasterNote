# Remote synchronization references and boundaries

The wire DTOs, sequence protocol, replica model, and session state machine are MasterNote-owned.
Nearby and generated protobuf types remain inside adapters and never cross into Reader or sync APIs.

- Durable messages are capped at 32 KiB normally and 128 KiB absolutely.
- Checkpoint chunks carry at most 64 KiB of page snapshot data.
- Durable messages require sequence and ACK handling; ephemeral messages are conflated and never persisted.
- Student annotation persistence is authoritative and must complete without transport availability.
- The archived connectivity sample contributes callback/lifecycle ideas only. Current permissions,
  authentication, dependency versions, and service ownership follow current official documentation.
- Runtime requests use `NEARBY_WIFI_DEVICES` from API 33, where the platform constant exists. The
  current Nearby guide's `minSdkVersion=32` manifest example is retained as documentation context,
  but requesting that permission on API 32 would reference a permission unavailable on that release.

## Durable storage invariants

- A local annotation operation and its outbox envelope are inserted by one Room transaction.
- Network failure never rolls back the student annotation transaction after it has committed.
- Inbox sequence receipts and applied operation IDs are stored separately: sequence rows advance
  contiguous ACK state, while operation IDs provide effectively-once application across retries.
- At most 64 unacknowledged messages are selected for an in-memory send window. Remaining work stays
  in Room and never blocks Ink input.
- A duplicate message can advance the contiguous sequence without applying its operation twice.
- A gap buffer holds at most 32 envelopes; overflow requests a page checkpoint instead of growing.

## Checkpoint invariants

- Page digests hash sorted stroke IDs together with revision and active count.
- A checkpoint contains one page only and is split into chunks of at most 64 KiB.
- Assemblies are bounded to 256 chunks; inconsistent metadata, corrupt protobuf, or a hash mismatch
  discards the candidate without changing the visible replica.
- A complete verified page replaces old replica rows in one Room transaction.
- `ReadOnlyRemoteLayer` is the only Reader source for a remote replica and cannot become the scene's
  editable source.
