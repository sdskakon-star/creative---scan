package com.kowsiq.creativescan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test
    fun undoAndRedoWalkThroughSteps() {
        val h = EditHistory(0)
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertTrue(h.push(1)); assertTrue(h.push(2)); assertTrue(h.push(3))
        assertEquals(2, h.undo()); assertEquals(1, h.undo()); assertEquals(0, h.undo())
        assertNull(h.undo()); assertFalse(h.canUndo)
        assertEquals(1, h.redo()); assertEquals(2, h.redo()); assertEquals(3, h.redo())
        assertNull(h.redo()); assertFalse(h.canRedo)
    }

    @Test
    fun newEditClearsRedo() {
        val h = EditHistory(0)
        h.push(1); h.push(2)
        h.undo()
        assertTrue(h.canRedo)
        h.push(9)
        assertFalse(h.canRedo)
        assertEquals(1, h.undo())
    }

    @Test
    fun identicalStateIsNotARecordedStep() {
        val h = EditHistory("a")
        assertFalse(h.push("a"))
        assertFalse(h.canUndo)
        h.push("b")
        assertFalse(h.push("b"))
        assertEquals("a", h.undo())
        assertNull(h.undo())
    }

    @Test
    fun revertCanBeUndone() {
        // a "revert to original" is just another step, so the previous look can be restored
        val h = EditHistory("original")
        h.push("enhanced+bright")
        h.push("original")
        assertEquals("enhanced+bright", h.undo())
    }

    @Test
    fun keepsOnlyTheNewestLimitSteps() {
        val h = EditHistory(0, limit = 50)
        for (i in 1..80) h.push(i)
        var steps = 0
        while (h.undo() != null) steps++
        assertEquals(50, steps)
        assertEquals(30, h.current)
    }
}
