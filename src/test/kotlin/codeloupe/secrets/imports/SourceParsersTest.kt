package codeloupe.secrets.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceParsersTest {
    @Test
    fun `a dotenv file gives names and values with their offsets, quotes and comments handled`() {
        val text = "# header\r\nA=plain-1\r\nexport B = \"dq value # not a comment\"\r\nC='sq\\value'\nD=unquoted-4 # inline comment\nE=\n  F=with spaces  \nnot a statement\nG=\"line one\nline two\"\nH=after\n"
        val entries = DotenvParser.parse(text).associateBy { it.name }
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), entries.keys.toList())
        assertEquals("plain-1", entries.getValue("A").value)
        assertEquals("dq value # not a comment", entries.getValue("B").value)
        assertEquals("sq\\value", entries.getValue("C").value)
        assertEquals("unquoted-4", entries.getValue("D").value)
        assertEquals("", entries.getValue("E").value)
        assertEquals("with spaces", entries.getValue("F").value)
        assertEquals("line one\nline two", entries.getValue("G").value)
        assertEquals("after", entries.getValue("H").value)
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 9, 11), entries.values.map { it.line })
        val a = entries.getValue("A")
        assertEquals("A=plain-1", text.substring(a.start, a.end))
        val b = entries.getValue("B")
        assertEquals("export B = \"dq value # not a comment\"", text.substring(b.start, b.end))
        val d = entries.getValue("D")
        assertEquals("D=unquoted-4", text.substring(d.start, d.end))
    }

    @Test
    fun `a byte order mark and an unterminated quote do not hide or swallow statements`() {
        val entries = DotenvParser.parse("\uFEFFFIRST=1\nSECOND=\"open\nTHIRD=3\n").associateBy { it.name }
        assertEquals("1", entries.getValue("FIRST").value)
        assertEquals("\"open", entries.getValue("SECOND").value)
        assertEquals("3", entries.getValue("THIRD").value)
    }

    @Test
    fun `env objects are found at every depth of a Claude JSON file and only strings count`() {
        val text = """{"env":{"TOP":"t-1","N":5},"mcpServers":{"yt":{"command":"x","env":{"YT":"a\"b\u0041"}}},"projects":{"C:\\p":{"mcpServers":{"s":{"env":{"S":"s-1"}}}}}}"""
        val sites = JsonEnvSites.find(text)!!.associateBy { it.name }
        assertEquals(setOf("TOP", "YT", "S"), sites.keys)
        assertEquals("a\"bA", sites.getValue("YT").value)
        assertEquals("env > TOP", sites.getValue("TOP").path)
        assertEquals("mcpServers > yt > env > YT", sites.getValue("YT").path)
        val top = sites.getValue("TOP")
        assertEquals("\"t-1\"", text.substring(top.start, top.end))
    }

    @Test
    fun `malformed json is not read and a byte order mark is tolerated`() {
        assertNull(JsonEnvSites.find("{\"env\": {\"A\": \"1\""))
        assertNull(JsonEnvSites.find("{\"env\": {} } trailing"))
        assertTrue(JsonEnvSites.find("\uFEFF{\"env\":{\"A\":\"1\"}}")!!.single().name == "A")
    }

    @Test
    fun `references are recognised`() {
        assertTrue(SourceRewriter.isReference("\${YOUTRACK_TOKEN}"))
        assertTrue(SourceRewriter.isReference("\${X:-default}"))
        assertTrue(!SourceRewriter.isReference("pre-\${X}"))
    }
}
