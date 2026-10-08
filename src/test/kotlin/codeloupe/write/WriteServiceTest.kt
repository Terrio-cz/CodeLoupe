package codeloupe.write

import codeloupe.config.WriteConfig
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `edit` operations of [WriteService] against real repositories: the guards, the answers, the journal. */
class WriteServiceTest {
    private val kotlin by lazy { WriteHarness("write/kotlin") }
    private val java by lazy { WriteHarness("write/java") }
    private val account = "src/main/kotlin/com/example/bank/Account.kt"

    private fun refusal(harness: WriteHarness, block: suspend () -> String): String = assertFailsWith<WriteRefused> { harness.blocking(block) }.message.orEmpty()

    @Test
    fun `a declaration is replaced when its hash is the one read, and the answer carries the next hash`() {
        val read = kotlin.read("Account.deposit")
        val code = read.code.replace("history += Entry(id, Kind.CREDIT, amount)", "history += Entry(id, Kind.CREDIT, amount)\n        touched = true")
        val answer = kotlin.blocking { kotlin.service.replace(kotlin.root, "Account.deposit", read.hash, code.replace("fun deposit", "fun deposit")) }
        assertContains(answer, "replaced com.example.bank.Account.deposit")
        assertContains(kotlin.text(account), "        touched = true")
        assertFalse(read.hash in answer, "the hash changed with the text")
        // The index sees the edit at once, and the next edit goes with the hash of the answer.
        assertContains(kotlin.read("Account.deposit").code, "touched = true")
        val next = Regex("hash=([0-9a-f]{10})").find(answer)!!.groupValues[1]
        assertEquals(next, kotlin.read("Account.deposit").hash)
    }

    @Test
    fun `a declaration changed since it was read is refused, a missing hash too`() {
        val read = kotlin.read("Account.describe")
        kotlin.repo.resolve(account).writeText(kotlin.text(account).replace("account \$id: ", "acct \$id: "))
        val stale = refusal(kotlin) { kotlin.service.replace(kotlin.root, "Account.describe", read.hash, read.code) }
        assertContains(stale, "hash mismatch")
        assertContains(stale, "read it again")
        assertContains(refusal(kotlin) { kotlin.service.replace(kotlin.root, "Account.describe", null, read.code) }, "pass hash")
    }

    @Test
    fun `code that would break the file is refused and the file stays as it was`() {
        val before = kotlin.snapshot()
        val read = kotlin.read("Account.withdraw")
        val broken = listOf(
            "unbalanced" to read.code.replace("        if (balance < amount) return false", "        if (balance < amount) { return false"),
            "no declaration" to "// nothing here",
            "swallows the next declaration" to read.code.removeSuffix("}") + "}}\nfun leftover() {",
            "duplicate" to read.code + "\n\n" + read.code,
        )
        for ((why, code) in broken) {
            val message = refusal(kotlin) { kotlin.service.replace(kotlin.root, "Account.withdraw", read.hash, code) }
            assertContains(message, "not written", message = why)
            assertEquals(before.mapValues { it.value.size }, kotlin.snapshot().mapValues { it.value.size }, why)
        }
        assertEquals(before, kotlin.snapshot())
        assertTrue(kotlin.journal.read().isEmpty(), "a refused write leaves no journal entry")
    }

    @Test
    fun `insert_member, insert_after, insert_before and delete work in Kotlin and Java`() {
        val k = kotlin.blocking { kotlin.service.insertMember(kotlin.root, "Account", kotlin.read("Account").hash, "fun overdrawn() = balance.cents < 0", "after_properties") }
        assertContains(k, "inserted")
        assertContains(kotlin.text(account), "    private val history = mutableListOf<Entry>()\n\n    fun overdrawn() = balance.cents < 0\n\n    open fun describe()")
        kotlin.blocking { kotlin.service.insertAfter(kotlin.root, "Account.overdrawn", kotlin.read("Account.overdrawn").hash, "fun solvent() = !overdrawn()") }
        kotlin.blocking { kotlin.service.insertBefore(kotlin.root, "Account.solvent", kotlin.read("Account.solvent").hash, "/** Is it in the red. */\nfun red() = overdrawn()") }
        assertContains(kotlin.text(account), "fun overdrawn() = balance.cents < 0\n\n    /** Is it in the red. */\n    fun red() = overdrawn()\n\n    fun solvent() = !overdrawn()")
        kotlin.blocking { kotlin.service.delete(kotlin.root, "Account.red", kotlin.read("Account.red").hash) }
        assertFalse("fun red()" in kotlin.text(account))
        assertEquals(emptyList(), Compile.kotlin(kotlin.repo))

        val cart = "src/main/java/com/example/shop/Cart.java"
        java.blocking { java.service.insertMember(java.root, "Cart", java.read("Cart").hash, "public int size() {\n    return items.size();\n}", "end") }
        assertContains(java.text(cart), "        return status;\n    }\n\n    public void pay()")
        assertContains(java.text(cart), "    public int size() {\n        return items.size();\n    }\n")
        java.blocking { java.service.replace(java.root, "Cart.total", java.read("Cart.total").hash, "public long total() {\n    return items.stream().mapToLong(Item::price).sum();\n}") }
        assertEquals(emptyList(), Compile.java(java.repo))
    }

