# Third-party notices

## Apache Commons Math 3.6.1 — construction numeric solver

- Source: https://github.com/apache/commons-math/tree/MATH_3_6_1
- Published dependency: `org.apache.commons:commons-math3:3.6.1`
- License: Apache License 2.0, with upstream third-party notices including MINPACK.
- Upstream LICENSE and NOTICE are included in the APK assets under `construction_licenses/`.

MasterNote reuses the published numerical optimizer and matrix decomposition implementation.
The geometric constraint definitions, Android interaction, persistence, and acceptance tests
are new MasterNote code. This is not a port of SolveSpace or FreeCAD source.
See `construction/core/PROVENANCE.md` for the reviewed upstream source and scope.

## Jetpack Compose Animations — Radial FAB Menu

- Copyright 2026 Jaewoong Eum (skydoves)
- Source: https://github.com/skydoves/compose-animations
- Adapted component: `AnimationExample14.kt`
- License: Apache License 2.0 — https://github.com/skydoves/compose-animations/blob/main/LICENSE

The polar-coordinate item placement and spring animation were adapted
for Study Ink's S Pen radial tool palette. The visual controls, application state,
and tool actions are Study Ink-specific modifications.

## ZXing / ZXing Android Embedded — QR pairing

- ZXing Core `3.5.4`: https://github.com/zxing/zxing/tree/zxing-3.5.4
- ZXing Android Embedded `4.3.0`: https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0
- License: Apache License 2.0

MasterNote uses the published libraries for local QR generation and camera scanning.
The pairing payload and LAN session handling are MasterNote-specific code.
