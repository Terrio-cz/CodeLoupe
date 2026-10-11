package codeloupe.conventions

import codeloupe.lang.DeclFact

/** The kinds of top-level types in main, their visibility and how many public ones carry a KDoc. */
object TypeShapePart : CardPart {
    override fun line(context: CardContext, width: Int): String? {
        val types = context.main.flatMap { it.types }
        if (types.isEmpty()) return null
        val shapes = Tally()
        types.forEach { shapes.add(shape(it)) }
        val kinds = shapes.top(width + 3).joinToString(", ") { "${it.key} ${it.count}" }
        val internal = types.count { "internal" in it.modifiers }
        val private = types.count { "private" in it.modifiers }
        val public = types.filter { "internal" !in it.modifiers && "private" !in it.modifiers }
        val visibility = listOfNotNull(internal.takeIf { it > 0 }?.let { "$it internal" }, private.takeIf { it > 0 }?.let { "$it private" })
        val documented = public.count { it.start < it.declStart }
        val tail = (visibility + "KDoc on $documented of ${public.size} public").joinToString(", ")
        return "Types (main, ${types.size} top-level): $kinds; $tail."
    }

    private fun shape(type: DeclFact): String {
        val words = type.modifiers
        return when {
            type.kind == "enum" -> "enum"
            type.kind == "annotation" -> "annotation"
            type.kind == "object" -> "object"
            "sealed" in words -> "sealed"
            type.kind == "interface" -> "interface"
            "data" in words -> "data class"
            "value" in words -> "value class"
            "abstract" in words -> "abstract class"
            else -> "class"
        }
    }
}
