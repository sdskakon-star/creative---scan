package com.kowsiq.creativescan.core

/**
 * Geometry of the two-sided ID card copy: both ID-1 sides (85.60 x 53.98 mm) are placed one
 * above the other, as a group centred on a white A4 portrait sheet rendered at 200 dpi.
 * Pure Kotlin so the layout can be unit-tested on the JVM.
 */
object IdCardLayout {
    const val PAGE_WIDTH = 1654f       // A4 width at 200 dpi
    const val PAGE_HEIGHT = 2339f      // A4 height at 200 dpi
    const val CARD_WIDTH = 674f        // 85.60 mm at 200 dpi
    const val CARD_ASPECT = 85.60 / 53.98
    const val GAP = 94f                // about 12 mm between the two sides

    val cardHeight: Float get() = CARD_WIDTH * 53.98f / 85.60f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    class Result(val front: Box, val back: Box)

    fun compute(): Result {
        val h = cardHeight
        val groupHeight = h * 2f + GAP
        val left = (PAGE_WIDTH - CARD_WIDTH) / 2f
        val top = (PAGE_HEIGHT - groupHeight) / 2f
        val front = Box(left, top, left + CARD_WIDTH, top + h)
        val backTop = top + h + GAP
        val back = Box(left, backTop, left + CARD_WIDTH, backTop + h)
        return Result(front, back)
    }
}
