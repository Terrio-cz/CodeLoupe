package codeloupe.aot

import codeloupe.index.ParseFile

/**
 * What the trainer gives a recorded parse worker to parse: the shapes a real edit brings (classes, objects, generics, lambdas,
 * annotations, a script, Java), so that the parser's classes the first real request needs are in the cache.
 */
internal object WorkerSamples {
    private val kotlin = """
        package demo.billing

        import java.time.Instant
        import java.util.UUID
        import kotlinx.coroutines.flow.Flow

        /** An invoice line. */
        @JvmInline
        value class Cents(val value: Long)

        sealed interface Event {
            data class Paid(val id: UUID, val at: Instant) : Event
            data object Cancelled : Event
        }

        enum class Plan(val limit: Int) { FREE(10), PRO(1_000) }

        abstract class Repository<T : Any>(protected val name: String) {
            abstract suspend fun find(id: UUID): T?
            open fun all(): List<T> = emptyList()
        }

        class Billing(private val rate: Int, private val repo: Repository<String>) : AutoCloseable {
            private val cache = mutableMapOf<UUID, Cents>()

            companion object {
                const val MAX = 42
                fun create(): Billing = Billing(1, object : Repository<String>("x") { override suspend fun find(id: UUID) = null })
            }

            fun total(lines: List<Cents>, discount: Int = 0): Cents =
                Cents(lines.sumOf { it.value } * rate - discount)

            suspend fun settle(id: UUID): Event = when (val found = repo.find(id)) {
                null -> Event.Cancelled
                else -> Event.Paid(id, Instant.now()).also { cache[id] = Cents(found.length.toLong()) }
            }

            fun <R> withLock(block: (Int) -> R): R = synchronized(this) { block(MAX) }

            val Plan.label: String get() = name.lowercase()

            override fun close() {
                for ((key, value) in cache) if (value.value > 0) cache.remove(key) else continue
                try { check(rate > 0) { "rate ${'$'}rate" } } catch (e: IllegalStateException) { throw e } finally { cache.clear() }
            }
        }

        fun interface Pricing { fun price(plan: Plan): Cents }

        typealias Prices = Map<Plan, Cents>

        internal fun Flow<Event>.paidOnly(): Flow<Event> = this

        private val default = Pricing { Cents(it.limit.toLong()) }
    """.trimIndent() + "\n"

    private val script = """
        plugins { kotlin("jvm") version "2.2.0" }

        val versions = listOf("1", "2").associateWith { it.length }
        tasks.register("hello") { doLast { println(versions) } }
    """.trimIndent() + "\n"

    private val java = """
        package demo.billing;

        import java.util.List;
        import java.util.Map;
        import java.util.function.Function;

        /** An order. */
        public final class Order<T extends Comparable<T>> implements Comparable<Order<T>> {
            private static final int MAX = 3;
            private final List<T> items;

            @Deprecated
            public Order(List<T> items) { this.items = items; }

            public int size() { return items.size(); }

            public <R> R map(Function<List<T>, R> mapper) { return mapper.apply(items); }

            @Override
            public int compareTo(Order<T> other) {
                switch (other.size()) {
                    case 0: return 1;
                    default: return Integer.compare(size(), other.size());
                }
            }

            public record Line(String sku, int count) {}

            enum State { OPEN, CLOSED }

            interface Listener { void changed(Map<String, Integer> counts); }
        }
    """.trimIndent() + "\n"

    val files: List<ParseFile> = listOf(
        ParseFile("src/main/kotlin/demo/billing/Billing.kt", kotlin),
        ParseFile("build.gradle.kts", script),
        ParseFile("src/main/java/demo/billing/Order.java", java),
    )
}
