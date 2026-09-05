# Numerical engine and geometry provenance

Checked on 2026-09-05 before implementation. The released Maven artifact
`org.apache.commons:commons-math3:3.6.1` is the actual runtime numerical dependency.
No SolveSpace, SolidWorks, GeoGebra, or FreeCAD source code is copied into this module.

Rechecked on 2026-09-06 for the relation expansion: SolveSpace's public
`src/slvs/lib.cpp` exposes equal line lengths, equal angles, midpoint, and length
ratio relations (https://github.com/solvespace/solvespace/blob/master/src/slvs/lib.cpp).
These informed the relation vocabulary, not a copied GPL solver implementation.
The runtime continues to use the pinned Apache implementation below.

## Upstream code inspected

- Apache repository: https://github.com/apache/commons-math
- Exact released LM source: https://github.com/apache/commons-math/blob/MATH_3_6_1/src/main/java/org/apache/commons/math3/fitting/leastsquares/LevenbergMarquardtOptimizer.java
- Exact released SVD source: https://github.com/apache/commons-math/blob/MATH_3_6_1/src/main/java/org/apache/commons/math3/linear/SingularValueDecomposition.java
- Exact release license: https://github.com/apache/commons-math/blob/MATH_3_6_1/LICENSE.txt
- Exact release notice: https://github.com/apache/commons-math/blob/MATH_3_6_1/NOTICE.txt

The upstream LM implementation identifies its origin as a translation of MINPACK
`lmder`, with the original Argonne National Laboratory work dating to 1980.
This is an established, published Java implementation with source, release history,
and upstream tests. Its age is evidence of provenance, not proof that this new
application-specific geometry layer is bug-free. The dependency is pinned rather
than using unversioned snippets or a new hobby CAD solver.

The full upstream LICENSE and NOTICE are packaged unmodified (except line endings)
under `assets/construction_licenses/`. The LICENSE includes third-party components,
including the MINPACK notice and redistribution terms. They must remain in APKs.

## Application-specific implementation

`ConstructionScene.kt` and `ConstraintSolver.kt` are new Kotlin code in MasterNote.
They define elementary 2-D distance, coincidence, cross-product, dot-product, and
directed-angle equations and immutable public scene data. Apache supplies the LM
optimizer and the SVD pseudoinverse, not the CAD model, branch policy, diagnostics,
storage, or UI. Do not describe the whole geometry editor as upstream-proven code.

- One mathematical unit is used on both axes; view zoom cannot distort angles.
- All enabled geometric conditions are hard equations. Small drag and proximity
  terms select a nearby solution; a separate SVD projection then restores hard
  feasibility. Returned geometry is checked against all enabled equations.
- Conflicting edits return the input scene unchanged. Failure means no valid
  solution was found within this bounded solve, not a proof of impossibility.
- Dragging uses warm starts and bounded continuation. Distance-link elbow signs
  and double-circle intersection sides are checked, including coincident aliases.
  This is local branch continuity, not a general symbolic branch enumerator.
- Direction constraints reject a collapsed reference line. Radius is positive.
- POINT_ON_LINE and DISTANCE_POINT_LINE use the entire supporting line. ANGLE
  measures the unsigned [0,180] degree angle between start-to-end directions;
  the initial orientation chooses the locally continuous signed branch.
- POINT_ON_SEGMENT has a closed-interval guard. POINT_FRACTION stores the integer
  numerator and denominator and enforces `P = A + (k/n)(B-A)`, not a rounded display
  decimal. POINT_DISTANCE uses a directed unit vector from the selected endpoint;
  its default interval guard is only removed by the explicit extension option.
- LENGTH_RATIO relates two live lengths, and INTERIOR_ANGLE uses a point/vertex/
  point triple independently of segment drawing order. EQUAL_ANGLE compares two
  interior triples and preserves each non-collinear orientation. Angle rays and
  ratio/equal-length references may not collapse to fake a satisfied condition.
  These are numeric constraints with feasibility tolerances, not symbolic proofs.
- Limits: 60 points, 100 conditions, 180 geometric entities, 1.5-second solve budget.
  Pointer input must run off the main thread and be coalesced by the UI.

## Acceptance tests

JUnit exercises the separately drawn 10 cm + 6 cm coincident chain, unreachable
drag without stretching, circle projection, two-circle branch retention,
consistent/redundant/conflicting conditions, independent equal numeric values,
angle creation from collinear input, point-on-line extension, fixed points,
malformed and nonfinite input, collapsed-direction rejection, and the user's
trapezoid intersection/perpendicular construction (3.8 cm, changed height -> 4 cm).
`ConstructionRelationsTest` additionally covers midpoint/trisection rotation and
drag, rational division, endpoint distances and explicit extensions, bounded
point dragging, changed driving length ratios, three-point interior angles,
equal-angle bisector construction, disabled relations, and atomic conflicts.
Storage tests cover omitted legacy defaults/digests and exact new options through
scene reopen and the student-snapshot/teacher-publish/application-receipt path.

Run `gradlew :construction:core:testDebugUnitTest` from the repository root.
