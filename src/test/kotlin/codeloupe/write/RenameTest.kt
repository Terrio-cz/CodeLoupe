package codeloupe.write

import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The last acceptance criterion of the write tools: rename on ten symbols and the code still compiles - here on the two fixture
 * projects, one rename after the other, each on the result of the one before.
 */
class RenameTest {
    private fun rename(harness: WriteHarness, name: String, to: String, hash: String? = harness.read(name).hash, dryRun: Boolean = false): String =
        harness.blocking { harness.service.rename(harness.root, name, to, hash, dryRun) }

    @Test
    fun `twelve Kotlin symbols are renamed one after the other and the project compiles`() {
        val h = WriteHarness("write/kotlin")
        val renames = listOf(
            "com.example.bank.Account" to "BankAccount",
            "BankAccount.deposit" to "credit",
            "Ledger.record" to "post",
            "BankAccount.balance" to "funds",
            "Money.format" to "render",
            "Kind.DEBIT" to "OUT",
            "com.example.bank.total" to "grandTotal",
            "com.example.bank.Entry" to "LedgerEntry",
            "com.example.bank.AccountService" to "TransferService",
            "Outcome.Refused" to "Rejected",
            "com.example.bank.Registry" to "Directory",
            "SavingsAccount.interest" to "accrued",
            "Money.cents" to "amountInCents",
        )
        for ((name, to) in renames) {
            val answer = rename(h, name, to)
            assertContains(answer, "renamed", message = "$name -> $to: $answer")
        }
        assertEquals(emptyList(), Compile.kotlin(h.repo), "the renamed Kotlin project compiles")
        val reports = h.text("src/main/kotlin/com/example/bank/report/Reports.kt")
        for (import in listOf("import com.example.bank.BankAccount", "import com.example.bank.TransferService", "import com.example.bank.Directory", "import com.example.bank.LedgerEntry", "import com.example.bank.grandTotal", "import com.example.bank.Money as Cash")) {
            assertContains(reports, import)
        }
        assertContains(reports, "is Outcome.Rejected -> \"refused: \${outcome.reason}\"")
        assertContains(reports, "it.kind == Kind.OUT")
        assertEquals(13, h.journal.read().size, "every rename is in the journal")
    }

    @Test
    fun `twelve Java symbols are renamed one after the other and the project compiles`() {
        val h = WriteHarness("write/java")
        val renames = listOf(
            "com.example.shop.Item" to "Product",
            "Priced.price" to "cost",
            "Cart.total" to "sum",
            "Status.closed" to "isClosed",
            "Cart.Line" to "Row",
            "com.example.shop.Pair" to "Duo",
            "Pricing.round" to "roundOff",
            "Cart.items" to "lines",
            "Product.of" to "named",
            "Pricing.PERCENT" to "HUNDRED",
            "Shop.checkout" to "settle",
            "Cart.add" to "append",
        )
        for ((name, to) in renames) {
            // Overloads are one symbol; the hash is that of any one of them.
            val answer = if (name == "Cart.add") rename(h, name, to, hash = h.read("Cart.add(Product)").hash) else rename(h, name, to)
            assertContains(answer, "renamed", message = "$name -> $to: $answer")
        }
        assertEquals(emptyList(), Compile.java(h.repo), "the renamed Java project compiles")
        assertTrue(h.repo.resolve("src/main/java/com/example/shop/Product.java").exists() && !h.repo.resolve("src/main/java/com/example/shop/Item.java").exists(), "a public type moves to the file of its name")
        assertTrue(h.repo.resolve("src/main/java/com/example/shop/Duo.java").exists())
        val page = h.text("src/main/java/com/example/shop/web/Page.java")
        assertContains(page, "import static com.example.shop.Pricing.roundOff;")
        assertContains(page, "import com.example.shop.Product;")
        assertContains(page, "Product first = Product.named(\"first\");")
        assertContains(h.text("src/main/java/com/example/shop/Status.java"), "public boolean isClosed() {\n            return false;")
    }

    @Test
    fun `a dry run says what would change and changes nothing`() {
        val h = WriteHarness("write/kotlin")
        val before = h.snapshot()
        val answer = rename(h, "Money.format", "render", hash = null, dryRun = true)
        assertContains(answer, "would rename com.example.bank.Money.format -> render")
        assertContains(answer, "dry run")
        assertEquals(before, h.snapshot())
        assertTrue(h.journal.read().isEmpty())
    }

