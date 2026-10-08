package codeloupe.hooks

import kotlin.test.Test
import kotlin.test.assertEquals

class PatternShapeTest {
    private fun shape(pattern: String, fixed: Boolean = false) = PatternShape.of(pattern, fixed)

    @Test
    fun `declarations`() {
        assertEquals(PatternShape.Declaration("Foo", null), shape("class Foo"))
        assertEquals(PatternShape.Declaration("Foo", null), shape("""^\s*(data )?class Foo""".replace("(data )?", "data ")))
        assertEquals(PatternShape.Declaration("Foo", "interface"), shape("interface Foo"))
        assertEquals(PatternShape.Declaration("run", "fun"), shape("""fun\s+run\b"""))
        assertEquals(PatternShape.Declaration("run", "fun"), shape("""private suspend fun run("""))
        assertEquals(PatternShape.Declaration("limit", "property"), shape("val limit"))
        assertEquals(PatternShape.Declaration("Id", "typealias"), shape("typealias Id"))
    }

    @Test
    fun `names`() {
        assertEquals(PatternShape.Name("OrderService"), shape("OrderService"))
        assertEquals(PatternShape.Name("OrderService"), shape("""\bOrderService\b"""))
        assertEquals(PatternShape.Name("parseConfig"), shape("""parseConfig\("""))
        assertEquals(PatternShape.Name("parseConfig"), shape("parseConfig(", fixed = true))
        assertEquals(PatternShape.Name("MAX_SIZE"), shape("MAX_SIZE"))
        assertEquals(PatternShape.Name("Order.total"), shape("Order.total"))
    }

    @Test
    fun `everything else is text`() {
        assertEquals(PatternShape.Text("timeout", false), shape("timeout"))
        assertEquals(PatternShape.Text("order_id", false), shape("order_id"))
        assertEquals(PatternShape.Text("Foo|Bar", true), shape("Foo|Bar"))
        assertEquals(PatternShape.Text("class Foo|Bar", true), shape("class Foo|Bar"))
        assertEquals(PatternShape.Text("SELECT * FROM t", false), shape("SELECT * FROM t", fixed = true))
        assertEquals(PatternShape.Text("""@Get\("/x"\)""", true), shape("""@Get\("/x"\)"""))
        assertEquals(PatternShape.Text("a.b", true), shape("a.b"))
    }
}
