package com.kowsiq.creativescan.core

import java.io.File

enum class ScanFilter(val label: String) {
    ORIGINAL("Original Color"),
    ENHANCED("Enhanced Color"),
    GRAYSCALE("Grayscale"),
    BLACK_WHITE("Black & White"),
    DOCUMENT_CLEAN("Document Clean"),
    PHOTO("Photo Mode")
}

/** A point normalised to 0..1 relative to the image width/height. */
data class NPoint(val x: Float, val y: Float)

/**
 * Everything the enhancement engine needs besides the image and its corners.
 * brightness: -100..100, contrast: 0.5..1.5, sharpness: 0..1, rotation: 0/90/180/270.
 */
data class ProcessSettings(
    val filter: ScanFilter = ScanFilter.ENHANCED,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val sharpness: Float = 0.3f,
    val autoStraighten: Boolean = true,
    val rotation: Int = 0
)

/** Result of analysing one camera frame. Corners are TL, TR, BR, BL (normalised). */
data class LiveResult(
    val corners: List<NPoint>?,
    val areaFraction: Double,
    val shadowy: Boolean
)

/** One page of the current scan session. The original capture file is never modified. */
data class ScanPage(
    val id: String,
    val file: File,
    val corners: List<NPoint>,
    val settings: ProcessSettings,
    /** True when this raster already represents a complete physical A4 sheet. */
    val isIdCardCopy: Boolean = false
)
