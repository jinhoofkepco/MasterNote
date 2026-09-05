package com.studyink.construction.core

/** Geometry coordinates use one uniform mathematical unit on both axes (cm in the editor).
 * View scale and annotation page coordinates never enter this model. */
data class ConstructionScene(
    val points: List<GeometryPoint> = emptyList(),
    val segments: List<GeometrySegment> = emptyList(),
    val circles: List<GeometryCircle> = emptyList(),
    val constraints: List<GeometryConstraint> = emptyList(),
    val measurements: List<GeometryMeasurement> = emptyList(),
) {
    fun point(id: String): GeometryPoint? = points.firstOrNull { it.id == id }
    fun segment(id: String): GeometrySegment? = segments.firstOrNull { it.id == id }
    fun circle(id: String): GeometryCircle? = circles.firstOrNull { it.id == id }
}

data class GeometryPoint(
    val id: String,
    val x: Double,
    val y: Double,
    val label: String = "",
    /** Null retains the original renderer default. A color never adds a geometric condition. */
    val colorArgb: Int? = null,
)
data class GeometrySegment(
    val id: String,
    val startPointId: String,
    val endPointId: String,
    val label: String = "",
    val colorArgb: Int? = null,
    val lineStyle: GeometryLineStyle = GeometryLineStyle.SOLID,
)
data class GeometryCircle(
    val id: String,
    val centerPointId: String,
    val radius: Double,
    val label: String = "",
    val colorArgb: Int? = null,
    val lineStyle: GeometryLineStyle = GeometryLineStyle.SOLID,
)

/** Presentation only. Gaps never change the supporting line/circle or its solver equations. */
enum class GeometryLineStyle { SOLID, DASHED, DOTTED }

enum class MeasurementType { DISTANCE, ANGLE, RADIUS, AREA }

/**
 * A visible, read-only measurement, not a solver equation or a cached numeric value.
 * DISTANCE references [pointA, pointB]; ANGLE [pointA, vertexB, pointC] measures the unsigned
 * angle in [0, 180]; RADIUS references [circle]; AREA references three triangle points.
 * Point IDs are stable anchors even if new points or branches are added along an existing line.
 * Offsets are uniform world units (positive x right, positive y up), relative to the renderer's
 * natural dimension anchor. Moving a label changes these offsets, never its measured geometry.
 */
data class GeometryMeasurement(
    val id: String,
    val type: MeasurementType,
    val entityIds: List<String>,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
)

enum class ConstraintType {
    FIXED_POINT,
    COINCIDENT,
    POINT_ON_LINE,
    POINT_ON_CIRCLE,
    DISTANCE_POINT_LINE,
    LENGTH,
    RADIUS,
    HORIZONTAL,
    VERTICAL,
    PARALLEL,
    PERPENDICULAR,
    EQUAL_LENGTH,
    ANGLE,
    POINT_ON_SEGMENT,
    POINT_FRACTION,
    POINT_DISTANCE,
    LENGTH_RATIO,
    INTERIOR_ANGLE,
    EQUAL_ANGLE,
    DISTANCE_POINTS,
    EQUAL_DISTANCE_POINTS,
}

/**
 * References, in order:
 * FIXED_POINT [point] + targetX/targetY; COINCIDENT [point, point];
 * POINT_ON_LINE [point, segment] (the entire supporting line, extensions allowed);
 * POINT_ON_CIRCLE [point, circle]; LENGTH [segment] / RADIUS [circle] + value;
 * DISTANCE_POINT_LINE [point, segment] + nonnegative value (supporting line, side preserved);
 * HORIZONTAL / VERTICAL [segment];
 * PARALLEL / PERPENDICULAR / EQUAL_LENGTH / ANGLE [segment, segment].
 * ANGLE is the unsigned angle in [0, 180] degrees between directed start->end segments.
 * POINT_ON_SEGMENT [point, segment] includes its endpoints, but not the extensions.
 * POINT_FRACTION [point, segment] stores the exact fraction numerator/denominator from start.
 * POINT_DISTANCE [point, segment] is value cm from start (or end when fromEnd), toward the other
 * endpoint. It stays inside the segment unless allowExtension explicitly permits going beyond it.
 * LENGTH_RATIO [segmentA, segmentB] means length A = value * length B.
 * INTERIOR_ANGLE [A, B, C] has vertex B and value degrees in [0, 180]. EQUAL_ANGLE compares
 * [A, B, C] and [D, E, F] interiors. The two triples may share vertices or rays.
 * DISTANCE_POINTS [pointA, pointB] fixes their distance without adding a visible segment.
 * EQUAL_DISTANCE_POINTS [A, B, C, D] means distance AB = distance CD; pairs may share a point.
 * A displayed measurement is not a constraint unless explicitly added here.
 */
