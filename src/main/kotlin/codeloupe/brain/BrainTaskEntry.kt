package codeloupe.brain

/**
 * One line of a task file (`brain/tasks/<id>.md`). [marker] is the notation's kind character(s) (`=`, `~`, `!P2`, `->` ...), empty for a line without one;
 * [at] and [who] come from the `## <time> <who>` header of the section the line sits in; [line] is its line number in the file, the key the brain index uses.
 */
class BrainTaskEntry(val line: Int, val at: String, val who: String, val marker: String, val text: String, val rejected: Boolean)