    @Test
    fun `a rename that cannot be done right is refused before anything is written`() {
        val h = WriteHarness("write/kotlin")
        val before = h.snapshot()
        fun refused(name: String, to: String): String = assertFailsWith<WriteRefused> { rename(h, name, to) }.message.orEmpty()
        assertContains(refused("Money.plus", "add"), "operator")
        assertContains(refused("Money.compareTo", "cmp"), "read by its name")
        assertContains(refused("Account.entries", "describe"), "exists already")
        assertContains(refused("com.example.bank.Money", "Account"), "exists already")
        assertContains(refused("Account.deposit", "class"), "keyword")
        assertContains(refused("Account.deposit", "not valid"), "plain identifier")
        assertEquals(before, h.snapshot())
        val stale = assertFailsWith<WriteRefused> { rename(h, "Account.deposit", "credit", hash = "0000000000") }.message.orEmpty()
        assertContains(stale, "hash mismatch")
    }

    @Test
    fun `a use the index is not sure of is left to the caller, not renamed`() {
        val odd = "src/main/kotlin/com/example/bank/Odd.kt"
        val h = WriteHarness("write/kotlin", extra = mapOf(odd to "package com.example.bank\n\nclass Printer {\n    fun describe() = \"p\"\n}\n\nfun mystery(): String = lookup().describe()\n\n"))
        val answer = rename(h, "Account.describe", "explain")
        assertContains(answer, "left to you")
        assertContains(answer, "Odd.kt:7")
        assertContains(h.text(odd), "lookup().describe()")
        assertContains(h.text("src/main/kotlin/com/example/bank/Account.kt"), "open fun explain()")
    }

    @Test
    fun `an import that serves several functions of one name stays, and the file that uses the renamed one gets a new import`() {
        val h = WriteHarness(
            "write/kotlin",
            extra = mapOf(
                "src/main/kotlin/com/example/bank/dto/Responses.kt" to "package com.example.bank.dto\n\nimport com.example.bank.Account\nimport com.example.bank.Money\n\nfun Account.toResponse(): String = id\n\nfun Money.toResponse(): String = format()\n\n",
                "src/main/kotlin/com/example/bank/api/Use.kt" to "package com.example.bank.api\n\nimport com.example.bank.Account\nimport com.example.bank.Money\nimport com.example.bank.dto.toResponse\n\nfun both(a: Account, m: Money) = a.toResponse() + m.toResponse()\n\nfun onlyMoney(m: Money) = m.toResponse()\n",
            ),
        )
        assertEquals(emptyList(), Compile.kotlin(h.repo))
        assertContains(rename(h, "Account.toResponse", "toAccountResponse"), "renamed")
        val api = h.text("src/main/kotlin/com/example/bank/api/Use.kt")
        assertContains(api, "import com.example.bank.dto.toResponse\n")
        assertContains(api, "import com.example.bank.dto.toAccountResponse")
        assertContains(api, "a.toAccountResponse() + m.toResponse()")
        assertContains(api, "fun onlyMoney(m: Money) = m.toResponse()")
        assertEquals(emptyList(), Compile.kotlin(h.repo))
    }

    @Test
    fun `labels that carry a function's name are renamed with it`() {
        val file = "src/main/kotlin/com/example/bank/Labels.kt"
        val h = WriteHarness(
            "write/kotlin",
            extra = mapOf(file to "package com.example.bank\n\nfun Money.twice(): Money = run { this@twice + this@twice }\n\nfun loop(items: List<Int>, action: (Int) -> Unit) {\n    for (i in items) action(i)\n}\n\nfun caller() {\n    loop(listOf(1)) { if (it > 0) return@loop }\n}\n"),
        )
        assertEquals(emptyList(), Compile.kotlin(h.repo))
        rename(h, "Money.twice", "doubled")
        rename(h, "com.example.bank.loop", "each")
        assertContains(h.text(file), "fun Money.doubled(): Money = run { this@doubled + this@doubled }")
        assertContains(h.text(file), "each(listOf(1)) { if (it > 0) return@each }")
        assertEquals(emptyList(), Compile.kotlin(h.repo))
    }

    @Test
    fun `renaming a member renames what it overrides and what overrides it`() {
        val h = WriteHarness("write/kotlin")
        rename(h, "Ledger.record", "post")
        assertContains(h.text("src/main/kotlin/com/example/bank/Entry.kt"), "override fun post(entry: Entry): Boolean")
        assertContains(h.text("src/main/kotlin/com/example/bank/Entry.kt"), "fun post(entry: Entry): Boolean\n")
        assertContains(h.text("src/main/kotlin/com/example/bank/Service.kt"), "ledger.post(debit)")
        rename(h, "Account.describe", "explain")
        assertContains(h.text("src/main/kotlin/com/example/bank/Account.kt"), "override fun explain(): String")
        assertEquals(emptyList(), Compile.kotlin(h.repo))
    }
}
