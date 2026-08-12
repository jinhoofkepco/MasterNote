# Remote synchronization references and boundaries

The wire DTOs, sequence protocol, replica model, and session state machine are MasterNote-owned.
Nearby and generated protobuf types remain inside adapters and never cross into Reader or sync APIs.

- Durable messages are capped at 32 KiB normally and 128 KiB absolutely.
- Checkpoint chunks carry at most 64 KiB of page snapshot data.
- Durable messages require sequence and ACK handling; ephemeral messages are conflated and never persisted.
- Student annotation persistence is authoritative and must complete without transport availability.
- The archived connectivity sample contributes callback/lifecycle ideas only. Current permissions,
  authentication, dependency versions, and service ownership follow current official documentation.