data class GeometryConstraint(
    val id: String,
    val type: ConstraintType,
    val entityIds: List<String>,
    val value: Double? = null,
    val targetX: Double? = null,
    val targetY: Double? = null,
    val enabled: Boolean = true,
    val numerator: Int? = null,
    val denominator: Int? = null,
    val fromEnd: Boolean = false,
    val allowExtension: Boolean = false,
)

data class DragTarget(val pointId: String, val x: Double, val y: Double)

data class SolveResult(
    val success: Boolean,
    val scene: ConstructionScene,
    val message: String,
    /** Constraints whose residuals failed; this is not claimed to be a minimal conflict set. */
    val conflictingConstraintIds: List<String> = emptyList(),
    /** Largest dimensionless normalized hard-constraint error. */
    val maxResidual: Double = 0.0,
    val degreesOfFreedom: Int = 0,
    /** True means the target could not be reached, but the returned geometry remains valid. */
    val dragLimited: Boolean = false,
)

object SceneValidator {
    const val MAX_POINTS = 60
    const val MAX_CONSTRAINTS = 100
    const val MAX_ENTITIES = 180
    const val MAX_MEASUREMENTS = 120
    const val MIN_LENGTH = 1e-6
    const val MAX_MAGNITUDE = 1e6
    const val MAX_DIVISIONS = 1_000_000

