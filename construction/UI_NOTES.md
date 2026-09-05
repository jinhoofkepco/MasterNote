# Compact construction UI — 2026-09-05

## Layout and interaction

- Native 36 dp controls with 18 dp original geometric glyphs. Tool selection has
  a persistent filled background, not a momentary ripple only. Icons retain text
  accessibility descriptions and long-press tooltips.
- The drawing surface fills a fixed `FrameLayout`. The top-left action hint,
  bottom status, and small conditions/measurements panel are siblings over it.
  Opening, switching, closing, or dragging the panel never invokes fit/zoom and
  never inserts a row above the canvas. Panel content scrolls within a cap.
- The keyboard overlays the drawing surface (`ADJUST_NOTHING`); numeric/name
  editors move the panel to the top, not the drawing. Back first closes a panel.
- Frequently used tools stay visible. Rename, delete, examples, help, zoom
  buttons, and recoloring existing entities are under More. Destructive delete
  and replacing with an example retain explicit confirmation dialogs.
- Source geometry uses charcoal, blue, or terracotta. Selection adds a halo and
  does not replace the saved color. Choosing a palette color affects new entities;
  recoloring an existing selection is a separate presentation-only command.

## Annotations are not constraints

- A measurement references stable point/circle IDs; it stores no frozen value.
  Distance has two endpoints, angle has [A, vertex B, C], radius references a
  circle, and area references the triangle's three points.
- Distance uses extension lines and opposing arrows; it still spans the original
  endpoints after new points or branches are added along a line. Original lines
  are not split automatically.
- Measurement label drags change world-space offsets only. A completed drag is
  one durable/undoable command, cancellation restores its starting state.
- Length/radius/height conditions also have automatic dimension rendering.
  Tapping these edits the condition; they are not draggable reference labels.
  Use a displayed measurement on the same geometry to position its label.
- Selecting geometry reveals small related constraint badges. Their actions
  distinguish changing/disabling a condition from deleting a measurement label.
- Reference values are marked `≈` because displayed decimals are rounded. A
  degenerate angle remains an annotation but displays as undefined, never 0°.

## Automatic connections

Priority is an existing point, a unique intersection of two visible line
segments, a projection onto one visible line segment, then a free point.
A newly snapped point and its one/two POINT_ON_LINE relations are one command.
The mathematical relation subsequently applies to the supporting line, including
extensions; it does not impose an unsupported bounded-segment inequality.

Snapping can be switched off. Circle centers can use the same point/line snaps;
the second circle tap sets the radius by position only and explicitly says it
adds no connection condition. Line-circle/circle-circle intersection automation
is not added here. Existing manual point-on-circle conditions remain available.

## Compatibility and reviewed sources

The existing Apache Commons Math 3.6.1 engine is unchanged; its exact upstream
sources and packaged license are documented in `core/PROVENANCE.md`.

Before this UI change, Lucide's public source and licensing were checked:

- https://github.com/lucide-icons/lucide/blob/main/icons/circle.svg
- https://github.com/lucide-icons/lucide/blob/main/LICENSE

The small consistent stroke/24-unit icon convention was reviewed, not copied
into a dependency. `ConstructionUi.kt` draws original elementary shapes; no
Lucide asset or SolidWorks/AutoCAD source is embedded. Application interaction,
snapping, rendering, and persistence are new code with their own regression tests,
not claimed to be an upstream-proven complete CAD implementation.

The separate construction envelope reads schema 1 and 2 and writes schema 2.
Absent colors/measurements default to the old appearance and an empty list. Old
documents are not rewritten just by opening. Checksums, atomic CAS saves, root
restore protection, and immutable nested lists remain. The directory name stays
`construction-scenes-v1` so targets/backups retain their original location.
Annotation relative-coordinate and existing memo formats are untouched. A prior
schema-1-only app cannot read a newly saved schema-2 construction document; do
not use an APK downgrade as a rollback mechanism. Undo works inside this update.

This remains device-local construction data, included in the app's backup, not
a change to Telegram remote-transfer payloads. Deliver only an APK verified with
the existing `com.studyink.app` package, the established signing certificate, and
a higher version code. Never uninstall or clear data to apply this update.
