package hammer.edit

interface Command { val label: String; fun apply(); fun revert() }

/** Undo/redo history. A new command after undo discards the redo branch. */
class CommandStack(private val limit: Int = 500) {
    private val undoList = ArrayDeque<Command>()
    private val redoList = ArrayDeque<Command>()
    val canUndo get() = undoList.isNotEmpty()
    val canRedo get() = redoList.isNotEmpty()

    fun run(c: Command) { c.apply(); undoList.addLast(c); redoList.clear(); if (undoList.size > limit) undoList.removeFirst() }
    fun undo(): Boolean { val c = undoList.removeLastOrNull() ?: return false; c.revert(); redoList.addLast(c); return true }
    fun redo(): Boolean { val c = redoList.removeLastOrNull() ?: return false; c.apply(); undoList.addLast(c); return true }
}
