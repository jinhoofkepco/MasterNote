# Remote synchronization soak results

Date: 2026-08-12 (Asia/Seoul)

## Automated simulator

Command:

`/usr/bin/time -lp ./gradlew :lab:remote-simulator:testDebugUnitTest :lab:remote-simulator:lintDebug`

Result: PASS in 3.46 seconds (warm Gradle daemon).

- 10,000 durable AddStroke operations, with every 100th message retried after simulated ACK loss:
  10,000 effects, no duplicate effect, contiguous ACK 10,000.
- 1,000 ReplaceStrokes operations delivered three times each: 1,000 effects.
- 100 disconnect-style reorder cycles, ten operations each: 1,000 effects, contiguous ACK 1,000.
- 72,000 preview/page ticks (two virtual hours at 100 ms): preview retained one latest value,
  page state retained one latest value, preview contained at most 24 points.
- Durable send window constant: 64; sequence-gap buffer constant: 32.
- Test-process maximum resident set size reported by `/usr/bin/time`: 125,255,680 bytes.
- Peak memory footprint reported by `/usr/bin/time`: 98,829,200 bytes.

This is deterministic accelerated testing, not evidence of two physical devices staying connected for
two wall-clock hours. It verifies bounded data structures and protocol effects; radios, vendor Play
services behavior, thermal effects, and real foreground/background lifecycle require the device matrix.

## Android device available in this workspace

- `Android_SDK_built_for_arm64`, API 36 emulator.
- Room schema/migration/replica transaction suite: 32 tests passed.
- Real Nearby two-endpoint latency: not measurable with the single available emulator.

## Required physical matrix (not yet executed)

- Two actual tablets, model and Android version recorded.
- 30-minute smoke, then two-hour run.
- Capture p50/p95 preview and durable latency, before/after PSS, maximum Outbox and unacked sizes,
  30-second radio interruption recovery, 100 rotations, and 20 screen-off/on cycles.
