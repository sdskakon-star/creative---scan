package com.kowsiq.creativescan.core

/** Linear undo/redo history of immutable snapshots (the newest [limit] steps are kept). */
class EditHistory<T>(initial: T, private val limit: Int = 50) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [state] as a new step. Returns false (and records nothing) if it equals the current one. */
    fun push(state: T): Boolean {
        if (state == current) return false
        undoStack.addLast(current)
        if (undoStack.size > limit) undoStack.removeFirst()
        current = state
        redoStack.clear()
        return true
    }

    fun undo(): T? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        current = undoStack.removeLast()
        return current
    }

    fun redo(): T? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        current = redoStack.removeLast()
        return current
    }
}