    fun validate(scene: ConstructionScene): List<String> = buildList {
        if (scene.points.size > MAX_POINTS) add("점은 ${MAX_POINTS}개까지 사용할 수 있습니다.")
        if (scene.constraints.size > MAX_CONSTRAINTS) add("조건은 ${MAX_CONSTRAINTS}개까지 사용할 수 있습니다.")
        if (scene.measurements.size > MAX_MEASUREMENTS) add("측정 표시는 ${MAX_MEASUREMENTS}개까지 사용할 수 있습니다.")
        if (scene.points.size + scene.segments.size + scene.circles.size > MAX_ENTITIES) {
            add("도형 개수가 허용 범위를 넘었습니다.")
        }
        val allIds = scene.points.map { it.id } + scene.segments.map { it.id } +
            scene.circles.map { it.id } + scene.constraints.map { it.id } + scene.measurements.map { it.id }
        if (allIds.any { it.isBlank() || it.length > 160 } || allIds.distinct().size != allIds.size) {
            add("도형·조건·측정 표시의 식별자는 비어 있지 않고 서로 달라야 합니다.")
        }
        val points = scene.points.associateBy { it.id }
        val segments = scene.segments.associateBy { it.id }
        val circles = scene.circles.associateBy { it.id }
        scene.points.forEach {
            if (!validNumber(it.x) || !validNumber(it.y)) add("점 ${it.id}의 좌표가 올바르지 않습니다.")
        }
        scene.segments.forEach {
            if (it.startPointId !in points || it.endPointId !in points || it.startPointId == it.endPointId) {
                add("선분 ${it.id}의 끝점 연결이 올바르지 않습니다.")
            }
        }
        scene.circles.forEach {
            if (it.centerPointId !in points || !validLength(it.radius)) add("원 ${it.id}의 중심 또는 반지름이 올바르지 않습니다.")
        }
        scene.constraints.forEach { c ->
            val refs = c.entityIds
            fun point(index: Int) = refs.getOrNull(index) in points
            fun segment(index: Int) = refs.getOrNull(index) in segments
            fun circle(index: Int) = refs.getOrNull(index) in circles
            fun angleTriple(start: Int): Boolean = refs.size >= start + 3 &&
                refs.subList(start, start + 3).let { it.all(points::containsKey) && it.distinct().size == 3 }
            val valid = when (c.type) {
                ConstraintType.FIXED_POINT -> refs.size == 1 && point(0) && validNumber(c.targetX) && validNumber(c.targetY)
                ConstraintType.COINCIDENT -> refs.size == 2 && point(0) && point(1) && refs[0] != refs[1]
                ConstraintType.POINT_ON_LINE -> refs.size == 2 && point(0) && segment(1)
                ConstraintType.POINT_ON_SEGMENT -> refs.size == 2 && point(0) && segment(1)
                ConstraintType.POINT_FRACTION -> refs.size == 2 && point(0) && segment(1) &&
                    c.denominator != null && c.denominator in 2..MAX_DIVISIONS &&
                    c.numerator != null && c.numerator in 1 until c.denominator &&
                    segments[refs[1]]!!.let { refs[0] != it.startPointId && refs[0] != it.endPointId }
                ConstraintType.POINT_DISTANCE -> refs.size == 2 && point(0) && segment(1) &&
                    validNumber(c.value) && c.value!! >= 0.0
                ConstraintType.POINT_ON_CIRCLE -> refs.size == 2 && point(0) && circle(1)
                ConstraintType.DISTANCE_POINT_LINE -> refs.size == 2 && point(0) && segment(1) &&
                    validNumber(c.value) && c.value!! >= 0.0
                ConstraintType.LENGTH -> refs.size == 1 && segment(0) && validLength(c.value)
                ConstraintType.DISTANCE_POINTS -> refs.size == 2 && point(0) && point(1) && refs[0] != refs[1] && validLength(c.value)
                ConstraintType.EQUAL_DISTANCE_POINTS -> refs.size == 4 && refs.all(points::containsKey) &&
                    refs[0] != refs[1] && refs[2] != refs[3] && refs.take(2).toSet() != refs.drop(2).toSet()
                ConstraintType.RADIUS -> refs.size == 1 && circle(0) && validLength(c.value)
                ConstraintType.HORIZONTAL, ConstraintType.VERTICAL -> refs.size == 1 && segment(0)
                ConstraintType.PARALLEL, ConstraintType.PERPENDICULAR, ConstraintType.EQUAL_LENGTH ->
                    refs.size == 2 && segment(0) && segment(1) && refs[0] != refs[1]
                ConstraintType.ANGLE -> refs.size == 2 && segment(0) && segment(1) && refs[0] != refs[1] &&
                    c.value != null && c.value.isFinite() && c.value in 0.0..180.0
                ConstraintType.LENGTH_RATIO -> refs.size == 2 && segment(0) && segment(1) && refs[0] != refs[1] && validLength(c.value)
                ConstraintType.INTERIOR_ANGLE -> refs.size == 3 && angleTriple(0) &&
                    c.value != null && c.value.isFinite() && c.value in 0.0..180.0
                ConstraintType.EQUAL_ANGLE -> refs.size == 6 && angleTriple(0) && angleTriple(3) &&
                    // The same angle, including reversed arms, is not a new relation.
                    !(refs[1] == refs[4] && setOf(refs[0], refs[2]) == setOf(refs[3], refs[5]))
            }
            val validOptions = (c.type == ConstraintType.POINT_FRACTION || c.numerator == null && c.denominator == null) &&
                (c.type == ConstraintType.POINT_DISTANCE || !c.fromEnd && !c.allowExtension)
            if (!valid || !validOptions) add("조건 ${c.id}의 대상 또는 값이 올바르지 않습니다.")
        }
        scene.measurements.forEach { measurement ->
            val refs = measurement.entityIds
            val validReferences = when (measurement.type) {
                MeasurementType.DISTANCE -> refs.size == 2 && refs.all { it in points } && refs.distinct().size == 2
                MeasurementType.ANGLE, MeasurementType.AREA -> refs.size == 3 && refs.all { it in points } && refs.distinct().size == 3
                MeasurementType.RADIUS -> refs.size == 1 && refs.single() in circles
            }
            if (!validReferences || !validNumber(measurement.offsetX) || !validNumber(measurement.offsetY)) {
                add("측정 표시 ${measurement.id}의 대상 또는 위치가 올바르지 않습니다.")
            }
            // A measured angle may become undefined when two points meet. This must not constrain
            // otherwise valid geometry; the renderer reports an undefined value for that frame.
        }
    }

    private fun validNumber(value: Double?) = value != null && value.isFinite() && kotlin.math.abs(value) <= MAX_MAGNITUDE
    private fun validLength(value: Double?) = validNumber(value) && value!! >= MIN_LENGTH
}
