package com.kowsiq.creativescan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IdCardLayoutTest {

    private val eps = 0.01f

    @Test
    fun a4SheetIs200Dpi() {
        // A4 = 210 x 297 mm  ->  1654 x 2339 px at 200 dpi
        assertEquals(210f / 25.4f * 200f, IdCardLayout.PAGE_WIDTH, 1f)
        assertEquals(297f / 25.4f * 200f, IdCardLayout.PAGE_HEIGHT, 1f)
    }

    @Test
    fun cardsHaveIsoId1PhysicalSize() {
        val l = IdCardLayout.compute()
        for (b in listOf(l.front, l.back)) {
            assertEquals(85.60f, b.width / 200f * 25.4f, 0.1f)
            assertEquals(53.98f, b.height / 200f * 25.4f, 0.1f)
            assertEquals(IdCardLayout.CARD_ASPECT, (b.width / b.height).toDouble(), 0.001)
        }
    }

    @Test
    fun pairIsCentredHorizontallyAndVertically() {
        val l = IdCardLayout.compute()
        // horizontally centred
        assertEquals(l.front.left, IdCardLayout.PAGE_WIDTH - l.front.right, eps)
        assertEquals(l.back.left, IdCardLayout.PAGE_WIDTH - l.back.right, eps)
        // the two sides line up exactly
        assertEquals(l.front.left, l.back.left, eps)
        // the whole group is centred vertically
        val topMargin = l.front.top
        val bottomMargin = IdCardLayout.PAGE_HEIGHT - l.back.bottom
        assertEquals(topMargin, bottomMargin, eps)
    }

    @Test
    fun frontIsAboveBackWithGapAndNoOverlap() {
        val l = IdCardLayout.compute()
        assertTrue(l.front.bottom < l.back.top)
        assertEquals(IdCardLayout.GAP, l.back.top - l.front.bottom, eps)
        assertTrue(l.front.top > 0f && l.back.bottom < IdCardLayout.PAGE_HEIGHT)
        assertTrue(abs(l.front.height - l.back.height) < eps)
    }
}