    @Test
    fun `imports are added once, in order, and create_file honours the package and the folder`() {
        val added = kotlin.blocking { kotlin.service.addImports(kotlin.root, account, listOf("java.util.UUID", "kotlin.math.max", "kotlin.collections.List")) }
        assertContains(added, "added")
        assertContains(kotlin.text(account), "package com.example.bank\n\nimport java.util.UUID\nimport kotlin.math.max\n\n/** A bank account")
        assertContains(kotlin.blocking { kotlin.service.addImports(kotlin.root, account, listOf("java.util.UUID")) }, "unchanged")
        assertEquals(emptyList(), Compile.kotlin(kotlin.repo))

        val created = kotlin.blocking {
            kotlin.service.createFile(kotlin.root, "src/main/kotlin/com/example/bank/Fee.kt", "package com.example.bank\n\nfun fee(amount: Money): Money = Money(amount.cents / 100)\n")
        }
        assertContains(created, "created src/main/kotlin/com/example/bank/Fee.kt")
        assertEquals(emptyList(), Compile.kotlin(kotlin.repo))
        assertContains(refusal(kotlin) { kotlin.service.createFile(kotlin.root, "src/main/kotlin/com/example/bank/Other.kt", "package com.example.wrong\n\nfun x() = 1\n") }, "package of")
        assertContains(refusal(kotlin) { kotlin.service.createFile(kotlin.root, "src/main/kotlin/com/example/bank/Fee.kt", "package com.example.bank\n\nfun y() = 1\n") }, "exists")
        assertContains(refusal(java) { java.service.createFile(java.root, "src/main/java/com/example/shop/Other.java", "package com.example.shop;\n\npublic class Different {\n}\n") }, "named like the file")
        java.blocking { java.service.createFile(java.root, "src/main/java/com/example/shop/Gift.java", "package com.example.shop;\n\npublic record Gift(Item item) {\n}\n") }
        java.blocking { java.service.addImports(java.root, "src/main/java/com/example/shop/Gift.java", listOf("java.util.List", "static java.lang.Math.max")) }
        assertContains(java.text("src/main/java/com/example/shop/Gift.java"), "import java.util.List;")
        assertContains(java.text("src/main/java/com/example/shop/Gift.java"), "import static java.lang.Math.max;")
        assertEquals(emptyList(), Compile.java(java.repo))
    }

    @Test
    fun `the write policy keeps the daemon out of the main checkout, of denied paths and of files in conflict`() {
        val linked = WriteHarness("write/kotlin", config = WriteConfig(linkedWorktreesOnly = true))
        val read = linked.read("Account.describe")
        assertContains(refusal(linked) { linked.service.replace(linked.root, "Account.describe", read.hash, read.code) }, "linked worktrees only")
        val denied = WriteHarness("write/kotlin", config = WriteConfig(deny = listOf("**/bank/Money.kt", "src/main/kotlin/com/example/bank/E*.kt")))
        val money = denied.read("Money.format")
        assertContains(refusal(denied) { denied.service.replace(denied.root, "Money.format", money.hash, money.code) }, "denied by the write policy")
        assertContains(refusal(denied) { denied.service.createFile(denied.root, "src/main/kotlin/com/example/bank/Extra.kt", "package com.example.bank\n\nfun e() = 1\n") }, "denied by the write policy")

        val conflicted = WriteHarness("write/kotlin")
        val file = conflicted.repo.resolve(account)
        file.writeText(conflicted.text(account).replace("    fun entries()", "<<<<<<< HEAD\n    fun entries()\n=======\n    fun entries2()\n>>>>>>> other\n    fun entries3()"))
        assertContains(refusal(conflicted) { conflicted.service.insertMember(conflicted.root, "Account", "0000000000", "fun x() = 1", "end") }, "conflict markers")

        val ownRules = WriteHarness("write/kotlin", extra = mapOf(".codeloupe.json" to """{"write": {"deny": ["**/Entry.kt"]}}"""))
        val entry = ownRules.read("Entry.signed")
        assertContains(refusal(ownRules) { ownRules.service.replace(ownRules.root, "Entry.signed", entry.hash, entry.code) }, "denied by the write policy")
    }

