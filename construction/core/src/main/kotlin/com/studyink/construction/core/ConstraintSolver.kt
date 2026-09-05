package com.studyink.construction.core

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.SingularValueDecomposition
import org.apache.commons.math3.util.Pair as MathPair
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Bounded 2-D equality-constraint editor. Apache Commons Math supplies LM and SVD; this class
 * supplies the geometric equations and editor policy, not a replacement numerical optimizer.
 *
 * Hard equations are solved first. Drag/stay terms select a nearby solution, then an SVD
 * projection removes their numerical influence. Every accepted result is independently checked.
 * Call off the UI thread and coalesce pointer input. Instances have no mutable shared state.
 */
class ConstraintSolver {
    fun solve(scene: ConstructionScene, drag: DragTarget? = null): SolveResult {
        val invalid = SceneValidator.validate(scene)
        if (invalid.isNotEmpty()) return SolveResult(false, scene, invalid.first())
        if (drag != null && (scene.point(drag.pointId) == null || !drag.x.isFinite() || !drag.y.isFinite() ||
                abs(drag.x) > SceneValidator.MAX_MAGNITUDE || abs(drag.y) > SceneValidator.MAX_MAGNITUDE)) {
            return SolveResult(false, scene, "이동할 점 또는 목표 위치가 올바르지 않습니다.")
        }
        if (scene.points.isEmpty()) return SolveResult(true, scene, "점을 그려 작도를 시작하세요.")
        val problem = Equations(scene)
        val deadline = System.nanoTime() + 1_500_000_000L
        val initial = problem.initial
        val guards = problem.branchGuards(initial)
        val base = if (problem.valid(initial)) initial else candidate(problem, initial, null, deadline)
        if (!problem.valid(base) || !problem.sameBranches(base, guards)) {
            return failure(scene, problem, base)
        }
        if (drag == null) return accepted(problem, base, false)

        val pointIndex = problem.pointIndex.getValue(drag.pointId)
        val target = doubleArrayOf((drag.x - problem.originX) / problem.scale, (drag.y - problem.originY) / problem.scale)
        val start = doubleArrayOf(base[pointIndex], base[pointIndex + 1])
        val steps = ceil(hypot(target[0] - start[0], target[1] - start[1]) / 0.12).toInt().coerceIn(1, 24)
        var current = base
        for (step in 1..steps) {
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted) break
            val t = step.toDouble() / steps
            val nextTarget = Target(pointIndex, start[0] + (target[0] - start[0]) * t, start[1] + (target[1] - start[1]) * t)
            val trial = candidate(problem, current, nextTarget, deadline)
            if (!problem.valid(trial) || !problem.sameBranches(trial, guards)) break
            current = trial
        }
        val limited = hypot(current[pointIndex] - target[0], current[pointIndex + 1] - target[1]) > 1e-5
        return accepted(problem, current, limited)
    }

    private fun accepted(problem: Equations, values: DoubleArray, limited: Boolean): SolveResult {
        val errors = problem.errors(values)
        val freedom = problem.degreesOfFreedom(values)
        return SolveResult(
            true, problem.toScene(values),
            if (limited) "조건을 유지할 수 있는 위치까지만 이동했습니다."
            else if (freedom == 0) "조건이 도형의 위치와 모양을 결정합니다."
            else "조건을 유지합니다. 움직일 수 있는 자유도: $freedom",
            maxResidual = errors.values.maxOrNull() ?: 0.0,
            degreesOfFreedom = freedom,
            dragLimited = limited,
        )
    }

    private fun failure(scene: ConstructionScene, problem: Equations, values: DoubleArray): SolveResult {
        val errors = problem.errors(values)
        val conflicts = errors.filterValues { !it.isFinite() || it > HARD_TOLERANCE }.keys.toList()
        return SolveResult(
            false, scene, "조건을 만족하는 배치를 찾지 못했습니다. 값과 연결 조건을 확인하세요.",
            conflicts, errors.values.maxOrNull() ?: Double.POSITIVE_INFINITY,
        )
    }

    private fun candidate(problem: Equations, start: DoubleArray, drag: Target?, deadline: Long): DoubleArray {
        var best = start.copyOf()
        var bestCost = Double.POSITIVE_INFINITY
        val stay = if (drag == null) 1e-5 else 1e-6
        val objective: (DoubleArray) -> DoubleArray = { x ->
            val hard = problem.residuals(x)
            DoubleArray(hard.size + x.size + if (drag == null) 0 else 2).also { residual ->
                hard.copyInto(residual)
                x.indices.forEach { residual[hard.size + it] = (x[it] - start[it]) * stay }
                if (drag != null) {
                    residual[hard.size + x.size] = (x[drag.index] - drag.x) * 1e-3
                    residual[hard.size + x.size + 1] = (x[drag.index + 1] - drag.y) * 1e-3
                }
            }
        }
        val model = MultivariateJacobianFunction { vector ->
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted) throw SolveBudgetExceeded()
            val x = vector.toArray()
            val residual = objective(x)
            val cost = residual.sumOf { it * it }
            if (cost.isFinite() && cost < bestCost) {
                bestCost = cost
                best = x.copyOf()
            }
            MathPair(ArrayRealVector(residual, false), Array2DRowRealMatrix(problem.jacobian(x, objective), false))
        }
        try {
            val fit = LeastSquaresBuilder().start(problem.seedDegenerateDistance(start)).target(DoubleArray(objective(start).size))
                .model(model).maxEvaluations(180).maxIterations(120).build()
            val optimum = LevenbergMarquardtOptimizer()
                .withInitialStepBoundFactor(2.0)
                .withCostRelativeTolerance(1e-11)
                .withParameterRelativeTolerance(1e-11)
                .optimize(fit)
            best = optimum.point.toArray()
        } catch (_: RuntimeException) {
            // Convergence limits, singular configurations and a cancelled drag preserve the best
            // trial only for verification below. Optimizer completion never implies feasibility.
        }
        return polish(problem, best, deadline)
    }

    private fun polish(problem: Equations, start: DoubleArray, deadline: Long): DoubleArray {
        var x = start
        repeat(16) {
            val residual = problem.residuals(x)
            if (residual.isEmpty() || residual.maxOf { abs(it) } < 1e-10 || System.nanoTime() > deadline) return x
            try {
                val matrix = Array2DRowRealMatrix(problem.jacobian(x, problem::residuals), false)
                val delta = SingularValueDecomposition(matrix).solver.solve(ArrayRealVector(residual, false)).toArray()
                val cost = residual.sumOf { it * it }
                var improved = false
                var amount = 1.0
                repeat(9) {
                    if (!improved) {
                        val trial = DoubleArray(x.size) { k -> x[k] - amount * delta[k] }
                        val trialCost = problem.residuals(trial).sumOf { it * it }
                        if (trial.all { it.isFinite() } && trialCost < cost) {
                            x = trial
                            improved = true
                        } else amount *= 0.5
                    }
                }
                if (!improved) return x
            } catch (_: RuntimeException) {
                return x
            }
        }
        return x
    }

    private data class Target(val index: Int, val x: Double, val y: Double)
    private class SolveBudgetExceeded : RuntimeException()

    private class Equations(val source: ConstructionScene) {
        val pointIndex = source.points.mapIndexed { index, point -> point.id to index * 2 }.toMap()
        private val segments = source.segments.associateBy { it.id }
        private val circles = source.circles.associateBy { it.id }
        private val circleIndex = source.circles.mapIndexed { index, circle -> circle.id to source.points.size * 2 + index }.toMap()
        private val active = source.constraints.filter { it.enabled }
        val originX = source.points.first().x
        val originY = source.points.first().y
        val scale = max(1.0, max(
            source.points.maxOf { max(abs(it.x - originX), abs(it.y - originY)) },
            max(source.circles.maxOfOrNull { it.radius } ?: 0.0,
                active.filter { it.type == ConstraintType.LENGTH || it.type == ConstraintType.RADIUS || it.type == ConstraintType.DISTANCE_POINT_LINE }.maxOfOrNull { it.value ?: 0.0 } ?: 0.0),
        ))
        val initial = DoubleArray(source.points.size * 2 + source.circles.size).also { x ->
            source.points.forEach { p -> val i = pointIndex.getValue(p.id); x[i] = (p.x - originX) / scale; x[i + 1] = (p.y - originY) / scale }
            source.circles.forEach { x[circleIndex.getValue(it.id)] = it.radius / scale }
        }
        private val angleSigns = active.filter { it.type == ConstraintType.ANGLE }.associate { c ->
            val a = direction(initial, c.entityIds[0]); val b = direction(initial, c.entityIds[1])
            c.id to if (cross(a, b) < -1e-12) -1.0 else 1.0
        }
        private val distanceSigns = active.filter { it.type == ConstraintType.DISTANCE_POINT_LINE }.associate { c ->
            c.id to if (lineDistance(initial, c.entityIds[0], c.entityIds[1]) < -1e-12) -1.0 else 1.0
        }
        private val rowOwners = active.flatMap { c -> List(if (c.type == ConstraintType.FIXED_POINT || c.type == ConstraintType.COINCIDENT) 2 else 1) { c.id } }
        private val angularRows = rowOwners.mapIndexedNotNull { index, id -> if (active.any { it.id == id && it.type == ConstraintType.ANGLE }) index else null }.toSet()

        fun residuals(x: DoubleArray): DoubleArray {
            val output = ArrayList<Double>(rowOwners.size)
            active.forEach { c ->
                val ids = c.entityIds
                when (c.type) {
                    ConstraintType.FIXED_POINT -> {
                        val p = point(x, ids[0]); output += p[0] - (c.targetX!! - originX) / scale; output += p[1] - (c.targetY!! - originY) / scale
                    }
                    ConstraintType.COINCIDENT -> {
                        val a = point(x, ids[0]); val b = point(x, ids[1]); output += a[0] - b[0]; output += a[1] - b[1]
                    }
                    ConstraintType.POINT_ON_LINE -> output += lineDistance(x, ids[0], ids[1])
                    ConstraintType.DISTANCE_POINT_LINE -> output += lineDistance(x, ids[0], ids[1]) - c.value!! / scale * distanceSigns.getValue(c.id)
                    ConstraintType.POINT_ON_CIRCLE -> {
                        val p = point(x, ids[0]); val center = point(x, circles.getValue(ids[1]).centerPointId)
                        output += hypot(p[0] - center[0], p[1] - center[1]) - x[circleIndex.getValue(ids[1])]
                    }
                    ConstraintType.LENGTH -> output += norm(direction(x, ids[0])) - c.value!! / scale
                    ConstraintType.RADIUS -> output += x[circleIndex.getValue(ids[0])] - c.value!! / scale
                    ConstraintType.EQUAL_LENGTH -> output += norm(direction(x, ids[0])) - norm(direction(x, ids[1]))
                    ConstraintType.HORIZONTAL, ConstraintType.VERTICAL -> {
                        val d = direction(x, ids[0])
                        output += d[if (c.type == ConstraintType.HORIZONTAL) 1 else 0] / max(norm(d), 1e-12)
                    }
                    ConstraintType.PARALLEL, ConstraintType.PERPENDICULAR, ConstraintType.ANGLE -> {
                        val a = direction(x, ids[0]); val b = direction(x, ids[1]); val denominator = max(norm(a) * norm(b), 1e-18)
                        val cross = cross(a, b); val dot = a[0] * b[0] + a[1] * b[1]
                        output += when (c.type) {
                            ConstraintType.PARALLEL -> cross / denominator
                            ConstraintType.PERPENDICULAR -> dot / denominator
                            else -> wrapAngle(atan2(cross, dot) - Math.toRadians(c.value!!) * angleSigns.getValue(c.id))
                        }
                    }
                }
            }
            return output.toDoubleArray()
        }

        /** At zero distance the norm has no unique derivative. A deterministic, small seed
         * selects one of the equally near directions; final feasibility still decides acceptance. */
        fun seedDegenerateDistance(start: DoubleArray): DoubleArray {
            val x = start.copyOf()
            val fixed = active.filter { it.type == ConstraintType.FIXED_POINT }.map { it.entityIds[0] }.toSet()
            active.forEach { c ->
                val ends = when (c.type) {
                    ConstraintType.LENGTH -> segments.getValue(c.entityIds[0]).let { it.startPointId to it.endPointId }
                    ConstraintType.POINT_ON_CIRCLE -> circles.getValue(c.entityIds[1]).centerPointId to c.entityIds[0]
                    else -> null
                }
                if (ends != null && ends.first != ends.second) {
                    val a = pointIndex.getValue(ends.first); val b = pointIndex.getValue(ends.second)
                    if (hypot(x[a] - x[b], x[a + 1] - x[b + 1]) < 1e-12) {
                        val target = if (c.type == ConstraintType.LENGTH) c.value!! / scale else x[circleIndex.getValue(c.entityIds[1])]
                        if (ends.second !in fixed) x[b] += max(target * 0.05, 1e-5)
                        else if (ends.first !in fixed) x[a] -= max(target * 0.05, 1e-5)
                    }
                }
            }
            return x
        }

        fun jacobian(x: DoubleArray, function: (DoubleArray) -> DoubleArray): Array<DoubleArray> {
            val rows = function(x).size
            val matrix = Array(rows) { DoubleArray(x.size) }
            val test = x.copyOf()
            x.indices.forEach { column ->
                val h = 1e-6 * max(1.0, abs(x[column]))
                test[column] = x[column] + h; val plus = function(test)
                test[column] = x[column] - h; val minus = function(test)
                test[column] = x[column]
                for (row in 0 until rows) {
                    val difference = plus[row] - minus[row]
                    matrix[row][column] = (if (row in angularRows) wrapAngle(difference) else difference) / (2 * h)
                }
            }
            return matrix
        }

        fun errors(x: DoubleArray): Map<String, Double> {
            val errors = linkedMapOf<String, Double>()
            residuals(x).forEachIndexed { index, value ->
                val owner = rowOwners[index]
                errors[owner] = max(errors[owner] ?: 0.0, if (value.isFinite()) abs(value) else Double.POSITIVE_INFINITY)
            }
            active.forEach { c ->
                val directions = when (c.type) {
                    ConstraintType.POINT_ON_LINE, ConstraintType.DISTANCE_POINT_LINE -> listOf(c.entityIds[1])
                    ConstraintType.HORIZONTAL, ConstraintType.VERTICAL -> c.entityIds
                    ConstraintType.PARALLEL, ConstraintType.PERPENDICULAR, ConstraintType.ANGLE -> c.entityIds
                    else -> emptyList()
                }
                if (directions.any { norm(direction(x, it)) * scale < SceneValidator.MIN_LENGTH }) errors[c.id] = Double.POSITIVE_INFINITY
            }
            return errors
        }

        fun valid(x: DoubleArray): Boolean = x.all { it.isFinite() } &&
            source.points.all { p -> val i = pointIndex.getValue(p.id); abs(x[i] * scale + originX) <= SceneValidator.MAX_MAGNITUDE && abs(x[i + 1] * scale + originY) <= SceneValidator.MAX_MAGNITUDE } &&
            source.circles.all { x[circleIndex.getValue(it.id)] * scale in SceneValidator.MIN_LENGTH..SceneValidator.MAX_MAGNITUDE } &&
            errors(x).values.all { it <= HARD_TOLERANCE }

        fun toScene(x: DoubleArray) = source.copy(
            points = source.points.map { p -> val i = pointIndex.getValue(p.id); p.copy(x = x[i] * scale + originX, y = x[i + 1] * scale + originY) },
            circles = source.circles.map { it.copy(radius = x[circleIndex.getValue(it.id)] * scale) },
        )

        fun degreesOfFreedom(x: DoubleArray): Int {
            if (active.isEmpty()) return x.size
            return try {
                val singular = SingularValueDecomposition(Array2DRowRealMatrix(jacobian(x, ::residuals), false)).singularValues
                val threshold = max(1e-7, (singular.maxOrNull() ?: 0.0) * 1e-7)
                (x.size - singular.count { it > threshold }).coerceAtLeast(0)
            } catch (_: RuntimeException) { x.size }
        }

        /** Preserve the elbow/intersection side for two distance links at a shared point.
         * We deliberately do not freeze unrelated triangle orientations in a flexible sketch. */
        fun branchGuards(x: DoubleArray): List<BranchGuard> {
            val neighbors = mutableMapOf<String, MutableSet<String>>()
            val representatives = source.points.associate { it.id to it.id }.toMutableMap()
            fun representative(id: String): String {
                var current = id
                while (representatives.getValue(current) != current) current = representatives.getValue(current)
                return current
            }
            active.filter { it.type == ConstraintType.COINCIDENT }.forEach {
                val a = representative(it.entityIds[0]); val b = representative(it.entityIds[1])
                if (a != b) representatives[b] = a
            }
            fun link(a: String, b: String) {
                val left = representative(a); val right = representative(b)
                if (left == right) return
                neighbors.getOrPut(left) { linkedSetOf() }.add(right)
                neighbors.getOrPut(right) { linkedSetOf() }.add(left)
            }
            active.forEach { c ->
                if (c.type == ConstraintType.LENGTH) segments.getValue(c.entityIds[0]).let { link(it.startPointId, it.endPointId) }
                if (c.type == ConstraintType.POINT_ON_CIRCLE) link(c.entityIds[0], circles.getValue(c.entityIds[1]).centerPointId)
            }
            return buildList {
                neighbors.forEach { (joint, connected) ->
                    val ids = connected.sorted()
                    for (a in ids.indices) for (b in a + 1 until ids.size) {
                        val value = triangleSign(x, ids[a], joint, ids[b])
                        if (abs(value) > 1e-7) add(BranchGuard(ids[a], joint, ids[b], if (value > 0) 1.0 else -1.0))
                    }
                }
            }
        }

        fun sameBranches(x: DoubleArray, guards: List<BranchGuard>) = guards.all { triangleSign(x, it.a, it.joint, it.b) * it.sign >= -1e-7 }
        private fun triangleSign(x: DoubleArray, a: String, joint: String, b: String): Double {
            val p = point(x, a); val q = point(x, joint); val r = point(x, b)
            return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
        }
        private fun point(x: DoubleArray, id: String): DoubleArray = pointIndex.getValue(id).let { doubleArrayOf(x[it], x[it + 1]) }
        private fun lineDistance(x: DoubleArray, pointId: String, lineId: String): Double {
            val line = segments.getValue(lineId); val p = point(x, pointId); val a = point(x, line.startPointId); val d = direction(x, line.id)
            return ((p[0] - a[0]) * d[1] - (p[1] - a[1]) * d[0]) / max(norm(d), 1e-12)
        }
        private fun direction(x: DoubleArray, id: String): DoubleArray {
            val s = segments.getValue(id); val a = pointIndex.getValue(s.startPointId); val b = pointIndex.getValue(s.endPointId)
            return doubleArrayOf(x[b] - x[a], x[b + 1] - x[a + 1])
        }
        private fun norm(a: DoubleArray) = hypot(a[0], a[1])
        private fun cross(a: DoubleArray, b: DoubleArray) = a[0] * b[1] - a[1] * b[0]
        data class BranchGuard(val a: String, val joint: String, val b: String, val sign: Double)
    }

    companion object {
        const val HARD_TOLERANCE = 1e-7
        private fun wrapAngle(angle: Double) = atan2(sin(angle), cos(angle))
    }
}
