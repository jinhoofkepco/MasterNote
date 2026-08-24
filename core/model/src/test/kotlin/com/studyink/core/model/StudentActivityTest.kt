package com.studyink.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentActivityTest {
    @Test
    fun aStraightLineIsAsDirectAsItGets() {
        val shape = strokeShape(line(from = 0f, to = 200f, y = 100f))

        assertEquals(200f, shape.pathLength, 0.5f)
        assertEquals(0, shape.reversals)
        assertTrue(shape.directness > 0.99f)
        assertFalse(isFillStroke(shape))
    }

    @Test
    fun colouringInFoldsBackOverItsOwnGround() {
        // A pen driven left and right over the same band, the way a page gets coloured in.
        val points = mutableListOf<PagePoint>()
        var y = 100f
        repeat(8) { pass ->
            val forward = pass % 2 == 0
            val xs = if (forward) 0..60 step 10 else 60 downTo 0 step 10
            xs.forEach { x -> points += PagePoint(x.toFloat(), y) }
            y += 3f
        }
        val shape = strokeShape(points)

        assertTrue("reversals=${shape.reversals}", shape.reversals >= FILL_MINIMUM_REVERSALS)
        assertTrue("directness=${shape.directness}", shape.directness <= FILL_MAXIMUM_DIRECTNESS)
        assertTrue(isFillStroke(shape))
    }

    @Test
    fun handwritingReversesWithoutCountingAsColouring() {
        // A digit-sized stroke that doubles back once, like the middle of a 4 or a Korean jamo.
        val points = listOf(
            PagePoint(0f, 0f), PagePoint(0f, 30f), PagePoint(0f, 60f),
            PagePoint(0f, 30f), PagePoint(20f, 30f), PagePoint(40f, 30f),
        )
        val shape = strokeShape(points)

        assertFalse("a short doubled-back stroke is writing, not filling", isFillStroke(shape))
    }

    @Test
    fun aShortScribbleIsBelowTheLengthFloor() {
        // Same shape as colouring but tiny: dotting or hatching a character must not be flagged.
        val points = mutableListOf<PagePoint>()
        repeat(8) { pass ->
            val xs = if (pass % 2 == 0) 0..6 step 2 else 6 downTo 0 step 2
            xs.forEach { x -> points += PagePoint(x.toFloat(), pass.toFloat()) }
        }
        val shape = strokeShape(points)

        assertTrue(shape.reversals >= FILL_MINIMUM_REVERSALS)
        assertTrue("pathLength=${shape.pathLength}", shape.pathLength < FILL_MINIMUM_PATH_LENGTH)
        assertFalse(isFillStroke(shape))
    }

    @Test
    fun aSampleSplitsInkBetweenWritingAndFilling() {
        val writing = line(from = 0f, to = 200f, y = 10f)
        val filling = mutableListOf<PagePoint>().apply {
            var y = 100f
            repeat(8) { pass ->
                val xs = if (pass % 2 == 0) 0..60 step 10 else 60 downTo 0 step 10
                xs.forEach { x -> add(PagePoint(x.toFloat(), y)) }
                y += 3f
            }
        }

        val sample = summariseActivity(1_000L, pageNumber = 4, strokes = listOf(writing, filling))

        assertEquals(2, sample.strokeCount)
        assertFalse(sample.isIdle)
        val fillLength = strokeShape(filling).pathLength
        assertEquals(fillLength / sample.inkLength, sample.fillRatio, 0.01f)
    }

    @Test
    fun anEmptyBucketReadsAsIdleRatherThanAsColouring() {
        val sample = summariseActivity(1_000L, pageNumber = 0, strokes = emptyList())

        assertTrue(sample.isIdle)
        assertEquals(0f, sample.inkLength, 0f)
        assertEquals(0f, sample.fillRatio, 0f)
    }

    @Test
    fun theWindowKeepsOnlyTheMostRecentBuckets() {
        val samples = (1..10).map {
            StudentActivitySample(it.toLong(), pageNumber = 0, strokeCount = it, inkLength = it.toFloat(), fillRatio = 0f)
        }

        val trimmed = samples.trimmedTo(4)

        assertEquals(listOf(7, 8, 9, 10), trimmed.map { it.strokeCount })
        assertEquals(10f, trimmed.peakInkLength(), 0f)
    }

    @Test
    fun anAllIdleWindowStillScalesTheGraph() {
        val idle = List(3) { StudentActivitySample(it.toLong(), 0, 0, 0f, 0f) }

        assertEquals(1f, idle.peakInkLength(), 0f)
    }

    private fun line(from: Float, to: Float, y: Float): List<PagePoint> =
        generateSequence(from) { it + 20f }.takeWhile { it <= to }.map { PagePoint(it, y) }.toList()
}