    @Test
    fun `what was written is in the journal, with the hashes before and after`() {
        val before = kotlin.snapshot()
        val read = kotlin.read("Account.entries")
        kotlin.blocking { kotlin.service.replace(kotlin.root, "Account.entries", read.hash, read.code.replace("toList()", "toList().asReversed()")) }
        val record = kotlin.journal.read().single()
        assertEquals("replace", record.op)
        assertEquals(account, record.files.single().path)
        assertEquals(codeloupe.platform.Sha1.hex(before.getValue(account).toByteArray()), record.files.single().before)
        assertEquals(codeloupe.platform.Sha1.hex(Files.readAllBytes(kotlin.repo.resolve(account))), record.files.single().after)
        assertEquals(emptyList(), Files.list(kotlin.repo.resolve("src/main/kotlin/com/example/bank")).use { paths -> paths.filter { it.toString().endsWith("codeloupe-tmp") }.toList() }, "no temporary file is left")
    }

    @Test
    fun `a file that changed while the edit was made is not overwritten`() {
        val source = SourceText.read(kotlin.repo.resolve(account))!!
        val applier = WriteApplier(WritePolicy(WriteConfig()), kotlin.journal)
        kotlin.repo.resolve(account).writeText(kotlin.text(account) + "\n// someone else\n")
        val failure = assertFailsWith<WriteRefused> {
            applier.apply("test", kotlin.root, kotlin.repo, kotlin.repo, listOf(FileChange(account, source, source.text.replace("deposit", "deposit2"))), "x")
        }
        assertContains(failure.message.orEmpty(), "changed while")
        assertContains(kotlin.text(account), "// someone else")
        assertFalse("deposit2" in kotlin.text(account))
    }

    @Test
    fun `several files are written together or not at all`() {
        val applier = WriteApplier(WritePolicy(WriteConfig()), kotlin.journal)
        val money = SourceText.read(kotlin.repo.resolve("src/main/kotlin/com/example/bank/Money.kt"))!!
        val entry = SourceText.read(kotlin.repo.resolve("src/main/kotlin/com/example/bank/Entry.kt"))!!
        val before = kotlin.snapshot()
        val failure = assertFailsWith<WriteRefused> {
            applier.apply(
                "test", kotlin.root, kotlin.repo, kotlin.repo,
                listOf(
                    FileChange("src/main/kotlin/com/example/bank/Money.kt", money, money.text + "\n// edited\n"),
                    FileChange("src/main/kotlin/com/example/bank/Entry.kt", entry, entry.text + "\n// edited\n", movedTo = "src/main/kotlin/com/example/bank/Money.kt"),
                ),
                "x",
            )
        }
        assertContains(failure.message.orEmpty(), "exists already")
        assertEquals(before.mapValues { it.value.toList() }, kotlin.snapshot().mapValues { it.value.toList() })
        assertContentEquals(before.getValue("src/main/kotlin/com/example/bank/Money.kt"), kotlin.bytes("src/main/kotlin/com/example/bank/Money.kt").toList())
    }

    @Test
    fun `the next query sees a write at once, however recently the worktree was checked`() {
        val slow = WriteHarness("write/kotlin", overlayCheckMs = 3_600_000)
        assertContains(slow.find("Account.describe"), "describe")
        val read = slow.read("Account.describe")
        slow.blocking { slow.service.replace(slow.root, "Account.describe", read.hash, read.code.replace("describe()", "describe2()")) }
        assertContains(slow.find("Account.describe2"), "describe2")
    }
}
